package com.kinetix.audit.persistence

import com.kinetix.audit.model.AuditEvent
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

// Stable bigint key for pg_advisory_xact_lock — any constant works as long as every
// audit-chain writer agrees on it. Picked to avoid collisions with other advisory locks
// in the platform; "kinetix.audit.chain".hashCode() materialised as a long.
private const val AUDIT_CHAIN_LOCK_KEY: Long = 7_345_920_188_731L

class ExposedAuditEventRepository(private val db: Database? = null) : AuditEventRepository {

    override suspend fun save(event: AuditEvent): Unit = newSuspendedTransaction(
        transactionIsolation = Connection.TRANSACTION_READ_COMMITTED,
        db = db,
    ) {
        // Serialize chain appends across the cluster. The chain is intrinsically serial
        // (each recordHash depends on the prior recordHash) — without this, two concurrent
        // writers can both read the same latest hash and produce two events claiming the
        // same previousHash, breaking verifyChain. Auto-released on COMMIT/ROLLBACK.
        // Runs at READ_COMMITTED so the SELECT below sees the prior holder's COMMIT;
        // the pool's REPEATABLE_READ default would freeze the snapshot at the lock
        // acquisition and serve stale data even with the lock held.
        exec("SELECT pg_advisory_xact_lock($AUDIT_CHAIN_LOCK_KEY)") { rs -> rs.next() }

        // Kafka inbox-dedup: at-least-once delivery can replay an already-persisted
        // record. If this event carries Kafka coordinates and a row with the same
        // (topic, partition, offset) already exists, it is a redelivery — skip it
        // entirely. The skip happens here, before any chaining or sequence-number
        // assignment, so a duplicate consumes no sequence number and does not
        // touch the hash chain. Two genuinely different events never share an
        // offset on the same partition, so there is zero false-rejection risk.
        // The check is safe under the advisory lock acquired above, which already
        // serialises every chain append — there is no SELECT-then-INSERT race.
        if (event.sourceTopic != null && event.sourcePartition != null && event.sourceOffset != null) {
            val alreadyPersisted = AuditEventsTable
                .select(AuditEventsTable.id)
                .where {
                    (AuditEventsTable.sourceTopic eq event.sourceTopic) and
                        (AuditEventsTable.sourcePartition eq event.sourcePartition) and
                        (AuditEventsTable.sourceOffset eq event.sourceOffset)
                }
                .limit(1)
                .empty()
                .not()
            if (alreadyPersisted) {
                return@newSuspendedTransaction
            }
        }

        val latestHash = AuditEventsTable
            .select(AuditEventsTable.recordHash)
            .orderBy(AuditEventsTable.id, SortOrder.DESC)
            .limit(1)
            .map { it[AuditEventsTable.recordHash] }
            .firstOrNull()
            ?.takeIf { it.isNotEmpty() }

        // Truncate timestamps to microseconds to match PostgreSQL TIMESTAMPTZ precision.
        val storedReceivedAt = event.receivedAt.truncatedTo(ChronoUnit.MICROS)
        val storedTradedAt = event.tradedAt?.let { Instant.parse(it).truncatedTo(ChronoUnit.MICROS) }

        // Normalize numeric strings to canonical form so the hash matches after DB round-trip.
        val normalizedQuantity = event.quantity?.toBigDecimal()?.stripTrailingZeros()?.toPlainString()
        val normalizedPriceAmount = event.priceAmount?.toBigDecimal()?.stripTrailingZeros()?.toPlainString()

        val eventForHash = event.copy(
            receivedAt = storedReceivedAt,
            tradedAt = storedTradedAt?.toString(),
            quantity = normalizedQuantity,
            priceAmount = normalizedPriceAmount,
        )
        val recordHash = AuditHasher.computeHash(eventForHash, latestHash)

        val maxSeqExpr = AuditEventsTable.sequenceNumber.max()
        val maxSeq: Long = AuditEventsTable
            .select(maxSeqExpr)
            .map { it[maxSeqExpr] }
            .firstOrNull() ?: 0L
        val assignedSequenceNumber = maxSeq + 1L

        AuditEventsTable.insert {
            it[tradeId] = event.tradeId
            it[bookId] = event.bookId
            it[instrumentId] = event.instrumentId
            it[assetClass] = event.assetClass
            it[side] = event.side
            it[quantity] = normalizedQuantity?.toBigDecimal()
            it[priceAmount] = normalizedPriceAmount?.toBigDecimal()
            it[priceCurrency] = event.priceCurrency
            it[tradedAt] = storedTradedAt?.let { ts -> OffsetDateTime.ofInstant(ts, ZoneOffset.UTC) }
            it[receivedAt] = OffsetDateTime.ofInstant(storedReceivedAt, ZoneOffset.UTC)
            it[AuditEventsTable.previousHash] = latestHash
            it[AuditEventsTable.recordHash] = recordHash
            it[userId] = event.userId
            it[userRole] = event.userRole
            it[traderId] = event.traderId
            it[eventType] = event.eventType
            it[modelName] = event.modelName
            it[scenarioId] = event.scenarioId
            it[limitId] = event.limitId
            it[submissionId] = event.submissionId
            it[details] = event.details
            it[sequenceNumber] = event.sequenceNumber ?: assignedSequenceNumber
            it[correlationId] = event.correlationId
            it[sourceTopic] = event.sourceTopic
            it[sourcePartition] = event.sourcePartition
            it[sourceOffset] = event.sourceOffset
        }
    }

    override suspend fun findAll(): List<AuditEvent> = newSuspendedTransaction(db = db) {
        AuditEventsTable
            .selectAll()
            .orderBy(AuditEventsTable.id)
            .map { it.toAuditEvent() }
    }

    override suspend fun findByBookId(bookId: String): List<AuditEvent> = newSuspendedTransaction(db = db) {
        AuditEventsTable
            .selectAll()
            .where { AuditEventsTable.bookId eq bookId }
            .orderBy(AuditEventsTable.id)
            .map { it.toAuditEvent() }
    }

    override suspend fun findPage(afterId: Long, limit: Int): List<AuditEvent> = newSuspendedTransaction(db = db) {
        AuditEventsTable
            .selectAll()
            .where { AuditEventsTable.id greater afterId }
            .orderBy(AuditEventsTable.id, SortOrder.ASC)
            .limit(limit)
            .map { it.toAuditEvent() }
    }

    override suspend fun findPage(
        afterId: Long,
        limit: Int,
        bookId: String?,
        tradeId: String?,
        eventType: String?,
        from: Instant?,
        to: Instant?,
    ): List<AuditEvent> = newSuspendedTransaction(db = db) {
        AuditEventsTable
            .selectAll()
            .where { AuditEventsTable.id greater afterId }
            .apply {
                bookId?.let { andWhere { AuditEventsTable.bookId eq it } }
                tradeId?.let { andWhere { AuditEventsTable.tradeId eq it } }
                eventType?.let { andWhere { AuditEventsTable.eventType eq it } }
                from?.let { andWhere { AuditEventsTable.receivedAt greaterEq it.atOffset(ZoneOffset.UTC) } }
                to?.let { andWhere { AuditEventsTable.receivedAt lessEq it.atOffset(ZoneOffset.UTC) } }
            }
            .orderBy(AuditEventsTable.id, SortOrder.ASC)
            .limit(limit)
            .map { it.toAuditEvent() }
    }

    override suspend fun countAll(): Long = newSuspendedTransaction(db = db) {
        AuditEventsTable.selectAll().count()
    }

    override suspend fun countSince(since: Instant): Long = newSuspendedTransaction(db = db) {
        AuditEventsTable
            .selectAll()
            .where { AuditEventsTable.receivedAt greaterEq since.atOffset(ZoneOffset.UTC) }
            .count()
    }

    override suspend fun findByTradeId(tradeId: String): AuditEvent? = newSuspendedTransaction(db = db) {
        AuditEventsTable
            .selectAll()
            .where { AuditEventsTable.tradeId eq tradeId }
            .limit(1)
            .map { it.toAuditEvent() }
            .firstOrNull()
    }

    override suspend fun nextSequenceNumber(): Long = newSuspendedTransaction(db = db) {
        val maxSeqExpr = AuditEventsTable.sequenceNumber.max()
        val current: Long = AuditEventsTable
            .select(maxSeqExpr)
            .map { it[maxSeqExpr] }
            .firstOrNull() ?: 0L
        current + 1L
    }

    private fun ResultRow.toAuditEvent(): AuditEvent = AuditEvent(
        id = this[AuditEventsTable.id],
        tradeId = this[AuditEventsTable.tradeId],
        bookId = this[AuditEventsTable.bookId],
        instrumentId = this[AuditEventsTable.instrumentId],
        assetClass = this[AuditEventsTable.assetClass],
        side = this[AuditEventsTable.side],
        quantity = this[AuditEventsTable.quantity]?.stripTrailingZeros()?.toPlainString(),
        priceAmount = this[AuditEventsTable.priceAmount]?.stripTrailingZeros()?.toPlainString(),
        priceCurrency = this[AuditEventsTable.priceCurrency],
        tradedAt = this[AuditEventsTable.tradedAt]?.toInstant()?.toString(),
        receivedAt = this[AuditEventsTable.receivedAt].toInstant(),
        previousHash = this[AuditEventsTable.previousHash],
        recordHash = this[AuditEventsTable.recordHash],
        userId = this[AuditEventsTable.userId],
        userRole = this[AuditEventsTable.userRole],
        traderId = this[AuditEventsTable.traderId],
        eventType = this[AuditEventsTable.eventType],
        modelName = this[AuditEventsTable.modelName],
        scenarioId = this[AuditEventsTable.scenarioId],
        limitId = this[AuditEventsTable.limitId],
        submissionId = this[AuditEventsTable.submissionId],
        details = this[AuditEventsTable.details],
        sequenceNumber = this[AuditEventsTable.sequenceNumber],
        correlationId = this[AuditEventsTable.correlationId],
        sourceTopic = this[AuditEventsTable.sourceTopic],
        sourcePartition = this[AuditEventsTable.sourcePartition],
        sourceOffset = this[AuditEventsTable.sourceOffset],
    )
}

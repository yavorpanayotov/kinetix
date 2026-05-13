package com.kinetix.position.persistence

import com.kinetix.common.model.*
import com.kinetix.common.model.instrument.InstrumentTypeCode
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Currency

class ExposedTradeEventRepository(private val db: Database? = null) : TradeEventRepository {

    override suspend fun save(trade: Trade): Unit = newSuspendedTransaction(db = db) {
        TradeEventsTable.insert {
            it[tradeId] = trade.tradeId.value
            it[bookId] = trade.bookId.value
            it[instrumentId] = trade.instrumentId.value
            it[assetClass] = trade.assetClass.name
            it[side] = trade.side.name
            it[quantity] = trade.quantity
            it[priceAmount] = trade.price.amount
            it[priceCurrency] = trade.price.currency.currencyCode
            it[tradedAt] = trade.tradedAt.atOffset(ZoneOffset.UTC)
            it[createdAt] = OffsetDateTime.now(ZoneOffset.UTC)
            it[eventType] = trade.eventType.name
            it[status] = trade.status.name
            it[originalTradeId] = trade.originalTradeId?.value
            it[counterpartyId] = trade.counterpartyId
            it[instrumentType] = trade.instrumentType.name
            it[strategyId] = trade.strategyId
            it[traderId] = trade.traderId?.value
        }
    }

    override suspend fun findByTradeId(tradeId: TradeId): Trade? = newSuspendedTransaction(db = db) {
        TradeEventsTable
            .selectAll()
            .where { TradeEventsTable.tradeId eq tradeId.value }
            .singleOrNull()
            ?.toTrade()
    }

    override suspend fun findByBookId(bookId: BookId): List<Trade> = newSuspendedTransaction(db = db) {
        TradeEventsTable
            .selectAll()
            .where { TradeEventsTable.bookId eq bookId.value }
            .orderBy(TradeEventsTable.tradedAt)
            .map { it.toTrade() }
    }

    override suspend fun findByBookIdInRange(bookId: BookId, from: Instant, to: Instant): List<Trade> = newSuspendedTransaction(db = db) {
        TradeEventsTable
            .selectAll()
            .where {
                (TradeEventsTable.bookId eq bookId.value) and
                    (TradeEventsTable.tradedAt greaterEq from.atOffset(ZoneOffset.UTC)) and
                    (TradeEventsTable.tradedAt lessEq to.atOffset(ZoneOffset.UTC))
            }
            .orderBy(TradeEventsTable.tradedAt)
            .map { it.toTrade() }
    }

    override suspend fun updateStatus(tradeId: TradeId, status: TradeStatus): Unit = newSuspendedTransaction(db = db) {
        TradeEventsTable.update({ TradeEventsTable.tradeId eq tradeId.value }) {
            it[TradeEventsTable.status] = status.name
        }
    }

    override suspend fun findByCounterpartyId(counterpartyId: String): List<Trade> = newSuspendedTransaction(db = db) {
        TradeEventsTable
            .selectAll()
            .where { TradeEventsTable.counterpartyId eq counterpartyId }
            .map { it.toTrade() }
    }

    override suspend fun countSince(since: Instant): Long = newSuspendedTransaction(db = db) {
        TradeEventsTable
            .selectAll()
            .where { TradeEventsTable.createdAt greaterEq since.atOffset(ZoneOffset.UTC) }
            .count()
    }

    override suspend fun findByBookIdPaged(
        bookId: BookId,
        offset: Long,
        limit: Int,
        counterpartyId: String?,
    ): List<Trade> = newSuspendedTransaction(db = db) {
        TradeEventsTable
            .selectAll()
            .where { pagedFilter(bookId, counterpartyId) }
            .orderBy(TradeEventsTable.tradedAt to SortOrder.DESC, TradeEventsTable.tradeId to SortOrder.ASC)
            .offset(offset)
            .limit(limit)
            .map { it.toTrade() }
    }

    override suspend fun countByBookId(bookId: BookId, counterpartyId: String?): Long =
        newSuspendedTransaction(db = db) {
            TradeEventsTable
                .selectAll()
                .where { pagedFilter(bookId, counterpartyId) }
                .count()
        }

    private fun SqlExpressionBuilder.pagedFilter(bookId: BookId, counterpartyId: String?): Op<Boolean> {
        var predicate: Op<Boolean> = TradeEventsTable.bookId eq bookId.value
        if (counterpartyId != null) {
            predicate = predicate and (TradeEventsTable.counterpartyId eq counterpartyId)
        }
        return predicate
    }

    override suspend fun bulkInsertForSeed(trades: List<Trade>) {
        if (trades.isEmpty()) return
        // Exposed's batchInsert collapses to a single multi-row INSERT inside one
        // transaction. For 50–100k seed trades on Postgres this finishes in seconds.
        // Chunk to 5k per transaction so the WAL doesn't bloat.
        trades.chunked(5_000).forEach { chunk ->
            newSuspendedTransaction(db = db) {
                TradeEventsTable.batchInsert(chunk, shouldReturnGeneratedValues = false) { trade ->
                    this[TradeEventsTable.tradeId] = trade.tradeId.value
                    this[TradeEventsTable.bookId] = trade.bookId.value
                    this[TradeEventsTable.instrumentId] = trade.instrumentId.value
                    this[TradeEventsTable.assetClass] = trade.assetClass.name
                    this[TradeEventsTable.side] = trade.side.name
                    this[TradeEventsTable.quantity] = trade.quantity
                    this[TradeEventsTable.priceAmount] = trade.price.amount
                    this[TradeEventsTable.priceCurrency] = trade.price.currency.currencyCode
                    this[TradeEventsTable.tradedAt] = trade.tradedAt.atOffset(ZoneOffset.UTC)
                    this[TradeEventsTable.createdAt] = OffsetDateTime.now(ZoneOffset.UTC)
                    this[TradeEventsTable.eventType] = trade.eventType.name
                    this[TradeEventsTable.status] = trade.status.name
                    this[TradeEventsTable.originalTradeId] = trade.originalTradeId?.value
                    this[TradeEventsTable.counterpartyId] = trade.counterpartyId
                    this[TradeEventsTable.instrumentType] = trade.instrumentType.name
                    this[TradeEventsTable.strategyId] = trade.strategyId
                    this[TradeEventsTable.traderId] = trade.traderId?.value
                }
            }
        }
    }

    private fun ResultRow.toTrade(): Trade = Trade(
        tradeId = TradeId(this[TradeEventsTable.tradeId]),
        bookId = BookId(this[TradeEventsTable.bookId]),
        instrumentId = InstrumentId(this[TradeEventsTable.instrumentId]),
        assetClass = AssetClass.valueOf(this[TradeEventsTable.assetClass]),
        side = Side.valueOf(this[TradeEventsTable.side]),
        quantity = this[TradeEventsTable.quantity],
        price = Money(
            this[TradeEventsTable.priceAmount],
            Currency.getInstance(this[TradeEventsTable.priceCurrency]),
        ),
        tradedAt = this[TradeEventsTable.tradedAt].toInstant(),
        eventType = TradeEventType.valueOf(this[TradeEventsTable.eventType]),
        status = TradeStatus.valueOf(this[TradeEventsTable.status]),
        originalTradeId = this[TradeEventsTable.originalTradeId]?.let { TradeId(it) },
        counterpartyId = this[TradeEventsTable.counterpartyId],
        instrumentType = InstrumentTypeCode.fromString(this[TradeEventsTable.instrumentType]),
        strategyId = this[TradeEventsTable.strategyId],
        traderId = this[TradeEventsTable.traderId]?.let { TraderId(it) },
    )
}

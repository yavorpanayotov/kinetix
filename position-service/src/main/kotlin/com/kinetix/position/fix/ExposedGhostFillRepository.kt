package com.kinetix.position.fix

import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory
import java.time.ZoneOffset

class ExposedGhostFillRepository(private val db: Database? = null) : GhostFillRepository {

    private val logger = LoggerFactory.getLogger(ExposedGhostFillRepository::class.java)

    override suspend fun save(fill: GhostFill) {
        try {
            newSuspendedTransaction(db = db) {
                GhostFillsTable.insert {
                    it[orderId] = fill.orderId
                    it[priorStatus] = fill.priorStatus.name
                    it[venue] = fill.venue
                    it[fixExecId] = fill.fixExecId
                    it[fillQty] = fill.fillQty
                    it[fillPrice] = fill.fillPrice
                    it[cumulativeQty] = fill.cumulativeQty
                    it[detectedAt] = fill.detectedAt.atOffset(ZoneOffset.UTC)
                    it[rawEvent] = fill.rawEvent
                }
            }
        } catch (e: ExposedSQLException) {
            // (order_id, fix_exec_id) UNIQUE collision: same fill replayed by FIX
            // session resync. Idempotent — log and move on.
            logger.info(
                "Ghost fill already recorded: orderId={} fixExecId={}",
                fill.orderId, fill.fixExecId,
            )
        }
    }

    override suspend fun findByOrderId(orderId: String): List<GhostFill> = newSuspendedTransaction(db = db) {
        GhostFillsTable
            .selectAll()
            .where { GhostFillsTable.orderId eq orderId }
            .orderBy(GhostFillsTable.detectedAt to SortOrder.DESC)
            .map(::toGhostFill)
    }

    private fun toGhostFill(row: ResultRow): GhostFill = GhostFill(
        orderId = row[GhostFillsTable.orderId],
        priorStatus = OrderStatus.valueOf(row[GhostFillsTable.priorStatus]),
        venue = row[GhostFillsTable.venue],
        fixExecId = row[GhostFillsTable.fixExecId],
        fillQty = row[GhostFillsTable.fillQty],
        fillPrice = row[GhostFillsTable.fillPrice],
        cumulativeQty = row[GhostFillsTable.cumulativeQty],
        detectedAt = row[GhostFillsTable.detectedAt].toInstant(),
        rawEvent = row[GhostFillsTable.rawEvent],
    )
}

package com.kinetix.position.fix

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.Side
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Currency

class ExposedExecutionOrderRepository(private val db: Database? = null) : ExecutionOrderRepository {

    override suspend fun save(order: Order): Unit = newSuspendedTransaction(db = db) {
        ExecutionOrdersTable.insert {
            it[orderId] = order.orderId
            it[bookId] = order.bookId
            it[instrumentId] = order.instrumentId
            it[side] = order.side.name
            it[quantity] = order.quantity
            it[orderType] = order.orderType
            it[limitPrice] = order.limitPrice
            it[arrivalPrice] = order.arrivalPrice
            it[submittedAt] = order.submittedAt.atOffset(ZoneOffset.UTC)
            it[status] = order.status.name
            it[riskCheckResult] = order.riskCheckResult
            it[riskCheckDetails] = order.riskCheckDetails
            it[fixSessionId] = order.fixSessionId
            it[assetClass] = order.assetClass.name
            it[currency] = order.currency.currencyCode
            it[timeInForce] = order.timeInForce.name
            it[expiresAt] = order.expiresAt?.atOffset(ZoneOffset.UTC)
            it[instrumentType] = order.instrumentType
            it[createdAt] = OffsetDateTime.now(ZoneOffset.UTC)
            it[updatedAt] = OffsetDateTime.now(ZoneOffset.UTC)
        }
    }

    override suspend fun updateStatus(
        orderId: String,
        status: OrderStatus,
        riskCheckResult: String?,
        riskCheckDetails: String?,
    ): Unit = newSuspendedTransaction(db = db) {
        ExecutionOrdersTable.update({ ExecutionOrdersTable.orderId eq orderId }) {
            it[ExecutionOrdersTable.status] = status.name
            it[ExecutionOrdersTable.riskCheckResult] = riskCheckResult
            it[ExecutionOrdersTable.riskCheckDetails] = riskCheckDetails
            it[updatedAt] = OffsetDateTime.now(ZoneOffset.UTC)
        }
    }

    override suspend fun updateQuantityAndPrice(
        orderId: String,
        quantity: java.math.BigDecimal,
        limitPrice: java.math.BigDecimal?,
    ): Unit = newSuspendedTransaction(db = db) {
        ExecutionOrdersTable.update({ ExecutionOrdersTable.orderId eq orderId }) {
            it[ExecutionOrdersTable.quantity] = quantity
            it[ExecutionOrdersTable.limitPrice] = limitPrice
            it[updatedAt] = OffsetDateTime.now(ZoneOffset.UTC)
        }
    }

    override suspend fun findById(orderId: String): Order? = newSuspendedTransaction(db = db) {
        ExecutionOrdersTable
            .selectAll()
            .where { ExecutionOrdersTable.orderId eq orderId }
            .singleOrNull()
            ?.toOrder()
    }

    override suspend fun findByBookId(bookId: String): List<Order> = newSuspendedTransaction(db = db) {
        ExecutionOrdersTable
            .selectAll()
            .where { ExecutionOrdersTable.bookId eq bookId }
            .orderBy(ExecutionOrdersTable.submittedAt)
            .map { it.toOrder() }
    }

    override suspend fun findOpenDayAndGtdOrders(): List<Order> = newSuspendedTransaction(db = db) {
        // Non-terminal statuses that can still be auto-expired. PENDING_RISK_CHECK is
        // excluded because the order isn't yet on the wire; REJECTED/CANCELLED/FILLED/
        // EXPIRED are terminal per OrderStatus.isTerminal.
        val openStatuses = listOf(OrderStatus.APPROVED.name, OrderStatus.SENT.name, OrderStatus.PARTIAL.name)
        val tifs = listOf(TimeInForce.DAY.name, TimeInForce.GTD.name)
        ExecutionOrdersTable
            .selectAll()
            .where {
                (ExecutionOrdersTable.status inList openStatuses) and
                    (ExecutionOrdersTable.timeInForce inList tifs)
            }
            .map { it.toOrder() }
    }

    private fun ResultRow.toOrder() = Order(
        orderId = this[ExecutionOrdersTable.orderId],
        bookId = this[ExecutionOrdersTable.bookId],
        instrumentId = this[ExecutionOrdersTable.instrumentId],
        side = Side.valueOf(this[ExecutionOrdersTable.side]),
        quantity = this[ExecutionOrdersTable.quantity],
        orderType = this[ExecutionOrdersTable.orderType],
        limitPrice = this[ExecutionOrdersTable.limitPrice],
        arrivalPrice = this[ExecutionOrdersTable.arrivalPrice],
        submittedAt = this[ExecutionOrdersTable.submittedAt].toInstant(),
        status = OrderStatus.valueOf(this[ExecutionOrdersTable.status]),
        riskCheckResult = this[ExecutionOrdersTable.riskCheckResult],
        riskCheckDetails = this[ExecutionOrdersTable.riskCheckDetails],
        fixSessionId = this[ExecutionOrdersTable.fixSessionId],
        assetClass = runCatching { AssetClass.valueOf(this[ExecutionOrdersTable.assetClass]) }.getOrDefault(AssetClass.EQUITY),
        currency = runCatching { Currency.getInstance(this[ExecutionOrdersTable.currency]) }.getOrDefault(Currency.getInstance("USD")),
        timeInForce = runCatching { TimeInForce.valueOf(this[ExecutionOrdersTable.timeInForce]) }.getOrDefault(TimeInForce.GTC),
        expiresAt = this[ExecutionOrdersTable.expiresAt]?.toInstant(),
        instrumentType = this[ExecutionOrdersTable.instrumentType],
    )
}

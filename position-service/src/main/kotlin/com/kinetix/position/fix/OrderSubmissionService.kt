package com.kinetix.position.fix

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Side
import com.kinetix.common.model.TradeId
import com.kinetix.position.model.LimitBreach
import com.kinetix.position.service.BookTradeCommand
import com.kinetix.position.service.PreTradeCheckService
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency
import java.util.UUID

/**
 * Handles the lifecycle of a new order from client submission through optional FIX dispatch.
 *
 * The flow is:
 *   1. Validate and persist the order with status PENDING_RISK_CHECK.
 *   2. Run pre-trade risk check via [PreTradeCheckService] with a configurable timeout.
 *   3. Reject if the check is blocked (hard breach) or times out.
 *   4. Approve with FLAGGED result if soft breaches exist but no hard block.
 *   5. If a connected FIX session is supplied and the order is approved, dispatch via [FIXOrderSender].
 *   6. Return the order in its final status.
 *
 * This class is intentionally free of infrastructure concerns — all I/O is injected so that
 * unit tests can run without a database or FIX session.
 */
class OrderSubmissionService(
    private val orderRepository: ExecutionOrderRepository,
    private val sessionRepository: FIXSessionRepository,
    private val fixOrderSender: FIXOrderSender,
    private val preTradeCheckService: PreTradeCheckService,
    private val riskCheckTimeoutMs: Long = 500L,
) {

    private val logger = LoggerFactory.getLogger(OrderSubmissionService::class.java)

    companion object {
        const val ARRIVAL_PRICE_MAX_AGE_MS: Long = 30_000L
    }

    suspend fun submit(
        bookId: String,
        instrumentId: String,
        side: Side,
        quantity: BigDecimal,
        orderType: String,
        limitPrice: BigDecimal?,
        arrivalPrice: BigDecimal,
        fixSessionId: String?,
        assetClass: String = "EQUITY",
        currency: String = "USD",
        arrivalPriceTimestamp: Instant? = null,
        timeInForce: TimeInForce = TimeInForce.DAY,
        expiresAt: Instant? = null,
        maxGtdHorizonDays: Long = 90,
        instrumentType: String,
    ): Order {
        require(instrumentType.isNotBlank()) { "instrumentType must not be blank" }
        require(quantity > BigDecimal.ZERO) { "Quantity must be positive" }

        if (arrivalPriceTimestamp != null) {
            val ageMs = Instant.now().toEpochMilli() - arrivalPriceTimestamp.toEpochMilli()
            require(ageMs <= ARRIVAL_PRICE_MAX_AGE_MS) {
                "Arrival price is stale: observed ${ageMs}ms ago, limit is ${ARRIVAL_PRICE_MAX_AGE_MS}ms"
            }
        }

        val now = Instant.now()
        when (timeInForce) {
            TimeInForce.GTD -> {
                requireNotNull(expiresAt) { "expiresAt is required when timeInForce=GTD" }
                require(expiresAt.isAfter(now)) {
                    "expiresAt must be in the future (got $expiresAt, now $now)"
                }
                val maxHorizon = now.plusSeconds(maxGtdHorizonDays * 24 * 3600)
                require(!expiresAt.isAfter(maxHorizon)) {
                    "expiresAt $expiresAt exceeds the venue's max-GTD horizon of $maxGtdHorizonDays days"
                }
            }
            else -> require(expiresAt == null) {
                "expiresAt must be null when timeInForce=$timeInForce (only GTD orders carry an expiry)"
            }
        }

        val resolvedAssetClass = resolveAssetClass(assetClass)
        val resolvedCurrency = resolveCurrency(currency)

        val order = Order(
            orderId = UUID.randomUUID().toString(),
            bookId = bookId,
            instrumentId = instrumentId,
            side = side,
            quantity = quantity,
            orderType = orderType,
            limitPrice = limitPrice,
            arrivalPrice = arrivalPrice,
            submittedAt = now,
            status = OrderStatus.PENDING_RISK_CHECK,
            riskCheckResult = null,
            riskCheckDetails = null,
            fixSessionId = fixSessionId,
            assetClass = resolvedAssetClass,
            currency = resolvedCurrency,
            timeInForce = timeInForce,
            expiresAt = expiresAt,
            instrumentType = instrumentType,
        )
        orderRepository.save(order)
        logger.info(
            "Order created: orderId={}, book={}, instrument={}, side={}, qty={}",
            order.orderId, bookId, instrumentId, side, quantity,
        )

        val command = BookTradeCommand(
            tradeId = TradeId(UUID.randomUUID().toString()),
            bookId = BookId(bookId),
            instrumentId = InstrumentId(instrumentId),
            assetClass = resolvedAssetClass,
            side = side,
            quantity = quantity,
            price = Money(arrivalPrice, resolvedCurrency),
            tradedAt = order.submittedAt,
            instrumentType = instrumentType,
        )

        val checkResult = try {
            withTimeout(riskCheckTimeoutMs) {
                preTradeCheckService.check(command)
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn(
                "Pre-trade risk check timed out after {}ms: orderId={}, book={}, instrument={}",
                riskCheckTimeoutMs, order.orderId, bookId, instrumentId,
            )
            orderRepository.updateStatus(order.orderId, OrderStatus.REJECTED, "TIMEOUT", null)
            return order.copy(status = OrderStatus.REJECTED, riskCheckResult = "TIMEOUT")
        }

        if (checkResult.blocked) {
            val details = serializeBreaches(checkResult.breaches)
            logger.warn(
                "Order REJECTED by pre-trade check: orderId={}, book={}, instrument={}, breaches={}",
                order.orderId, bookId, instrumentId, details,
            )
            orderRepository.updateStatus(order.orderId, OrderStatus.REJECTED, "REJECTED", details)
            return order.copy(status = OrderStatus.REJECTED, riskCheckResult = "REJECTED", riskCheckDetails = details)
        }

        val (riskResult, riskDetails) = if (checkResult.breaches.isNotEmpty()) {
            val details = serializeBreaches(checkResult.breaches)
            logger.info(
                "Order approved with warnings: orderId={}, book={}, instrument={}, breaches={}",
                order.orderId, bookId, instrumentId, details,
            )
            "FLAGGED" to details
        } else {
            "APPROVED" to null
        }

        orderRepository.updateStatus(order.orderId, OrderStatus.APPROVED, riskResult, riskDetails)
        val approved = order.copy(
            status = OrderStatus.APPROVED,
            riskCheckResult = riskResult,
            riskCheckDetails = riskDetails,
        )

        if (fixSessionId == null) {
            return approved
        }

        val session = sessionRepository.findById(fixSessionId)
        if (session == null || session.status != FIXSessionStatus.CONNECTED) {
            logger.warn(
                "FIX session {} not connected — order {} remains APPROVED without dispatch",
                fixSessionId, approved.orderId,
            )
            return approved
        }

        fixOrderSender.send(approved, session)
        orderRepository.updateStatus(approved.orderId, OrderStatus.SENT)
        logger.info("Order dispatched via FIX: orderId={}, session={}", approved.orderId, fixSessionId)

        return approved.copy(status = OrderStatus.SENT)
    }

    private fun resolveAssetClass(assetClass: String): AssetClass =
        runCatching { AssetClass.valueOf(assetClass.uppercase()) }.getOrDefault(AssetClass.EQUITY)

    private fun resolveCurrency(currency: String): Currency =
        runCatching { Currency.getInstance(currency.uppercase()) }.getOrDefault(Currency.getInstance("USD"))

    private fun serializeBreaches(breaches: List<LimitBreach>): String =
        Json.encodeToString(breaches.map { breach ->
            mapOf(
                "limitType" to breach.limitType,
                "severity" to breach.severity.name,
                "currentValue" to breach.currentValue,
                "limitValue" to breach.limitValue,
                "message" to breach.message,
            )
        })
}

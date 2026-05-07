package com.kinetix.position.fix

import com.kinetix.common.kafka.events.RiskBreakEvent
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.TradeId
import com.kinetix.position.kafka.NoOpRiskBreakPublisher
import com.kinetix.position.kafka.RiskBreakPublisher
import com.kinetix.position.service.BookTradeCommand
import com.kinetix.position.service.TradeBookingService
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Dispatches incoming FIX ExecutionReports to the correct handler based on ExecType.
 *
 * Supported exec types:
 *   F / 1  — Fill / Partial Fill: dedup via fix_exec_id, persist fill, update order status,
 *             trigger [TradeBookingService] so the position is updated.
 *   4      — Cancelled: validate the order is in a cancellable state, advance to CANCELLED.
 *   5      — Replace (OrderCancelReplace): update the order's quantity and limit price.
 *
 * The processor is intentionally thin — it validates, persists, and delegates. Trade booking
 * side effects (position update, Kafka publish) are handled by [TradeBookingService].
 *
 * Overfill guard: if an inbound fill would push cumulative filled quantity past the order
 * quantity, the fill is rejected and logged as an error.
 */
class FIXExecutionReportProcessor(
    private val orderRepository: ExecutionOrderRepository,
    private val fillRepository: ExecutionFillRepository,
    private val tradeBookingService: TradeBookingService,
    private val executionCostService: ExecutionCostService,
    private val executionCostRepository: ExecutionCostRepository,
    private val ghostFillRepository: GhostFillRepository = NoOpGhostFillRepository,
    private val riskBreakPublisher: RiskBreakPublisher = NoOpRiskBreakPublisher(),
    private val clock: () -> Instant = Instant::now,
) {

    private val logger = LoggerFactory.getLogger(FIXExecutionReportProcessor::class.java)

    suspend fun process(event: FIXInboundFillEvent) {
        when (event.execType) {
            "F", "1" -> processFill(event)
            "4"      -> processCancel(event)
            "5"      -> processReplace(event)
            else     -> logger.warn("Unhandled ExecType '{}' for execId={}", event.execType, event.execId)
        }
    }

    // --- Fill (ExecType = F or 1) ---

    private suspend fun processFill(event: FIXInboundFillEvent) {
        val fillId = FIXMessageConverter.deterministicFillId(event.sessionId, event.execId)

        // Deduplication: the same exec report can arrive on reconnect
        if (fillRepository.existsByFixExecId(event.execId)) {
            logger.info("Duplicate fill ignored: execId={}, fillId={}", event.execId, fillId)
            return
        }

        val order = orderRepository.findById(event.orderId)
        if (order == null) {
            logger.error("Fill arrived for unknown order: orderId={}, execId={}", event.orderId, event.execId)
            return
        }

        if (order.isTerminal) {
            // Ghost-fill detection (ADR-0035 phase 2): a 35=8 fill against an
            // EXPIRED / CANCELLED / REJECTED order MUST surface as a CRITICAL
            // RiskBreak. We persist the fill against ghost_fills (so audit
            // captures the venue's claim) but DO NOT auto-update Position —
            // operator decides whether the fill is real.
            //
            // FILLED is excluded: receiving a fill against an already-FILLED
            // order is most likely an overfill and is handled by the existing
            // overfill guard rather than ghost-fill semantics.
            if (order.status == OrderStatus.FILLED) {
                logger.error(
                    "Fill arrived for FILLED order (overfill): orderId={}, execId={}",
                    order.orderId, event.execId,
                )
                return
            }
            recordGhostFill(order, event)
            return
        }

        // Overfill guard: cumulative from the event is the ground truth from the broker
        val existingFills = fillRepository.findByOrderId(order.orderId)
        val existingFilledQty = existingFills.fold(BigDecimal.ZERO) { acc, f -> acc + f.fillQty }
        if (existingFilledQty + event.lastQty > order.quantity) {
            logger.error(
                "Overfill rejected: orderId={}, orderQty={}, alreadyFilled={}, incomingQty={}",
                order.orderId, order.quantity, existingFilledQty, event.lastQty,
            )
            return
        }

        val isFull = event.cumulativeQty.compareTo(order.quantity) >= 0
        val fillType = if (isFull) FillType.FULL else FillType.PARTIAL

        val fill = ExecutionFill(
            fillId = fillId,
            orderId = order.orderId,
            bookId = order.bookId,
            instrumentId = order.instrumentId,
            fillTime = Instant.now(),
            fillQty = event.lastQty,
            fillPrice = event.lastPrice,
            fillType = fillType,
            venue = event.venue,
            cumulativeQty = event.cumulativeQty,
            averagePrice = event.averagePrice,
            fixExecId = event.execId,
        )
        fillRepository.save(fill)

        val newStatus = if (isFull) OrderStatus.FILLED else OrderStatus.PARTIAL
        orderRepository.updateStatus(order.orderId, newStatus)
        logger.info(
            "Fill processed: orderId={}, fillQty={}, fillPrice={}, newStatus={}",
            order.orderId, event.lastQty, event.lastPrice, newStatus,
        )

        // Trigger trade booking so the position and P&L are updated
        val tradeCommand = BookTradeCommand(
            tradeId = TradeId(fillId),
            bookId = BookId(order.bookId),
            instrumentId = InstrumentId(order.instrumentId),
            assetClass = order.assetClass,
            side = order.side,
            quantity = event.lastQty,
            price = Money(event.lastPrice, order.currency),
            tradedAt = fill.fillTime,
            correlationId = order.orderId,
        )
        tradeBookingService.handle(tradeCommand)

        if (isFull) {
            val allFills = existingFills + fill
            val filledOrder = order.copy(fills = allFills)
            val costAnalysis = executionCostService.compute(filledOrder, fill.fillTime)
            executionCostRepository.save(costAnalysis)
            logger.info(
                "Execution cost persisted: orderId={}, slippageBps={}",
                order.orderId, costAnalysis.metrics.slippageBps,
            )
        }
    }

    // --- Ghost-fill detection (ADR-0035 phase 2) ---

    private suspend fun recordGhostFill(order: Order, event: FIXInboundFillEvent) {
        val now = clock()
        val ghostFill = GhostFill(
            orderId = order.orderId,
            priorStatus = order.status,
            venue = event.venue ?: "UNKNOWN",
            fixExecId = event.execId,
            fillQty = event.lastQty,
            fillPrice = event.lastPrice,
            cumulativeQty = event.cumulativeQty,
            detectedAt = now,
            rawEvent = event.toString(),
        )
        ghostFillRepository.save(ghostFill)

        riskBreakPublisher.publish(
            RiskBreakEvent(
                eventId = UUID.randomUUID().toString(),
                breakType = "GHOST_FILL",
                severity = "CRITICAL",
                message = "Fill received against ${order.status} order — manual ops resolution required. " +
                    "Position has NOT been auto-updated.",
                occurredAt = now.toString(),
                orderId = order.orderId,
                bookId = order.bookId,
                instrumentId = order.instrumentId,
                venue = event.venue,
                attributes = mapOf(
                    "priorStatus" to order.status.name,
                    "fixExecId" to event.execId,
                    "fillQty" to event.lastQty.toPlainString(),
                    "fillPrice" to event.lastPrice.toPlainString(),
                ),
            )
        )

        logger.error(
            "GHOST FILL detected: orderId={} priorStatus={} venue={} execId={} qty={} price={}",
            order.orderId, order.status, event.venue, event.execId, event.lastQty, event.lastPrice,
        )
    }

    /** Default no-op repo so existing callers don't break before V24 + ExposedGhostFillRepository wire-up. */
    private object NoOpGhostFillRepository : GhostFillRepository {
        override suspend fun save(fill: GhostFill) = Unit
        override suspend fun findByOrderId(orderId: String): List<GhostFill> = emptyList()
    }

    // --- Cancel (ExecType = 4) ---

    private suspend fun processCancel(event: FIXInboundFillEvent) {
        val order = orderRepository.findById(event.orderId)
        if (order == null) {
            logger.error("Cancel arrived for unknown order: orderId={}", event.orderId)
            return
        }

        if (order.status !in setOf(OrderStatus.SENT, OrderStatus.PARTIAL)) {
            logger.warn(
                "Cannot cancel order in status {}: orderId={}",
                order.status, order.orderId,
            )
            return
        }

        orderRepository.updateStatus(order.orderId, OrderStatus.CANCELLED)
        logger.info("Order cancelled via FIX: orderId={}", order.orderId)
    }

    // --- Replace (ExecType = 5) ---

    private suspend fun processReplace(event: FIXInboundFillEvent) {
        val order = orderRepository.findById(event.orderId)
        if (order == null) {
            logger.error("Replace arrived for unknown order: orderId={}", event.orderId)
            return
        }

        if (order.isTerminal) {
            logger.warn(
                "Cannot replace terminal order: orderId={}, status={}",
                order.orderId, order.status,
            )
            return
        }

        // For Replace the event carries the new order quantity in cumulativeQty (what has been
        // accepted) and the new limit price in averagePrice — but these fields are repurposed
        // here from the FIXInboundFillEvent model which was designed for fills. The processor
        // uses lastQty as the new total quantity and lastPrice as the new limit price when
        // non-zero, which is the convention used by FIXMessageConverter for Replace messages.
        val newQty = if (event.lastQty.signum() > 0) event.lastQty else order.quantity
        val newLimitPrice = if (event.lastPrice.signum() > 0) event.lastPrice else order.limitPrice

        // Guard: reject replace if new quantity is less than already-filled amount.
        // Prevents a malicious replace from reducing quantity below cumulative fills,
        // which would create phantom P&L and break position accounting.
        val existingFills = fillRepository.findByOrderId(order.orderId)
        val filledQty = existingFills.fold(BigDecimal.ZERO) { acc, fill -> acc + fill.fillQty }
        if (newQty < filledQty) {
            logger.error(
                "Rejecting replace: newQty {} < filledQty {} for orderId={}",
                newQty, filledQty, order.orderId,
            )
            return
        }

        orderRepository.updateQuantityAndPrice(order.orderId, newQty, newLimitPrice)
        logger.info(
            "Order replaced via FIX: orderId={}, newQty={}, newLimitPrice={}",
            order.orderId, newQty, newLimitPrice,
        )
    }
}

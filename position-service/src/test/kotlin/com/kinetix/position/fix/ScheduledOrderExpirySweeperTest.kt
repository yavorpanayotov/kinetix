package com.kinetix.position.fix

import com.kinetix.common.execution.CancelReason
import com.kinetix.common.execution.OrderCancelEmitter
import com.kinetix.common.execution.VenueOpenChecker
import com.kinetix.common.model.Side
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Test fixture: minimal in-process VenueOpenChecker that captures the policy
 * the sweeper used to read directly off VenueCutoffRegistry. Limited to the
 * launch venues (NYSE, NASDAQ, LSE, TSE, HKEX); production routes through
 * GrpcVenueOpenChecker against fix-gateway's IsVenueOpen RPC.
 */
private class ClockBackedVenueOpenChecker : VenueOpenChecker {
    private val zones: Map<String, Pair<ZoneId, Pair<LocalTime, LocalTime>>> = mapOf(
        "NYSE"   to (ZoneId.of("America/New_York") to (LocalTime.of(9, 30) to LocalTime.of(16, 0))),
        "NASDAQ" to (ZoneId.of("America/New_York") to (LocalTime.of(9, 30) to LocalTime.of(16, 0))),
        "LSE"    to (ZoneId.of("Europe/London")    to (LocalTime.of(8, 0)  to LocalTime.of(16, 30))),
        "TSE"    to (ZoneId.of("Asia/Tokyo")       to (LocalTime.of(9, 0)  to LocalTime.of(15, 0))),
        "HKEX"   to (ZoneId.of("Asia/Hong_Kong")   to (LocalTime.of(9, 30) to LocalTime.of(16, 0))),
    )

    override fun isOpen(venue: String, at: Instant): Boolean {
        val entry = zones[venue.uppercase()] ?: zones["NYSE"]!!
        val (zone, window) = entry
        val zdt: ZonedDateTime = ZonedDateTime.ofInstant(at, zone)
        if (zdt.dayOfWeek == DayOfWeek.SATURDAY || zdt.dayOfWeek == DayOfWeek.SUNDAY) return false
        val tod = zdt.toLocalTime()
        return !tod.isBefore(window.first) && tod.isBefore(window.second)
    }
}

class ScheduledOrderExpirySweeperTest : FunSpec({

    fun order(
        orderId: String = "ord-1",
        timeInForce: TimeInForce = TimeInForce.DAY,
        expiresAt: Instant? = null,
        status: OrderStatus = OrderStatus.SENT,
        venueOrderId: String? = null,
    ) = Order(
        orderId = orderId,
        bookId = "book-1",
        instrumentId = "AAPL",
        side = Side.BUY,
        quantity = BigDecimal("100"),
        orderType = "MARKET",
        limitPrice = null,
        arrivalPrice = BigDecimal("150.00"),
        submittedAt = Instant.parse("2026-05-04T13:30:00Z"), // 09:30 ET
        status = status,
        riskCheckResult = "APPROVED",
        riskCheckDetails = null,
        fixSessionId = null,
        timeInForce = timeInForce,
        expiresAt = expiresAt,
        instrumentType = "CASH_EQUITY",
        venueOrderId = venueOrderId,
    )

    /** 16:30 ET on 2026-05-04 (a Monday) — past NYSE 16:00 cutoff. */
    val pastNyseCutoff: Clock = Clock.fixed(
        Instant.parse("2026-05-04T20:30:00Z"),
        ZoneOffset.UTC,
    )

    /** 14:00 ET on 2026-05-04 — well within NYSE session. */
    val duringNyseSession: Clock = Clock.fixed(
        Instant.parse("2026-05-04T18:00:00Z"),
        ZoneOffset.UTC,
    )

    test("expires DAY orders when venue session has closed") {
        val orderRepo = mockk<ExecutionOrderRepository>()
        val emitter = mockk<OrderCancelEmitter>(relaxed = true)
        val sweeper = ScheduledOrderExpirySweeper(
            orderRepository = orderRepo,
            venueOpenChecker = ClockBackedVenueOpenChecker(),
            cancelEmitter = emitter,
            clock = pastNyseCutoff,
        )

        coEvery { orderRepo.findOpenDayAndGtdOrders() } returns listOf(order())
        coEvery { orderRepo.updateStatus(any(), any(), any(), any()) } returns Unit

        val expired = sweeper.sweepOnce()

        expired shouldBe 1
        coVerify(exactly = 1) {
            emitter.emitCancel(
                orderId = "ord-1",
                venue = any(),
                venueOrderId = null,
                reason = CancelReason.DAY_ORDER_EXPIRY,
                correlationId = any(),
            )
        }
        coVerify(exactly = 1) { orderRepo.updateStatus("ord-1", OrderStatus.EXPIRED, "DAY_ORDER_EXPIRY", any()) }
    }

    test("does not expire DAY orders during the venue session") {
        val orderRepo = mockk<ExecutionOrderRepository>()
        val emitter = mockk<OrderCancelEmitter>(relaxed = true)
        val sweeper = ScheduledOrderExpirySweeper(
            orderRepository = orderRepo,
            venueOpenChecker = ClockBackedVenueOpenChecker(),
            cancelEmitter = emitter,
            clock = duringNyseSession,
        )

        coEvery { orderRepo.findOpenDayAndGtdOrders() } returns listOf(order())

        val expired = sweeper.sweepOnce()

        expired shouldBe 0
        coVerify(exactly = 0) { emitter.emitCancel(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { orderRepo.updateStatus(any(), OrderStatus.EXPIRED, any(), any()) }
    }

    test("expires GTD orders past their expiresAt timestamp") {
        val now = Instant.parse("2026-05-04T18:00:00Z")
        val expiredYesterday = now.minusSeconds(86400)
        val orderRepo = mockk<ExecutionOrderRepository>()
        val emitter = mockk<OrderCancelEmitter>(relaxed = true)
        val sweeper = ScheduledOrderExpirySweeper(
            orderRepository = orderRepo,
            venueOpenChecker = ClockBackedVenueOpenChecker(),
            cancelEmitter = emitter,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        coEvery { orderRepo.findOpenDayAndGtdOrders() } returns listOf(
            order(orderId = "gtd-1", timeInForce = TimeInForce.GTD, expiresAt = expiredYesterday),
        )
        coEvery { orderRepo.updateStatus(any(), any(), any(), any()) } returns Unit

        val expired = sweeper.sweepOnce()

        expired shouldBe 1
        coVerify(exactly = 1) {
            emitter.emitCancel(
                orderId = "gtd-1",
                venue = any(),
                venueOrderId = null,
                reason = CancelReason.GTD_EXPIRY,
                correlationId = any(),
            )
        }
        coVerify(exactly = 1) { orderRepo.updateStatus("gtd-1", OrderStatus.EXPIRED, "GTD_EXPIRY", any()) }
    }

    test("does not expire GTD orders that haven't reached their expiresAt yet") {
        val now = Instant.parse("2026-05-04T18:00:00Z")
        val tomorrow = now.plusSeconds(86400)
        val orderRepo = mockk<ExecutionOrderRepository>()
        val emitter = mockk<OrderCancelEmitter>(relaxed = true)
        val sweeper = ScheduledOrderExpirySweeper(
            orderRepository = orderRepo,
            venueOpenChecker = ClockBackedVenueOpenChecker(),
            cancelEmitter = emitter,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        coEvery { orderRepo.findOpenDayAndGtdOrders() } returns listOf(
            order(orderId = "gtd-1", timeInForce = TimeInForce.GTD, expiresAt = tomorrow),
        )

        sweeper.sweepOnce() shouldBe 0
        coVerify(exactly = 0) { orderRepo.updateStatus(any(), OrderStatus.EXPIRED, any(), any()) }
    }

    test("propagates the order's venueOrderId to the cancel emitter") {
        // ADR-0035 phase 4: once OrderSubmissionService persists the venue's FIX 37 OrderID
        // on PENDING_NEW, the sweeper must hand it to fix-gateway for outbound 35=F cancels
        // (most venues require tag 37 to identify the order).
        val orderRepo = mockk<ExecutionOrderRepository>()
        val emitter = mockk<OrderCancelEmitter>(relaxed = true)
        val sweeper = ScheduledOrderExpirySweeper(
            orderRepository = orderRepo,
            venueOpenChecker = ClockBackedVenueOpenChecker(),
            cancelEmitter = emitter,
            clock = pastNyseCutoff,
        )

        coEvery { orderRepo.findOpenDayAndGtdOrders() } returns listOf(order(venueOrderId = "NYSE-12345"))
        coEvery { orderRepo.updateStatus(any(), any(), any(), any()) } returns Unit

        sweeper.sweepOnce() shouldBe 1

        coVerify(exactly = 1) {
            emitter.emitCancel(
                orderId = "ord-1",
                venue = any(),
                venueOrderId = "NYSE-12345",
                reason = CancelReason.DAY_ORDER_EXPIRY,
                correlationId = any(),
            )
        }
    }

    test("proceeds with state transition when the cancel emitter throws") {
        // Venue connectivity failure must not block the state-side expiry — venue-side
        // reconciliation flows asynchronously via execution reports.
        val orderRepo = mockk<ExecutionOrderRepository>()
        val emitter = mockk<OrderCancelEmitter>()
        val sweeper = ScheduledOrderExpirySweeper(
            orderRepository = orderRepo,
            venueOpenChecker = ClockBackedVenueOpenChecker(),
            cancelEmitter = emitter,
            clock = pastNyseCutoff,
        )

        coEvery { orderRepo.findOpenDayAndGtdOrders() } returns listOf(order())
        coEvery { emitter.emitCancel(any(), any(), any(), any(), any()) } throws RuntimeException("fix-gateway unavailable")
        coEvery { orderRepo.updateStatus(any(), any(), any(), any()) } returns Unit

        sweeper.sweepOnce() shouldBe 1

        coVerify(exactly = 1) { orderRepo.updateStatus("ord-1", OrderStatus.EXPIRED, "DAY_ORDER_EXPIRY", any()) }
    }

    test("processes a mix of expirable and not-expirable orders in one pass") {
        val now = Instant.parse("2026-05-04T20:30:00Z") // past NYSE cutoff
        val orderRepo = mockk<ExecutionOrderRepository>()
        val emitter = mockk<OrderCancelEmitter>(relaxed = true)
        val sweeper = ScheduledOrderExpirySweeper(
            orderRepository = orderRepo,
            venueOpenChecker = ClockBackedVenueOpenChecker(),
            cancelEmitter = emitter,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        coEvery { orderRepo.findOpenDayAndGtdOrders() } returns listOf(
            order(orderId = "day-1", timeInForce = TimeInForce.DAY),  // expires
            order(orderId = "gtd-future", timeInForce = TimeInForce.GTD, expiresAt = now.plusSeconds(7200)),  // stays
            order(orderId = "gtd-past", timeInForce = TimeInForce.GTD, expiresAt = now.minusSeconds(60)),  // expires
        )
        coEvery { orderRepo.updateStatus(any(), any(), any(), any()) } returns Unit

        sweeper.sweepOnce() shouldBe 2

        coVerify(exactly = 1) { orderRepo.updateStatus("day-1", OrderStatus.EXPIRED, "DAY_ORDER_EXPIRY", any()) }
        coVerify(exactly = 1) { orderRepo.updateStatus("gtd-past", OrderStatus.EXPIRED, "GTD_EXPIRY", any()) }
        coVerify(exactly = 0) { orderRepo.updateStatus("gtd-future", any(), any(), any()) }
    }

    test("returns zero when there are no candidate orders") {
        val orderRepo = mockk<ExecutionOrderRepository>()
        val emitter = mockk<OrderCancelEmitter>()
        val sweeper = ScheduledOrderExpirySweeper(
            orderRepository = orderRepo,
            venueOpenChecker = ClockBackedVenueOpenChecker(),
            cancelEmitter = emitter,
            clock = pastNyseCutoff,
        )

        coEvery { orderRepo.findOpenDayAndGtdOrders() } returns emptyList()

        sweeper.sweepOnce() shouldBe 0
        coVerify(exactly = 0) { emitter.emitCancel(any(), any(), any(), any(), any()) }
    }

    test("uses venueResolver to look up the right cutoff for non-NYSE orders") {
        val now = Instant.parse("2026-05-04T15:35:00Z") // 16:35 BST — past LSE cutoff
        val orderRepo = mockk<ExecutionOrderRepository>()
        val emitter = mockk<OrderCancelEmitter>(relaxed = true)
        val sweeper = ScheduledOrderExpirySweeper(
            orderRepository = orderRepo,
            venueOpenChecker = ClockBackedVenueOpenChecker(),
            cancelEmitter = emitter,
            // Resolve every order to LSE for this test.
            venueResolver = { _ -> "LSE" },
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        coEvery { orderRepo.findOpenDayAndGtdOrders() } returns listOf(order(orderId = "lse-1"))
        coEvery { orderRepo.updateStatus(any(), any(), any(), any()) } returns Unit

        sweeper.sweepOnce() shouldBe 1
    }
})

package com.kinetix.position.fix

import com.kinetix.common.model.Side
import com.kinetix.position.model.LimitBreach
import com.kinetix.position.model.LimitBreachResult
import com.kinetix.position.model.LimitBreachSeverity
import com.kinetix.position.service.PreTradeCheckService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.mockk.*
import java.math.BigDecimal
import java.time.Instant

class OrderSubmissionServiceTest : FunSpec({

    val orderRepository = mockk<ExecutionOrderRepository>()
    val sessionRepository = mockk<FIXSessionRepository>()
    val fixOrderSender = mockk<FIXOrderSender>()
    val preTradeCheckService = mockk<PreTradeCheckService>()

    val service = OrderSubmissionService(
        orderRepository = orderRepository,
        sessionRepository = sessionRepository,
        fixOrderSender = fixOrderSender,
        preTradeCheckService = preTradeCheckService,
    )

    beforeEach {
        clearMocks(orderRepository, sessionRepository, fixOrderSender, preTradeCheckService)
        coEvery { orderRepository.save(any()) } just runs
        coEvery { orderRepository.updateStatus(any(), any(), any(), any()) } just runs
        coEvery { fixOrderSender.send(any(), any()) } just runs
        coEvery { preTradeCheckService.check(any()) } returns LimitBreachResult(emptyList())
    }

    test("saves order and returns it with APPROVED status when no FIX session is provided") {
        val order = service.submit(
            bookId = "book-1",
            instrumentId = "AAPL",
            side = Side.BUY,
            quantity = BigDecimal("100"),
            orderType = "LIMIT",
            limitPrice = BigDecimal("150.00"),
            arrivalPrice = BigDecimal("149.90"),
            fixSessionId = null,
            instrumentType = "CASH_EQUITY",
        )

        order.shouldNotBeNull()
        order.bookId shouldBe "book-1"
        order.instrumentId shouldBe "AAPL"
        order.side shouldBe Side.BUY
        order.quantity.compareTo(BigDecimal("100")) shouldBe 0
        order.status shouldBe OrderStatus.APPROVED

        coVerify(exactly = 1) { orderRepository.save(any()) }
        coVerify(exactly = 1) { orderRepository.updateStatus(any(), OrderStatus.APPROVED, "APPROVED", null) }
        coVerify(exactly = 0) { fixOrderSender.send(any(), any()) }
    }

    test("dispatches order via FIX and returns SENT status when session is connected") {
        val session = FIXSession(
            sessionId = "FIX-01",
            counterparty = "BROKER",
            status = FIXSessionStatus.CONNECTED,
            lastMessageAt = Instant.now(),
            inboundSeqNum = 10,
            outboundSeqNum = 8,
        )
        coEvery { sessionRepository.findById("FIX-01") } returns session

        val order = service.submit(
            bookId = "book-1",
            instrumentId = "AAPL",
            side = Side.BUY,
            quantity = BigDecimal("200"),
            orderType = "MARKET",
            limitPrice = null,
            arrivalPrice = BigDecimal("149.80"),
            fixSessionId = "FIX-01",
            instrumentType = "CASH_EQUITY",
        )

        order.status shouldBe OrderStatus.SENT
        coVerify(exactly = 1) { fixOrderSender.send(match { it.status == OrderStatus.APPROVED }, session) }
        coVerify(exactly = 1) { orderRepository.updateStatus(any(), OrderStatus.SENT) }
    }

    test("remains APPROVED without FIX dispatch when session is disconnected") {
        val session = FIXSession(
            sessionId = "FIX-02",
            counterparty = "BROKER",
            status = FIXSessionStatus.DISCONNECTED,
            lastMessageAt = null,
            inboundSeqNum = 0,
            outboundSeqNum = 0,
        )
        coEvery { sessionRepository.findById("FIX-02") } returns session

        val order = service.submit(
            bookId = "book-1",
            instrumentId = "AAPL",
            side = Side.SELL,
            quantity = BigDecimal("50"),
            orderType = "LIMIT",
            limitPrice = BigDecimal("160.00"),
            arrivalPrice = BigDecimal("159.90"),
            fixSessionId = "FIX-02",
            instrumentType = "CASH_EQUITY",
        )

        order.status shouldBe OrderStatus.APPROVED
        coVerify(exactly = 0) { fixOrderSender.send(any(), any()) }
    }

    test("remains APPROVED without FIX dispatch when session is not found") {
        coEvery { sessionRepository.findById("MISSING") } returns null

        val order = service.submit(
            bookId = "book-1",
            instrumentId = "AAPL",
            side = Side.BUY,
            quantity = BigDecimal("10"),
            orderType = "MARKET",
            limitPrice = null,
            arrivalPrice = BigDecimal("150.00"),
            fixSessionId = "MISSING",
            instrumentType = "CASH_EQUITY",
        )

        order.status shouldBe OrderStatus.APPROVED
        coVerify(exactly = 0) { fixOrderSender.send(any(), any()) }
    }

    test("rejects zero quantity with IllegalArgumentException") {
        shouldThrow<IllegalArgumentException> {
            service.submit(
                bookId = "book-1",
                instrumentId = "AAPL",
                side = Side.BUY,
                quantity = BigDecimal.ZERO,
                orderType = "MARKET",
                limitPrice = null,
                arrivalPrice = BigDecimal("150.00"),
                fixSessionId = null,
                instrumentType = "CASH_EQUITY",
            )
        }

        coVerify(exactly = 0) { orderRepository.save(any()) }
    }

    test("rejects order when arrival price timestamp is older than 30 seconds") {
        val staleTimestamp = Instant.now().minusSeconds(31)

        shouldThrow<IllegalArgumentException> {
            service.submit(
                bookId = "book-1",
                instrumentId = "AAPL",
                side = Side.BUY,
                quantity = BigDecimal("100"),
                orderType = "MARKET",
                limitPrice = null,
                arrivalPrice = BigDecimal("150.00"),
                fixSessionId = null,
                arrivalPriceTimestamp = staleTimestamp,
                instrumentType = "CASH_EQUITY",
            )
        }

        coVerify(exactly = 0) { orderRepository.save(any()) }
    }

    test("accepts order when arrival price timestamp is within 30 seconds") {
        val freshTimestamp = Instant.now().minusSeconds(10)

        val order = service.submit(
            bookId = "book-1",
            instrumentId = "AAPL",
            side = Side.BUY,
            quantity = BigDecimal("100"),
            orderType = "MARKET",
            limitPrice = null,
            arrivalPrice = BigDecimal("150.00"),
            fixSessionId = null,
            arrivalPriceTimestamp = freshTimestamp,
            instrumentType = "CASH_EQUITY",
        )

        order.status shouldBe OrderStatus.APPROVED
        coVerify(exactly = 1) { orderRepository.save(any()) }
    }

    test("skips arrival price staleness check when no timestamp is provided") {
        val order = service.submit(
            bookId = "book-1",
            instrumentId = "AAPL",
            side = Side.BUY,
            quantity = BigDecimal("100"),
            orderType = "MARKET",
            limitPrice = null,
            arrivalPrice = BigDecimal("150.00"),
            fixSessionId = null,
            arrivalPriceTimestamp = null,
            instrumentType = "CASH_EQUITY",
        )

        order.status shouldBe OrderStatus.APPROVED
        coVerify(exactly = 1) { orderRepository.save(any()) }
    }

    test("approves and dispatches when check passes with no breaches") {
        coEvery { preTradeCheckService.check(any()) } returns LimitBreachResult(emptyList())

        val order = service.submit(
            bookId = "book-1",
            instrumentId = "AAPL",
            side = Side.BUY,
            quantity = BigDecimal("100"),
            orderType = "MARKET",
            limitPrice = null,
            arrivalPrice = BigDecimal("150.00"),
            fixSessionId = null,
            instrumentType = "CASH_EQUITY",
        )

        order.status shouldBe OrderStatus.APPROVED
        order.riskCheckResult shouldBe "APPROVED"
        order.riskCheckDetails shouldBe null
        coVerify(exactly = 1) { orderRepository.updateStatus(any(), OrderStatus.APPROVED, "APPROVED", null) }
    }

    test("approves with FLAGGED when check returns soft-breach warnings") {
        val softBreach = LimitBreach(
            limitType = "CONCENTRATION",
            severity = LimitBreachSeverity.SOFT,
            currentValue = "0.22",
            limitValue = "0.25",
            message = "Approaching concentration limit",
        )
        coEvery { preTradeCheckService.check(any()) } returns LimitBreachResult(listOf(softBreach))

        val order = service.submit(
            bookId = "book-1",
            instrumentId = "AAPL",
            side = Side.BUY,
            quantity = BigDecimal("100"),
            orderType = "MARKET",
            limitPrice = null,
            arrivalPrice = BigDecimal("150.00"),
            fixSessionId = null,
            instrumentType = "CASH_EQUITY",
        )

        order.status shouldBe OrderStatus.APPROVED
        order.riskCheckResult shouldBe "FLAGGED"
        order.riskCheckDetails.shouldNotBeNull()
        order.riskCheckDetails!! shouldContain "CONCENTRATION"
        coVerify(exactly = 1) {
            orderRepository.updateStatus(any(), OrderStatus.APPROVED, "FLAGGED", match { it != null && it.contains("CONCENTRATION") })
        }
        coVerify(exactly = 0) { fixOrderSender.send(any(), any()) }
    }

    test("rejects and does not dispatch when check returns hard limit breach") {
        val hardBreach = LimitBreach(
            limitType = "POSITION",
            severity = LimitBreachSeverity.HARD,
            currentValue = "1100000",
            limitValue = "1000000",
            message = "Position limit exceeded",
        )
        coEvery { preTradeCheckService.check(any()) } returns LimitBreachResult(listOf(hardBreach))

        val order = service.submit(
            bookId = "book-1",
            instrumentId = "AAPL",
            side = Side.BUY,
            quantity = BigDecimal("100"),
            orderType = "MARKET",
            limitPrice = null,
            arrivalPrice = BigDecimal("150.00"),
            fixSessionId = null,
            instrumentType = "CASH_EQUITY",
        )

        order.status shouldBe OrderStatus.REJECTED
        order.riskCheckResult shouldBe "REJECTED"
        order.riskCheckDetails.shouldNotBeNull()
        order.riskCheckDetails!! shouldContain "POSITION"
        coVerify(exactly = 1) {
            orderRepository.updateStatus(any(), OrderStatus.REJECTED, "REJECTED", match { it != null && it.contains("POSITION") })
        }
        coVerify(exactly = 0) { fixOrderSender.send(any(), any()) }
    }

    test("rejects and updates status when check times out") {
        val service = OrderSubmissionService(
            orderRepository = orderRepository,
            sessionRepository = sessionRepository,
            fixOrderSender = fixOrderSender,
            preTradeCheckService = preTradeCheckService,
            riskCheckTimeoutMs = 50L,
        )
        coEvery { preTradeCheckService.check(any()) } coAnswers {
            kotlinx.coroutines.delay(500)
            LimitBreachResult(emptyList())
        }

        val order = service.submit(
            bookId = "book-1",
            instrumentId = "AAPL",
            side = Side.BUY,
            quantity = BigDecimal("100"),
            orderType = "MARKET",
            limitPrice = null,
            arrivalPrice = BigDecimal("150.00"),
            fixSessionId = null,
            instrumentType = "CASH_EQUITY",
        )

        order.status shouldBe OrderStatus.REJECTED
        order.riskCheckResult shouldBe "TIMEOUT"
        coVerify(exactly = 1) { orderRepository.updateStatus(any(), OrderStatus.REJECTED, "TIMEOUT", null) }
        coVerify(exactly = 0) { fixOrderSender.send(any(), any()) }
    }

    test("populates riskCheckDetails with breach info on rejection") {
        val hardBreach = LimitBreach(
            limitType = "NOTIONAL",
            severity = LimitBreachSeverity.HARD,
            currentValue = "11000000",
            limitValue = "10000000",
            message = "Notional limit exceeded",
        )
        coEvery { preTradeCheckService.check(any()) } returns LimitBreachResult(listOf(hardBreach))

        val order = service.submit(
            bookId = "book-1",
            instrumentId = "AAPL",
            side = Side.BUY,
            quantity = BigDecimal("100"),
            orderType = "MARKET",
            limitPrice = null,
            arrivalPrice = BigDecimal("150.00"),
            fixSessionId = null,
            instrumentType = "CASH_EQUITY",
        )

        order.riskCheckDetails.shouldNotBeNull()
        order.riskCheckDetails!! shouldContain "NOTIONAL"
        order.riskCheckDetails!! shouldContain "Notional limit exceeded"
        order.riskCheckDetails!! shouldContain "HARD"
    }

    test("stores asset class and currency from submission on the returned order") {
        val order = service.submit(
            bookId = "book-1",
            instrumentId = "BOND-001",
            side = Side.BUY,
            quantity = BigDecimal("500000"),
            orderType = "LIMIT",
            limitPrice = BigDecimal("99.50"),
            arrivalPrice = BigDecimal("99.45"),
            fixSessionId = null,
            assetClass = "FIXED_INCOME",
            currency = "EUR",
            instrumentType = "CASH_EQUITY",
        )

        order.assetClass shouldBe com.kinetix.common.model.AssetClass.FIXED_INCOME
        order.currency shouldBe java.util.Currency.getInstance("EUR")
    }

    // ------------------------------------------------------------
    // Time in force / GTD (audit A-13, ADR-0035)
    // ------------------------------------------------------------

    test("defaults to DAY when timeInForce is not provided") {
        val order = service.submit(
            bookId = "book-1",
            instrumentId = "AAPL",
            side = Side.BUY,
            quantity = BigDecimal("100"),
            orderType = "MARKET",
            limitPrice = null,
            arrivalPrice = BigDecimal("150.00"),
            fixSessionId = null,
            instrumentType = "CASH_EQUITY",
        )

        order.timeInForce shouldBe TimeInForce.DAY
        order.expiresAt shouldBe null
    }

    test("accepts an explicit GTC time-in-force") {
        val order = service.submit(
            bookId = "book-1",
            instrumentId = "AAPL",
            side = Side.BUY,
            quantity = BigDecimal("100"),
            orderType = "MARKET",
            limitPrice = null,
            arrivalPrice = BigDecimal("150.00"),
            fixSessionId = null,
            timeInForce = TimeInForce.GTC,
            instrumentType = "CASH_EQUITY",
        )

        order.timeInForce shouldBe TimeInForce.GTC
        order.expiresAt shouldBe null
    }

    test("GTD requires expiresAt to be set") {
        shouldThrow<IllegalArgumentException> {
            service.submit(
                bookId = "book-1",
                instrumentId = "AAPL",
                side = Side.BUY,
                quantity = BigDecimal("100"),
                orderType = "MARKET",
                limitPrice = null,
                arrivalPrice = BigDecimal("150.00"),
                fixSessionId = null,
                timeInForce = TimeInForce.GTD,
                expiresAt = null,
                instrumentType = "CASH_EQUITY",
            )
        }.message!! shouldContain "expiresAt is required"
    }

    test("GTD rejects an expiresAt in the past") {
        shouldThrow<IllegalArgumentException> {
            service.submit(
                bookId = "book-1",
                instrumentId = "AAPL",
                side = Side.BUY,
                quantity = BigDecimal("100"),
                orderType = "MARKET",
                limitPrice = null,
                arrivalPrice = BigDecimal("150.00"),
                fixSessionId = null,
                timeInForce = TimeInForce.GTD,
                expiresAt = Instant.now().minusSeconds(60),
                instrumentType = "CASH_EQUITY",
            )
        }.message!! shouldContain "must be in the future"
    }

    test("GTD rejects an expiresAt beyond the venue's max-GTD horizon") {
        shouldThrow<IllegalArgumentException> {
            service.submit(
                bookId = "book-1",
                instrumentId = "AAPL",
                side = Side.BUY,
                quantity = BigDecimal("100"),
                orderType = "MARKET",
                limitPrice = null,
                arrivalPrice = BigDecimal("150.00"),
                fixSessionId = null,
                timeInForce = TimeInForce.GTD,
                expiresAt = Instant.now().plusSeconds(100L * 24 * 3600),
                instrumentType = "CASH_EQUITY",
            )
        }.message!! shouldContain "max-GTD horizon"
    }

    test("non-GTD orders reject any expiresAt") {
        shouldThrow<IllegalArgumentException> {
            service.submit(
                bookId = "book-1",
                instrumentId = "AAPL",
                side = Side.BUY,
                quantity = BigDecimal("100"),
                orderType = "MARKET",
                limitPrice = null,
                arrivalPrice = BigDecimal("150.00"),
                fixSessionId = null,
                timeInForce = TimeInForce.DAY,
                expiresAt = Instant.now().plusSeconds(3600),
                instrumentType = "CASH_EQUITY",
            )
        }.message!! shouldContain "expiresAt must be null"
    }

    test("GTD with a future expiresAt within horizon is accepted and stored on the order") {
        val expires = Instant.now().plusSeconds(7L * 24 * 3600)
        val order = service.submit(
            bookId = "book-1",
            instrumentId = "AAPL",
            side = Side.BUY,
            quantity = BigDecimal("100"),
            orderType = "MARKET",
            limitPrice = null,
            arrivalPrice = BigDecimal("150.00"),
            fixSessionId = null,
            timeInForce = TimeInForce.GTD,
            expiresAt = expires,
            instrumentType = "CASH_EQUITY",
        )

        order.timeInForce shouldBe TimeInForce.GTD
        order.expiresAt shouldBe expires
    }
})

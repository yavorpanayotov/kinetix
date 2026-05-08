package com.kinetix.position.fix

import com.kinetix.common.model.Side
import com.kinetix.position.persistence.DatabaseTestSetup
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.math.BigDecimal
import java.time.Instant

class ExecutionRepositoryIntegrationTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val orderRepo = ExposedExecutionOrderRepository(db)
    val fillRepo = ExposedExecutionFillRepository(db)
    val costRepo = ExposedExecutionCostRepository(db)
    val reconRepo = ExposedPrimeBrokerReconciliationRepository(db)
    val sessionRepo = ExposedFIXSessionRepository(db)

    beforeEach {
        newSuspendedTransaction(db = db) {
            exec("TRUNCATE TABLE execution_fills, execution_cost_analysis, prime_broker_reconciliation, fix_sessions, execution_orders RESTART IDENTITY CASCADE")
        }
    }

    // ------- Order repository -------

    test("saves and retrieves an order by ID") {
        val order = makeOrder("ord-1", "book-1", OrderStatus.PENDING_RISK_CHECK)
        orderRepo.save(order)
        val found = orderRepo.findById("ord-1")
        found.shouldNotBeNull()
        found.orderId shouldBe "ord-1"
        found.bookId shouldBe "book-1"
        found.status shouldBe OrderStatus.PENDING_RISK_CHECK
        found.side shouldBe Side.BUY
    }

    test("findById returns null for unknown order") {
        orderRepo.findById("nonexistent").shouldBeNull()
    }

    test("updates order status") {
        orderRepo.save(makeOrder("ord-2", "book-1", OrderStatus.PENDING_RISK_CHECK))
        orderRepo.updateStatus("ord-2", OrderStatus.APPROVED, "APPROVED", null)
        val found = orderRepo.findById("ord-2")
        found.shouldNotBeNull()
        found.status shouldBe OrderStatus.APPROVED
        found.riskCheckResult shouldBe "APPROVED"
    }

    test("findByBookId returns all orders for a book") {
        orderRepo.save(makeOrder("ord-3a", "book-2", OrderStatus.FILLED))
        orderRepo.save(makeOrder("ord-3b", "book-2", OrderStatus.CANCELLED))
        orderRepo.save(makeOrder("ord-3c", "book-3", OrderStatus.FILLED))
        val orders = orderRepo.findByBookId("book-2")
        orders shouldHaveSize 2
    }

    // ------- Fill repository -------

    test("saves and retrieves fills by order ID") {
        orderRepo.save(makeOrder("ord-4", "book-1", OrderStatus.PARTIAL))
        val fill = makeFill("fill-1", "ord-4", BigDecimal("50"), BigDecimal("150.00"), "exec-001")
        fillRepo.save(fill)
        val fills = fillRepo.findByOrderId("ord-4")
        fills shouldHaveSize 1
        fills[0].fillId shouldBe "fill-1"
        fills[0].fillQty.compareTo(BigDecimal("50")) shouldBe 0
    }

    test("existsByFixExecId returns true when fill exists") {
        orderRepo.save(makeOrder("ord-5", "book-1", OrderStatus.FILLED))
        fillRepo.save(makeFill("fill-2", "ord-5", BigDecimal("100"), BigDecimal("149.95"), "exec-002"))
        fillRepo.existsByFixExecId("exec-002") shouldBe true
    }

    test("existsByFixExecId returns false when fill does not exist") {
        fillRepo.existsByFixExecId("exec-does-not-exist") shouldBe false
    }

    test("fill deduplication: unique constraint on fix_exec_id exists in schema") {
        // Verify that the UNIQUE constraint on fix_exec_id exists at the schema level.
        // We cannot test the actual constraint violation inside newSuspendedTransaction
        // because Exposed + Kotest's shouldThrow cannot catch exceptions thrown inside
        // suspended transactions (known gotcha — see CLAUDE.md), and the resulting
        // aborted transaction state corrupts the connection pool for subsequent tests.
        newSuspendedTransaction(db = db) {
            val constraints = exec(
                """SELECT constraint_name FROM information_schema.table_constraints
                   WHERE table_name = 'execution_fills'
                   AND constraint_type = 'UNIQUE'"""
            ) { rs ->
                val names = mutableListOf<String>()
                while (rs.next()) names.add(rs.getString("constraint_name"))
                names
            }
            constraints.shouldNotBeNull()
            constraints.any { it.contains("exec_id", ignoreCase = true) } shouldBe true
        }
    }

    // ------- Execution cost repository -------

    test("saves and retrieves execution cost analysis by order ID") {
        orderRepo.save(makeOrder("ord-7", "book-1", OrderStatus.FILLED))
        val analysis = ExecutionCostAnalysis(
            orderId = "ord-7",
            bookId = "book-1",
            instrumentId = "AAPL",
            completedAt = Instant.parse("2026-03-24T15:00:00Z"),
            arrivalPrice = BigDecimal("150.00"),
            averageFillPrice = BigDecimal("150.15"),
            side = Side.BUY,
            totalQty = BigDecimal("100"),
            metrics = ExecutionCostMetrics(
                slippageBps = BigDecimal("10.0000000000"),
                marketImpactBps = null,
                timingCostBps = null,
                totalCostBps = BigDecimal("10.0000000000"),
            ),
        )
        costRepo.save(analysis)
        val found = costRepo.findByOrderId("ord-7")
        found.shouldNotBeNull()
        found.orderId shouldBe "ord-7"
        found.metrics.slippageBps.compareTo(BigDecimal("10.0000000000")) shouldBe 0
        found.metrics.marketImpactBps.shouldBeNull()
    }

    test("findByBookId returns all cost analyses for a book") {
        orderRepo.save(makeOrder("ord-8a", "book-4", OrderStatus.FILLED))
        orderRepo.save(makeOrder("ord-8b", "book-4", OrderStatus.FILLED))
        costRepo.save(makeCostAnalysis("ord-8a", "book-4"))
        costRepo.save(makeCostAnalysis("ord-8b", "book-4"))
        val analyses = costRepo.findByBookId("book-4")
        analyses shouldHaveSize 2
    }

    // ------- Reconciliation repository -------

    test("saves and retrieves prime broker reconciliation") {
        val recon = PrimeBrokerReconciliation(
            reconciliationDate = "2026-03-24",
            bookId = "book-5",
            status = "BREAKS_FOUND",
            totalPositions = 3,
            matchedCount = 2,
            breakCount = 1,
            breaks = listOf(
                ReconciliationBreak(
                    instrumentId = "AAPL",
                    internalQty = BigDecimal("105"),
                    primeBrokerQty = BigDecimal("100"),
                    breakQty = BigDecimal("5"),
                    breakNotional = BigDecimal("750.00"),
                    severity = ReconciliationBreakSeverity.NORMAL,
                )
            ),
            reconciledAt = Instant.parse("2026-03-24T18:00:00Z"),
        )
        reconRepo.save(recon, "recon-1")
        val found = reconRepo.findLatestByBookId("book-5")
        found.shouldNotBeNull()
        found.status shouldBe "BREAKS_FOUND"
        found.breakCount shouldBe 1
        found.breaks shouldHaveSize 1
        found.breaks[0].instrumentId shouldBe "AAPL"
        found.breaks[0].breakQty.compareTo(BigDecimal("5")) shouldBe 0
    }

    // ------- FIX session repository -------

    test("saves and retrieves a FIX session") {
        val session = FIXSession(
            sessionId = "FIX-SESSION-1",
            counterparty = "BROKER-A",
            status = FIXSessionStatus.CONNECTED,
            lastMessageAt = Instant.parse("2026-03-24T10:00:00Z"),
            inboundSeqNum = 42,
            outboundSeqNum = 38,
        )
        sessionRepo.save(session)
        val found = sessionRepo.findById("FIX-SESSION-1")
        found.shouldNotBeNull()
        found.status shouldBe FIXSessionStatus.CONNECTED
        found.inboundSeqNum shouldBe 42
    }

    test("updates FIX session status to disconnected") {
        sessionRepo.save(
            FIXSession(
                sessionId = "FIX-SESSION-2",
                counterparty = "BROKER-B",
                status = FIXSessionStatus.CONNECTED,
                lastMessageAt = null,
                inboundSeqNum = 0,
                outboundSeqNum = 0,
            )
        )
        sessionRepo.updateStatus("FIX-SESSION-2", FIXSessionStatus.DISCONNECTED)
        val found = sessionRepo.findById("FIX-SESSION-2")
        found.shouldNotBeNull()
        found.status shouldBe FIXSessionStatus.DISCONNECTED
    }
})

private fun makeOrder(orderId: String, bookId: String, status: OrderStatus) = Order(
    orderId = orderId,
    bookId = bookId,
    instrumentId = "AAPL",
    side = Side.BUY,
    quantity = BigDecimal("100"),
    orderType = "LIMIT",
    limitPrice = BigDecimal("150.00"),
    arrivalPrice = BigDecimal("149.80"),
    submittedAt = Instant.parse("2026-03-24T10:00:00Z"),
    status = status,
    riskCheckResult = null,
    riskCheckDetails = null,
    fixSessionId = "SESSION-1",
    instrumentType = "CASH_EQUITY",
)

private fun makeFill(
    fillId: String,
    orderId: String,
    qty: BigDecimal,
    price: BigDecimal,
    fixExecId: String,
) = ExecutionFill(
    fillId = fillId,
    orderId = orderId,
    bookId = "book-1",
    instrumentId = "AAPL",
    fillTime = Instant.parse("2026-03-24T10:05:00Z"),
    fillQty = qty,
    fillPrice = price,
    fillType = FillType.PARTIAL,
    venue = "NYSE",
    cumulativeQty = qty,
    averagePrice = price,
    fixExecId = fixExecId,
)

private fun makeCostAnalysis(orderId: String, bookId: String) = ExecutionCostAnalysis(
    orderId = orderId,
    bookId = bookId,
    instrumentId = "AAPL",
    completedAt = Instant.parse("2026-03-24T15:00:00Z"),
    arrivalPrice = BigDecimal("150.00"),
    averageFillPrice = BigDecimal("150.10"),
    side = Side.BUY,
    totalQty = BigDecimal("100"),
    metrics = ExecutionCostMetrics(
        slippageBps = BigDecimal("6.6666666667"),
        marketImpactBps = null,
        timingCostBps = null,
        totalCostBps = BigDecimal("6.6666666667"),
    ),
)

package com.kinetix.position

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Position
import com.kinetix.common.model.Side
import com.kinetix.position.fix.ExecutionCostAnalysis
import com.kinetix.position.fix.ExecutionCostMetrics
import com.kinetix.position.fix.ExposedExecutionCostRepository
import com.kinetix.position.fix.ExposedPrimeBrokerReconciliationRepository
import com.kinetix.position.fix.PrimeBrokerReconciliation
import com.kinetix.position.fix.PrimeBrokerReconciliationService
import com.kinetix.position.fix.ReconciliationBreak
import com.kinetix.position.fix.ReconciliationBreakSeverity
import com.kinetix.position.fix.ReconciliationBreakStatus
import com.kinetix.position.persistence.DatabaseTestSetup
import com.kinetix.position.persistence.ExposedPositionRepository
import com.kinetix.position.routes.executionRoutes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.request.patch
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency as JCurrency

@Serializable
private data class ExecutionErrorBody(val error: String, val message: String)

private fun Application.configureTestApp(
    costRepo: ExposedExecutionCostRepository,
    reconRepo: ExposedPrimeBrokerReconciliationRepository,
    reconService: PrimeBrokerReconciliationService,
    positionRepo: ExposedPositionRepository,
) {
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ExecutionErrorBody("bad_request", cause.message ?: ""))
        }
    }
    routing {
        executionRoutes(costRepo, reconRepo, reconService, positionRepo)
    }
}

class ExecutionRoutesAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val costRepo = ExposedExecutionCostRepository(db)
    val reconRepo = ExposedPrimeBrokerReconciliationRepository(db)
    val reconService = PrimeBrokerReconciliationService()
    val positionRepo = ExposedPositionRepository(db)

    beforeEach {
        newSuspendedTransaction(db = db) {
            exec("TRUNCATE TABLE prime_broker_reconciliation, execution_cost_analysis, positions RESTART IDENTITY CASCADE")
        }
    }

    test("GET /api/v1/execution/cost/{bookId} returns list of cost analyses") {
        val analysis = ExecutionCostAnalysis(
            orderId = "ord-1",
            bookId = "book-1",
            instrumentId = "AAPL",
            completedAt = Instant.parse("2026-03-24T15:00:00Z"),
            arrivalPrice = BigDecimal("150.00"),
            averageFillPrice = BigDecimal("150.15"),
            side = Side.BUY,
            totalQty = BigDecimal("100"),
            metrics = ExecutionCostMetrics(
                slippageBps = BigDecimal("10.00"),
                marketImpactBps = null,
                timingCostBps = null,
                totalCostBps = BigDecimal("10.00"),
            ),
        )
        costRepo.save(analysis)

        testApplication {
            application { configureTestApp(costRepo, reconRepo, reconService, positionRepo) }
            val response = client.get("/api/v1/execution/cost/book-1")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            body.size shouldBe 1
            body[0].jsonObject["orderId"]!!.jsonPrimitive.content shouldBe "ord-1"
            // slippageBps is stored as decimal(20,10); toPlainString() preserves full scale
            body[0].jsonObject["slippageBps"]!!.jsonPrimitive.content.toBigDecimal().toDouble() shouldBe 10.0
        }
    }

    test("GET /api/v1/execution/cost/{bookId} returns empty list when no analyses exist") {
        testApplication {
            application { configureTestApp(costRepo, reconRepo, reconService, positionRepo) }
            val response = client.get("/api/v1/execution/cost/book-empty")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            body.size shouldBe 0
        }
    }

    test("GET /api/v1/execution/reconciliation/{bookId} returns reconciliation history") {
        val recon = PrimeBrokerReconciliation(
            reconciliationDate = "2026-03-24",
            bookId = "book-2",
            status = "CLEAN",
            totalPositions = 5,
            matchedCount = 5,
            breakCount = 0,
            breaks = emptyList(),
            reconciledAt = Instant.parse("2026-03-24T18:00:00Z"),
        )
        reconRepo.save(recon, "recon-book-2")

        testApplication {
            application { configureTestApp(costRepo, reconRepo, reconService, positionRepo) }
            val response = client.get("/api/v1/execution/reconciliation/book-2")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            body.size shouldBe 1
            body[0].jsonObject["status"]!!.jsonPrimitive.content shouldBe "CLEAN"
            body[0].jsonObject["breakCount"]!!.jsonPrimitive.content shouldBe "0"
        }
    }

    test("POST /api/v1/execution/reconciliation/{bookId}/statements returns reconciliation result") {
        positionRepo.save(
            Position(
                bookId = BookId("book-3"),
                instrumentId = InstrumentId("AAPL"),
                assetClass = AssetClass.EQUITY,
                quantity = BigDecimal("100"),
                averageCost = Money(BigDecimal("150.00"), JCurrency.getInstance("USD")),
                marketPrice = Money(BigDecimal("155.00"), JCurrency.getInstance("USD")),
                instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
            )
        )

        testApplication {
            application { configureTestApp(costRepo, reconRepo, reconService, positionRepo) }
            val response = client.post("/api/v1/execution/reconciliation/book-3/statements") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                        "bookId": "book-3",
                        "date": "2026-03-24",
                        "positions": [
                            {"instrumentId": "AAPL", "quantity": "100", "price": "155.00"}
                        ]
                    }
                    """.trimIndent()
                )
            }
            response.status shouldBe HttpStatusCode.Created
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["status"]!!.jsonPrimitive.content shouldBe "CLEAN"
            body["matchedCount"]!!.jsonPrimitive.content shouldBe "1"
            body["breakCount"]!!.jsonPrimitive.content shouldBe "0"
        }
    }

    // EXEC-04: break status update endpoint
    test("PATCH /api/v1/execution/reconciliation-breaks/{id}/{instrument}/status updates break status") {
        val recon = PrimeBrokerReconciliation(
            reconciliationDate = "2026-03-24",
            bookId = "book-patch",
            status = "BREAKS_FOUND",
            totalPositions = 1,
            matchedCount = 0,
            breakCount = 1,
            breaks = listOf(
                ReconciliationBreak(
                    instrumentId = "AAPL",
                    internalQty = BigDecimal("105"),
                    primeBrokerQty = BigDecimal("100"),
                    breakQty = BigDecimal("5"),
                    breakNotional = BigDecimal("775.00"),
                    severity = ReconciliationBreakSeverity.NORMAL,
                    status = ReconciliationBreakStatus.OPEN,
                )
            ),
            reconciledAt = Instant.parse("2026-03-24T18:00:00Z"),
        )
        reconRepo.save(recon, "recon-1")

        testApplication {
            application { configureTestApp(costRepo, reconRepo, reconService, positionRepo) }
            val response = client.patch("/api/v1/execution/reconciliation-breaks/recon-1/AAPL/status") {
                contentType(ContentType.Application.Json)
                setBody("""{"status": "INVESTIGATING"}""")
            }
            response.status shouldBe HttpStatusCode.NoContent
        }

        val updated = reconRepo.findById("recon-1")!!
        updated.breaks[0].status shouldBe ReconciliationBreakStatus.INVESTIGATING
    }

    test("PATCH break status with invalid status returns 400") {
        testApplication {
            application { configureTestApp(costRepo, reconRepo, reconService, positionRepo) }
            val response = client.patch("/api/v1/execution/reconciliation-breaks/recon-1/AAPL/status") {
                contentType(ContentType.Application.Json)
                setBody("""{"status": "INVALID_STATUS"}""")
            }
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("POST statement with book mismatch returns 400") {
        testApplication {
            application { configureTestApp(costRepo, reconRepo, reconService, positionRepo) }
            val response = client.post("/api/v1/execution/reconciliation/book-4/statements") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                        "bookId": "DIFFERENT-BOOK",
                        "date": "2026-03-24",
                        "positions": []
                    }
                    """.trimIndent()
                )
            }
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("POST statement detects material break when PB position differs by more than 1 unit") {
        positionRepo.save(
            Position(
                bookId = BookId("book-5"),
                instrumentId = InstrumentId("AAPL"),
                assetClass = AssetClass.EQUITY,
                quantity = BigDecimal("105"),
                averageCost = Money(BigDecimal("150.00"), JCurrency.getInstance("USD")),
                marketPrice = Money(BigDecimal("155.00"), JCurrency.getInstance("USD")),
                instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
            )
        )

        testApplication {
            application { configureTestApp(costRepo, reconRepo, reconService, positionRepo) }
            val response = client.post("/api/v1/execution/reconciliation/book-5/statements") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                        "bookId": "book-5",
                        "date": "2026-03-24",
                        "positions": [
                            {"instrumentId": "AAPL", "quantity": "100", "price": "155.00"}
                        ]
                    }
                    """.trimIndent()
                )
            }
            response.status shouldBe HttpStatusCode.Created
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["status"]!!.jsonPrimitive.content shouldBe "BREAKS_FOUND"
            body["breakCount"]!!.jsonPrimitive.content shouldBe "1"
            response.bodyAsText() shouldContain "AAPL"
        }
    }
})

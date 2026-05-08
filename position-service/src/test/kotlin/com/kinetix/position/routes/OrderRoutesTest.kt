package com.kinetix.position.routes

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.Side
import com.kinetix.position.fix.GhostFill
import com.kinetix.position.fix.GhostFillRepository
import com.kinetix.position.fix.Order
import com.kinetix.position.fix.OrderStatus
import com.kinetix.position.fix.OrderSubmissionService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency
import java.util.UUID

@Serializable
private data class TestErrorBody(val error: String, val message: String)

private fun fakeOrder(): Order = Order(
    orderId = UUID.randomUUID().toString(),
    bookId = "book-1",
    instrumentId = "AAPL",
    side = Side.BUY,
    quantity = BigDecimal("100"),
    orderType = "LIMIT",
    limitPrice = BigDecimal("150.00"),
    arrivalPrice = BigDecimal("149.90"),
    submittedAt = Instant.parse("2026-04-30T12:00:00Z"),
    status = OrderStatus.APPROVED,
    riskCheckResult = "APPROVED",
    riskCheckDetails = null,
    fixSessionId = null,
    assetClass = AssetClass.EQUITY,
    currency = Currency.getInstance("USD"),
    instrumentType = "CASH_EQUITY",
)

private object EmptyGhostFillRepository : GhostFillRepository {
    override suspend fun save(fill: GhostFill) = Unit
    override suspend fun findByOrderId(orderId: String): List<GhostFill> = emptyList()
}

private fun Application.testOrderRouteModule(
    service: OrderSubmissionService,
    ghostFillRepository: GhostFillRepository = EmptyGhostFillRepository,
) {
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                TestErrorBody("bad_request", cause.message ?: "Invalid request"),
            )
        }
    }
    routing { orderRoutes(service, ghostFillRepository) }
}

class OrderRoutesTest : FunSpec({

    test("POST /api/v1/orders forwards arrivalPriceTimestamp through to OrderSubmissionService") {
        val service = mockk<OrderSubmissionService>()
        val captured = slot<Instant?>()
        coEvery {
            service.submit(
                bookId = any(),
                instrumentId = any(),
                side = any(),
                quantity = any(),
                orderType = any(),
                limitPrice = any(),
                arrivalPrice = any(),
                fixSessionId = any(),
                assetClass = any(),
                currency = any(),
                arrivalPriceTimestamp = captureNullable(captured),
                instrumentType = "CASH_EQUITY",
            )
        } returns fakeOrder()

        testApplication {
            application { testOrderRouteModule(service) }
            val response = client.post("/api/v1/orders") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {"bookId":"book-1","instrumentId":"AAPL","side":"BUY","quantity":"100",
                     "orderType":"LIMIT","limitPrice":"150.00","arrivalPrice":"149.90",
                     "arrivalPriceTimestamp":"2026-04-30T11:59:55Z","instrumentType":"CASH_EQUITY"}
                    """.trimIndent(),
                )
            }
            response.status shouldBe HttpStatusCode.Created
        }

        captured.captured shouldBe Instant.parse("2026-04-30T11:59:55Z")
    }

    test("POST /api/v1/orders without arrivalPriceTimestamp passes null to OrderSubmissionService") {
        val service = mockk<OrderSubmissionService>()
        val captured = slot<Instant?>()
        coEvery {
            service.submit(
                bookId = any(),
                instrumentId = any(),
                side = any(),
                quantity = any(),
                orderType = any(),
                limitPrice = any(),
                arrivalPrice = any(),
                fixSessionId = any(),
                assetClass = any(),
                currency = any(),
                arrivalPriceTimestamp = captureNullable(captured),
                instrumentType = "CASH_EQUITY",
            )
        } returns fakeOrder()

        testApplication {
            application { testOrderRouteModule(service) }
            val response = client.post("/api/v1/orders") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {"bookId":"book-1","instrumentId":"AAPL","side":"BUY","quantity":"100",
                     "orderType":"LIMIT","limitPrice":"150.00","arrivalPrice":"149.90","instrumentType":"CASH_EQUITY"}
                    """.trimIndent(),
                )
            }
            response.status shouldBe HttpStatusCode.Created
        }

        captured.captured shouldBe null
    }

    test("POST /api/v1/orders rejects an unparseable arrivalPriceTimestamp with 400") {
        val service = mockk<OrderSubmissionService>(relaxed = true)

        testApplication {
            application { testOrderRouteModule(service) }
            val response = client.post("/api/v1/orders") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {"bookId":"book-1","instrumentId":"AAPL","side":"BUY","quantity":"100",
                     "orderType":"LIMIT","limitPrice":"150.00","arrivalPrice":"149.90",
                     "arrivalPriceTimestamp":"not-a-timestamp","instrumentType":"CASH_EQUITY"}
                    """.trimIndent(),
                )
            }
            response.status shouldBe HttpStatusCode.BadRequest
            response.bodyAsText().contains("arrivalPriceTimestamp") shouldBe true
        }

        coVerify(exactly = 0) {
            service.submit(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), instrumentType = any())
        }
    }

    test("POST /api/v1/orders surfaces rejectReason from the order's riskCheckResult so the UI can distinguish PENDING_FAILED variants") {
        val service = mockk<OrderSubmissionService>()
        val rejectedOrder = fakeOrder().copy(
            status = OrderStatus.PENDING_FAILED,
            riskCheckResult = "DUPLICATE_IN_FLIGHT",
            riskCheckDetails = "DUPLICATE_IN_FLIGHT: original RPC still in flight",
        )
        coEvery {
            service.submit(
                bookId = any(),
                instrumentId = any(),
                side = any(),
                quantity = any(),
                orderType = any(),
                limitPrice = any(),
                arrivalPrice = any(),
                fixSessionId = any(),
                assetClass = any(),
                currency = any(),
                arrivalPriceTimestamp = any(),
                instrumentType = "CASH_EQUITY",
            )
        } returns rejectedOrder

        testApplication {
            application { testOrderRouteModule(service) }
            val response = client.post("/api/v1/orders") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {"bookId":"book-1","instrumentId":"AAPL","side":"BUY","quantity":"100",
                     "orderType":"LIMIT","limitPrice":"150.00","arrivalPrice":"149.90",
                     "instrumentType":"CASH_EQUITY"}
                    """.trimIndent(),
                )
            }
            response.status shouldBe HttpStatusCode.Created
            val body = response.bodyAsText()
            body.contains("\"status\":\"PENDING_FAILED\"") shouldBe true
            body.contains("\"rejectReason\":\"DUPLICATE_IN_FLIGHT\"") shouldBe true
        }
    }

    test("POST /api/v1/orders surfaces a stale arrival-price rejection from the service as 400") {
        val service = mockk<OrderSubmissionService>()
        coEvery {
            service.submit(
                bookId = any(),
                instrumentId = any(),
                side = any(),
                quantity = any(),
                orderType = any(),
                limitPrice = any(),
                arrivalPrice = any(),
                fixSessionId = any(),
                assetClass = any(),
                currency = any(),
                arrivalPriceTimestamp = any(),
                instrumentType = "CASH_EQUITY",
            )
        } throws IllegalArgumentException("Arrival price is stale: observed 45000ms ago, limit is 30000ms")

        testApplication {
            application { testOrderRouteModule(service) }
            val response = client.post("/api/v1/orders") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {"bookId":"book-1","instrumentId":"AAPL","side":"BUY","quantity":"100",
                     "orderType":"LIMIT","limitPrice":"150.00","arrivalPrice":"149.90",
                     "arrivalPriceTimestamp":"2026-04-30T11:59:00Z","instrumentType":"CASH_EQUITY"}
                    """.trimIndent(),
                )
            }
            response.status shouldBe HttpStatusCode.BadRequest
            response.bodyAsText().contains("stale") shouldBe true
        }
    }
})

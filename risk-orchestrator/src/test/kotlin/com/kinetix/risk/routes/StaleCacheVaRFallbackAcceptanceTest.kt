package com.kinetix.risk.routes

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.dtos.ApiError
import com.kinetix.common.resilience.CircuitBreakerOpenException
import com.kinetix.risk.cache.RedisTestSetup
import com.kinetix.risk.cache.RedisVaRCache
import com.kinetix.risk.cache.VaRCache
import com.kinetix.risk.model.CalculationType
import com.kinetix.risk.model.ComponentBreakdown
import com.kinetix.risk.model.ConfidenceLevel
import com.kinetix.risk.model.ValuationOutput
import com.kinetix.risk.model.ValuationResult
import com.kinetix.risk.service.VaRCalculationService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/**
 * Acceptance tests for stale-cache VaR fallback.
 *
 * When the circuit breaker is open during a VaR POST, the route should:
 * - return the last cached result with stale=true and Warning: 199 header, if available
 * - return 503 if no cached result exists
 */
class StaleCacheVaRFallbackAcceptanceTest : FunSpec({

    val varCalculationService = mockk<VaRCalculationService>()
    val redisConnection = RedisTestSetup.start()
    val varCache: VaRCache = RedisVaRCache(redisConnection)

    beforeEach {
        clearMocks(varCalculationService)
        redisConnection.sync().flushdb()
    }

    val cachedResult = ValuationResult(
        bookId = BookId("port-1"),
        calculationType = CalculationType.PARAMETRIC,
        confidenceLevel = ConfidenceLevel.CL_95,
        varValue = 50000.0,
        expectedShortfall = 65000.0,
        componentBreakdown = listOf(
            ComponentBreakdown(AssetClass.EQUITY, 50000.0, 100.0),
        ),
        greeks = null,
        calculatedAt = Instant.parse("2026-03-24T09:00:00Z"),
        computedOutputs = setOf(ValuationOutput.VAR),
    )

    fun testApp(
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) {
        testApplication {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(StatusPages) {
                exception<CircuitBreakerOpenException> { call, _ ->
                    call.response.header("Retry-After", "30")
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ApiError(code = "SERVICE_UNAVAILABLE", message = "Risk engine temporarily unavailable"),
                    )
                }
            }
            routing {
                riskRoutes(
                    varCalculationService = varCalculationService,
                    varCache = varCache,
                    positionProvider = mockk(),
                    stressTestStub = mockk(),
                    regulatoryStub = mockk(),
                )
            }
            block()
        }
    }

    test("returns stale cached VaR with Warning header when circuit breaker is open and cache is populated") {
        varCache.put("port-1", cachedResult)

        coEvery { varCalculationService.calculateVaR(any(), any(), any(), any(), any()) } throws
            CircuitBreakerOpenException("risk-engine")

        testApp {
            val response = client.post("/api/v1/risk/var/port-1") {
                contentType(ContentType.Application.Json)
                setBody("""{"calculationType":"PARAMETRIC","confidenceLevel":"CL_95"}""")
            }

            response.status shouldBe HttpStatusCode.OK
            response.headers["Warning"] shouldNotBe null
            response.headers["Warning"] shouldBe """199 - "Stale data""""

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["bookId"]?.jsonPrimitive?.content shouldBe "port-1"
            body["stale"]?.jsonPrimitive?.boolean shouldBe true
        }
    }

    test("returns 503 with Retry-After when circuit breaker is open and cache is empty") {
        coEvery { varCalculationService.calculateVaR(any(), any(), any(), any(), any()) } throws
            CircuitBreakerOpenException("risk-engine")

        testApp {
            val response = client.post("/api/v1/risk/var/port-1") {
                contentType(ContentType.Application.Json)
                setBody("""{"calculationType":"PARAMETRIC","confidenceLevel":"CL_95"}""")
            }

            response.status shouldBe HttpStatusCode.ServiceUnavailable
            response.headers["Retry-After"] shouldBe "30"
        }
    }

    test("returns fresh VaR without Warning header when circuit breaker is closed") {
        coEvery { varCalculationService.calculateVaR(any(), any(), any(), any(), any()) } returns cachedResult

        testApp {
            val response = client.post("/api/v1/risk/var/port-1") {
                contentType(ContentType.Application.Json)
                setBody("""{"calculationType":"PARAMETRIC","confidenceLevel":"CL_95"}""")
            }

            response.status shouldBe HttpStatusCode.OK
            response.headers["Warning"] shouldBe null
        }
    }
})

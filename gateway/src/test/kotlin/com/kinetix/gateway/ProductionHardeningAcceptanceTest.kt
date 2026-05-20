package com.kinetix.gateway

import com.kinetix.common.persistence.ConnectionPoolConfig
import com.kinetix.common.security.Role
import com.kinetix.gateway.auth.InMemoryBookAccessService
import com.kinetix.gateway.auth.TestJwtHelper
import com.kinetix.gateway.client.HttpPositionServiceClient
import com.kinetix.gateway.client.HttpRiskServiceClient
import com.kinetix.gateway.ratelimit.RateLimit
import com.kinetix.gateway.ratelimit.RateLimiterConfig
import com.kinetix.gateway.ratelimit.TokenBucketRateLimiter
import com.kinetix.gateway.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import java.io.File

private fun testJwtConfig() = TestJwtHelper.testJwtConfig()
private fun testJwkProvider() = TestJwtHelper.testJwkProvider()
private fun generateToken(roles: List<Role>): String = TestJwtHelper.generateToken(roles = roles)

/**
 * Resolves the monorepo root by walking up from the test process working
 * directory until a directory containing `settings.gradle.kts` is found.
 * Gradle launches the `:gateway` test JVM with `user.dir` set to the
 * `gateway/` module directory, so the root is one or more levels above.
 */
private fun repoRoot(): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
        if (File(dir, "settings.gradle.kts").isFile) return dir
        dir = dir.parentFile
    }
    error("Could not locate the monorepo root (no settings.gradle.kts found above ${System.getProperty("user.dir")})")
}

/** The banned verification-hedge phrase — see plan ai-v2.md "Out of scope". */
private const val BANNED_VERIFICATION_HEDGE = "verify with your team"

private val bookTradeResponseJson = """
    {
      "trade":{
        "tradeId":"t-acc-1","bookId":"port-1","instrumentId":"AAPL","assetClass":"EQUITY",
        "side":"BUY","quantity":"100","price":{"amount":"150.00","currency":"USD"},
        "tradedAt":"2025-01-15T10:00:00Z","instrumentType":"CASH_EQUITY"
      },
      "position":{
        "bookId":"port-1","instrumentId":"AAPL","assetClass":"EQUITY","quantity":"100",
        "averageCost":{"amount":"150.00","currency":"USD"},
        "marketPrice":{"amount":"155.00","currency":"USD"},
        "marketValue":{"amount":"15500.00","currency":"USD"},
        "unrealizedPnl":{"amount":"500.00","currency":"USD"},
        "instrumentType":"CASH_EQUITY"
      }
    }
""".trimIndent()

private val frtbResultJson = """
    {
      "bookId":"port-1","sbmCharges":[],"totalSbmCharge":"100000.0","grossJtd":"50000.0",
      "hedgeBenefit":"10000.0","netDrc":"40000.0","exoticNotional":"5000.0",
      "otherNotional":"3000.0","totalRrao":"8000.0","totalCapitalCharge":"148000.0",
      "calculatedAt":"2025-01-15T10:00:00Z"
    }
""".trimIndent()

class ProductionHardeningAcceptanceTest : FunSpec({

    val jwtConfig = testJwtConfig()
    val jwkProvider = testJwkProvider()

    test("JWT authentication configured with RBAC roles — a TRADER requests to book a trade — authorized — TRADER has WRITE_TRADES permission") {
        val backend = BackendStubServer {
            post("/api/v1/books/port-1/trades") {
                call.respondText(bookTradeResponseJson, ContentType.Application.Json, HttpStatusCode.Created)
            }
        }
        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        try {
            val positionClient = HttpPositionServiceClient(httpClient, backend.baseUrl)
            val token = generateToken(listOf(Role.TRADER))

            testApplication {
                application {
                    module(
                        jwtConfig,
                        positionClient = positionClient,
                        bookAccessService = InMemoryBookAccessService(traderBooks = mapOf("user-1" to setOf("port-1"))),
                        jwkProvider = jwkProvider,
                    )
                }
                val response = client.post("/api/v1/books/port-1/trades") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"tradeId":"t-acc-1","instrumentId":"AAPL","assetClass":"EQUITY","side":"BUY","quantity":"100","priceAmount":"150.00","priceCurrency":"USD","tradedAt":"2025-01-15T10:00:00Z","instrumentType":"CASH_EQUITY"}""")
                }
                response.status shouldBe HttpStatusCode.Created
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("JWT authentication configured with RBAC roles — a VIEWER requests to book a trade — denied — VIEWER lacks WRITE_TRADES permission") {
        val backend = BackendStubServer { }
        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        try {
            val positionClient = HttpPositionServiceClient(httpClient, backend.baseUrl)
            val token = generateToken(listOf(Role.VIEWER))

            testApplication {
                application { module(jwtConfig, positionClient = positionClient, jwkProvider = jwkProvider) }
                val response = client.post("/api/v1/books/port-1/trades") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"tradeId":"t-acc-2","instrumentId":"AAPL","assetClass":"EQUITY","side":"BUY","quantity":"100","priceAmount":"150.00","priceCurrency":"USD","tradedAt":"2025-01-15T10:00:00Z","instrumentType":"CASH_EQUITY"}""")
                }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("JWT authentication configured with RBAC roles — a COMPLIANCE user requests regulatory reports — authorized — COMPLIANCE has GENERATE_REPORTS permission") {
        val backend = BackendStubServer {
            post("/api/v1/regulatory/frtb/port-1") {
                call.respondText(frtbResultJson, ContentType.Application.Json, HttpStatusCode.OK)
            }
        }
        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)
            val token = generateToken(listOf(Role.COMPLIANCE))

            testApplication {
                application { module(jwtConfig, riskClient = riskClient, jwkProvider = jwkProvider) }
                val response = client.post("/api/v1/regulatory/frtb/port-1") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
                response.status shouldBe HttpStatusCode.OK
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("rate limiter configured on gateway — requests within limit — all accepted") {
        testApplication {
            application {
                module()
                install(RateLimit) {
                    rateLimiter = TokenBucketRateLimiter(RateLimiterConfig(requestsPerSecond = 100, burstSize = 10))
                    excludedPaths = setOf("/health", "/metrics")
                }
                routing {
                    get("/api/test") { call.respondText("ok") }
                }
            }
            repeat(5) {
                client.get("/api/test").status shouldBe HttpStatusCode.OK
            }
        }
    }

    test("rate limiter configured on gateway — requests exceed limit — excess rejected") {
        testApplication {
            application {
                module()
                install(RateLimit) {
                    rateLimiter = TokenBucketRateLimiter(RateLimiterConfig(requestsPerSecond = 10, burstSize = 2))
                    excludedPaths = setOf("/health", "/metrics")
                }
                routing {
                    get("/api/test") { call.respondText("ok") }
                }
            }
            repeat(2) { client.get("/api/test") }
            client.get("/api/test").status shouldBe HttpStatusCode.TooManyRequests
        }
    }

    test("connection pool tuned for services — config created for position-service — has 15 max connections and 3 min idle") {
        val config = ConnectionPoolConfig.forService("position-service")
        config.maxPoolSize shouldBe 15
        config.minIdle shouldBe 3
    }

    test("ai-insights-service runs in demo mode under test — the test-profile compose override sets DEMO_MODE=true so CI never reaches the live Claude SDK") {
        val composeOverride = File(repoRoot(), "docker-compose.test.yml")
        composeOverride.isFile shouldBe true

        val text = composeOverride.readText()
        // The override must declare the ai-insights-service container and
        // pin DEMO_MODE to "true" — without it CI would default to
        // ${DEMO_MODE:-false} from docker-compose.services.yml and attempt
        // live LLM calls, making test runs slow, flaky, and non-deterministic.
        text shouldContain "ai-insights-service:"

        val aiSection = text.substringAfter("ai-insights-service:")
        aiSection shouldContain "DEMO_MODE"
        val demoModeAssignment = aiSection
            .lineSequence()
            .map { it.trim() }
            .first { it.startsWith("DEMO_MODE") }
        demoModeAssignment.replace(" ", "") shouldBe """DEMO_MODE:"true""""
    }

    test("no verification-hedge copy under ai-insights-service/src — banned-phrase scrub finds the phrase in no canned fixture or fallback") {
        val srcDir = File(repoRoot(), "ai-insights-service/src")
        srcDir.isDirectory shouldBe true

        val offenders = srcDir.walkTopDown()
            .filter { it.isFile }
            .filter { file ->
                runCatching { file.readText().contains(BANNED_VERIFICATION_HEDGE) }
                    .getOrDefault(false)
            }
            .map { it.relativeTo(repoRoot()).path }
            .toList()

        // "verify with your team" is verification-hedge copy explicitly
        // ruled out by the plan — citations are the trust anchor. No canned
        // fixture or fallback narrative may contain it.
        offenders.shouldBeEmpty()
    }
})

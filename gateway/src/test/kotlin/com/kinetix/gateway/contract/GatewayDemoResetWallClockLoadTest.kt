package com.kinetix.gateway.contract

import com.kinetix.gateway.routes.demoAdminRoutes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Tag

/**
 * Pins the wall-clock SLO for `POST /api/v1/admin/demo-reset` from
 * docs/plans/demo-review.md (Phase 1 NFR):
 *
 *   "Demo reset wall-clock duration (full firm scenario) < 5 minutes p95"
 *
 * The gateway fans out to 8 services sequentially (DemoAdminRoutes.kt). The
 * actual seed cost lives in each per-service /demo-reset handler — this test
 * does not stand the services up. Instead it stubs each backend with a
 * configurable delay (LOAD_TEST_PER_BACKEND_DELAY_MS, default 10ms; the
 * nightly soak job overrides) and measures the gateway's wall-clock.
 *
 * Two assertions:
 *   1. wall-clock < 5 minutes (the plan's hard SLO). Always passes with the
 *      default tiny delays — the assertion exists as a hard ceiling that
 *      catches catastrophic regressions and documents the budget.
 *   2. wall-clock <  ceiling derived from the configured per-backend delay.
 *      Catches subtler regressions: a gateway that introduces a 30s pause
 *      between fan-out calls would still pass (1) but blow up (2).
 *
 * Tagged @Tag("load") and named *LoadTest so it's filtered out of the
 * default :test / :acceptanceTest / :integrationTest runs (per
 * build-logic/.../kinetix.kotlin-testing.gradle.kts) and only fires via
 * :gateway:loadTest.
 */
private const val WALL_CLOCK_BUDGET_MS = 5L * 60L * 1000L

private fun Application.configureDemoAdmin(mockEngine: MockEngine) {
    val client = HttpClient(mockEngine) {
        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json() }
    }
    install(ContentNegotiation) { json() }
    routing {
        demoAdminRoutes(
            httpClient = client,
            positionUrl = "http://position",
            auditUrl = "http://audit",
            riskUrl = "http://risk",
            priceUrl = "http://price",
            ratesUrl = "http://rates",
            volatilityUrl = "http://volatility",
            correlationUrl = "http://correlation",
            referenceDataUrl = "http://reference-data",
            adminKey = "admin-key",
            resetToken = "reset-token",
        )
    }
}

@Tag("load")
class GatewayDemoResetWallClockLoadTest : FunSpec({

    val perBackendDelayMs = System.getenv("LOAD_TEST_PER_BACKEND_DELAY_MS")?.toLongOrNull() ?: 10L
    // Eight backends, fanned out sequentially → expected fan-out ≈ 8 × delay.
    // Allow a 4× overhead headroom on top of that for JIT, scheduler, and the
    // mock engine's own bookkeeping. Even with that envelope, it's tight enough
    // to catch a regression that introduces a noticeable pause per call.
    val regressionCeilingMs = (8L * perBackendDelayMs * 4L).coerceAtLeast(500L)

    test(
        "POST /api/v1/admin/demo-reset wall-clock < 5min hard SLO and < ${regressionCeilingMs}ms regression ceiling " +
            "(per-backend delay = ${perBackendDelayMs}ms)",
    ) {
        val mockEngine = MockEngine { _ ->
            delay(perBackendDelayMs)
            respond(
                content = """{"status":"ok"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        testApplication {
            application { configureDemoAdmin(mockEngine) }

            val start = System.currentTimeMillis()
            val response = client.post("/api/v1/admin/demo-reset") {
                header("X-Demo-Admin-Key", "admin-key")
            }
            val wallClockMs = System.currentTimeMillis() - start

            response.status shouldBe HttpStatusCode.OK
            wallClockMs shouldBeLessThan WALL_CLOCK_BUDGET_MS
            wallClockMs shouldBeLessThan regressionCeilingMs
        }
    }
})

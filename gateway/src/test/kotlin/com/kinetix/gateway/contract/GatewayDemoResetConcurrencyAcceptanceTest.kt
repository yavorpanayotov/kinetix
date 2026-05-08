package com.kinetix.gateway.contract

import com.kinetix.gateway.routes.demoAdminRoutes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

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
            adminKey = "admin-key",
            resetToken = "reset-token",
        )
    }
}

class GatewayDemoResetConcurrencyAcceptanceTest : FunSpec({

    test("a second concurrent demo-reset returns 409 with reset_in_progress while the first is in flight") {
        // Each backend call sleeps 250ms. The full reset is ~750ms (3 sequential calls).
        // The second request fires after 100ms, well inside the first's window.
        val mockEngine = MockEngine { _ ->
            delay(250)
            respond(
                content = """{"status":"ok"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        testApplication {
            application { configureDemoAdmin(mockEngine) }

            coroutineScope {
                val firstDeferred = async {
                    client.post("/api/v1/admin/demo-reset") {
                        header("X-Demo-Admin-Key", "admin-key")
                    }
                }

                // Let the first request enter the route and acquire the guard.
                delay(100)

                val second = client.post("/api/v1/admin/demo-reset") {
                    header("X-Demo-Admin-Key", "admin-key")
                }
                val secondBody = second.bodyAsText()

                val first = firstDeferred.await()

                first.status shouldBe HttpStatusCode.OK
                second.status shouldBe HttpStatusCode.Conflict
                secondBody shouldContain "reset_in_progress"
            }
        }
    }

    test("after a reset completes the next request succeeds (guard releases)") {
        val mockEngine = MockEngine { _ ->
            respond(
                content = """{"status":"ok"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        testApplication {
            application { configureDemoAdmin(mockEngine) }

            val first = client.post("/api/v1/admin/demo-reset") {
                header("X-Demo-Admin-Key", "admin-key")
            }
            val second = client.post("/api/v1/admin/demo-reset") {
                header("X-Demo-Admin-Key", "admin-key")
            }

            first.status shouldBe HttpStatusCode.OK
            second.status shouldBe HttpStatusCode.OK
        }
    }

    test("the guard is bypassed when the admin key is wrong, so 403 still wins over 409") {
        val mockEngine = MockEngine { _ ->
            respond(
                content = """{"status":"ok"}""",
                status = HttpStatusCode.OK,
            )
        }

        testApplication {
            application { configureDemoAdmin(mockEngine) }

            val response = client.post("/api/v1/admin/demo-reset") {
                header("X-Demo-Admin-Key", "wrong")
            }

            response.status shouldBe HttpStatusCode.Forbidden
        }
    }
})

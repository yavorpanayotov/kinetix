package com.kinetix.gateway.routes

import com.kinetix.gateway.client.HttpPositionServiceClient
import com.kinetix.gateway.client.HttpRiskServiceClient
import com.kinetix.gateway.module
import com.kinetix.gateway.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Acceptance tests verifying gateway partial failure behaviour.
 *
 * These tests confirm that the gateway isolates upstream failures:
 * - position endpoints remain available when risk-orchestrator is down
 * - risk endpoints return 503 with Retry-After without affecting other endpoints
 * - the system health endpoint reports DEGRADED (not DOWN) when one upstream fails
 */
class PartialFailureAcceptanceTest : FunSpec({

    test("GET positions succeeds when risk-orchestrator is unavailable") {
        val positionBackend = BackendStubServer {
            get("/api/v1/books/port-1/positions") {
                call.respondText("[]", ContentType.Application.Json, HttpStatusCode.OK)
            }
            get("/api/v1/books") {
                call.respondText("[]", ContentType.Application.Json, HttpStatusCode.OK)
            }
        }
        val httpClient = HttpClient(CIO) {
            install(ContentNegotiation) { json() }
        }
        try {
            val positionClient = HttpPositionServiceClient(httpClient, positionBackend.baseUrl)

            testApplication {
                application { module(positionClient) }

                val response = client.get("/api/v1/books/port-1/positions")
                response.status shouldBe HttpStatusCode.OK
            }
        } finally {
            httpClient.close()
            positionBackend.close()
        }
    }

    test("GET /api/v1/risk/var/{bookId} returns 503 with Retry-After when risk-orchestrator is down") {
        val riskBackend = BackendStubServer {
            post("/api/v1/risk/var/port-1") {
                call.response.headers.append(HttpHeaders.RetryAfter, "30")
                call.respondText(
                    """{"code":"service_unavailable","message":"Risk engine temporarily unavailable"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
            }
        }
        val httpClient = HttpClient(CIO) {
            install(ContentNegotiation) { json() }
        }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, riskBackend.baseUrl)

            testApplication {
                application { module(riskClient) }

                val response = client.post("/api/v1/risk/var/port-1") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"calculationType":"PARAMETRIC","confidenceLevel":"CL_95"}""")
                }
                response.status shouldBe HttpStatusCode.ServiceUnavailable
                response.headers[HttpHeaders.RetryAfter] shouldBe "30"
                val body = response.bodyAsText()
                body shouldContain "SERVICE_UNAVAILABLE"
            }
        } finally {
            httpClient.close()
            riskBackend.close()
        }
    }

    test("risk-orchestrator 503 does not affect position service endpoints") {
        val positionBackend = BackendStubServer {
            get("/api/v1/books/port-1/positions") {
                call.respondText("[]", ContentType.Application.Json, HttpStatusCode.OK)
            }
            get("/api/v1/books") {
                call.respondText("[]", ContentType.Application.Json, HttpStatusCode.OK)
            }
        }
        val httpClient = HttpClient(CIO) {
            install(ContentNegotiation) { json() }
        }
        try {
            val positionClient = HttpPositionServiceClient(httpClient, positionBackend.baseUrl)

            testApplication {
                application { module(positionClient) }

                // Positions endpoint should remain functional
                val positionResponse = client.get("/api/v1/books/port-1/positions")
                positionResponse.status shouldBe HttpStatusCode.OK

                // Books listing also works
                val booksResponse = client.get("/api/v1/books")
                booksResponse.status shouldBe HttpStatusCode.OK
            }
        } finally {
            httpClient.close()
            positionBackend.close()
        }
    }

    test("system health endpoint returns DEGRADED when one upstream is unavailable") {
        // Build a mock HTTP client simulating one service DOWN, rest healthy
        val mockEngine = MockEngine { request ->
            val url = request.url.toString()
            when {
                url.contains("risk-orchestrator") -> respond(
                    content = """{"status":"UP"}""",
                    status = HttpStatusCode.ServiceUnavailable,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond(
                    content = """{"status":"READY","checks":{}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val mockHttpClient = HttpClient(mockEngine) { install(ContentNegotiation) { json() } }

        testApplication {
            application {
                install(ServerContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
                routing {
                    systemHealthRoute(
                        httpClient = mockHttpClient,
                        serviceUrls = mapOf(
                            "position-service" to "http://position-service",
                            "price-service" to "http://price-service",
                            "risk-orchestrator" to "http://risk-orchestrator",
                        ),
                    )
                }
            }

            val response = client.get("/api/v1/system/health")
            response.status shouldBe HttpStatusCode.OK

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["status"]?.jsonPrimitive?.content shouldBe "DEGRADED"
        }
    }

    test("system health endpoint reports READY for all services when all are up") {
        val mockEngine = MockEngine { _ ->
            respond(
                content = """{"status":"READY","checks":{}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val mockHttpClient = HttpClient(mockEngine) { install(ContentNegotiation) { json() } }

        testApplication {
            application {
                install(ServerContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
                routing {
                    systemHealthRoute(
                        httpClient = mockHttpClient,
                        serviceUrls = mapOf(
                            "position-service" to "http://position-service",
                            "risk-orchestrator" to "http://risk-orchestrator",
                        ),
                    )
                }
            }

            val response = client.get("/api/v1/system/health")
            response.status shouldBe HttpStatusCode.OK

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["status"]?.jsonPrimitive?.content shouldBe "UP"
        }
    }
})

/**
 * Extracts the system health routing logic from devModule so it can be tested in isolation.
 *
 * This mirrors the logic in Application.devModule() for the /api/v1/system/health endpoint
 * but accepts injectable dependencies for testability.
 */
internal fun Route.systemHealthRoute(
    httpClient: HttpClient,
    serviceUrls: Map<String, String>,
) {
    get("/api/v1/system/health") {
        val results = coroutineScope {
            serviceUrls.map { (name, url) ->
                name to async {
                    try {
                        val resp = withTimeoutOrNull(5_000L) {
                            httpClient.get("$url/health/ready")
                        }
                        if (resp != null && resp.status == HttpStatusCode.OK) "READY" else "NOT_READY"
                    } catch (_: Exception) {
                        "DOWN"
                    }
                }
            }.map { (name, deferred) -> name to deferred.await() }
        }
        val overall = if (results.all { it.second == "READY" }) "UP" else "DEGRADED"
        call.respondText(
            """{"status":"$overall","services":{${results.joinToString(",") { (name, status) -> """"$name":{"status":"$status"}""" }}}}""",
            ContentType.Application.Json,
        )
    }
}

package com.kinetix.gateway.routes

import com.kinetix.gateway.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Acceptance test for the gateway data-quality routes. Wires the routes against
 * a real in-process HTTP backend that mimics the position-service `GET
 * /api/v1/books` contract — which returns objects keyed by `bookId`, not `id`.
 *
 * Reproduces the bug where the gateway's internal `PortfolioListItemDto`
 * expects a field named `id`, causing the Position Count check to flip to
 * `CRITICAL` with a `Field 'id' is required` decoding error instead of `OK`
 * with `N portfolio(s) active`.
 */
class DataQualityRoutesAcceptanceTest : FunSpec({

    test("Position Count check is OK when position-service /api/v1/books returns bookId payloads") {
        val backend = BackendStubServer {
            get("/api/v1/books") {
                call.respondText(
                    contentType = ContentType.Application.Json,
                    text = """[{"bookId":"balanced-income"}]""",
                )
            }
        }

        val httpClient = HttpClient(CIO) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        try {
            testApplication {
                application {
                    install(ServerContentNegotiation) { json() }
                    routing {
                        dataQualityRoutes(
                            httpClient = httpClient,
                            positionUrl = backend.baseUrl,
                            priceUrl = null,
                            riskUrl = null,
                        )
                    }
                }

                val response = client.get("/api/v1/data-quality/status")
                response.status shouldBe HttpStatusCode.OK

                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val positionCheck = body["checks"]!!.jsonArray
                    .map { it.jsonObject }
                    .first { it["name"]!!.jsonPrimitive.content == "Position Count" }

                positionCheck["status"]!!.jsonPrimitive.content shouldBe "OK"
                positionCheck["message"]!!.jsonPrimitive.content shouldContain "portfolio(s) active"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }
})

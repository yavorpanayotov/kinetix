package com.kinetix.gateway.contract

import com.kinetix.gateway.client.HttpPositionServiceClient
import com.kinetix.gateway.module
import com.kinetix.gateway.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class GatewayPositionNotesContractAcceptanceTest : FunSpec({

    val noteJson = """
        {
          "id":"123e4567-e89b-12d3-a456-426614174000",
          "bookId":"port-1",
          "instrumentId":"AAPL",
          "note":"Earnings call Friday",
          "author":"alice",
          "createdAt":"2026-05-18T10:00:00Z"
        }
    """.trimIndent()

    test("GET /api/v1/positions/{bookId}/notes — 200 — gateway returns list from upstream") {
        val backend = BackendStubServer {
            get("/api/v1/positions/port-1/notes") {
                call.respond(Json.parseToJsonElement("[$noteJson]").jsonArray)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val positionClient = HttpPositionServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(positionClient) }
                val response = client.get("/api/v1/positions/port-1/notes")

                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
                body.size shouldBe 1
                body[0].jsonObject["bookId"]?.jsonPrimitive?.content shouldBe "port-1"
                body[0].jsonObject["instrumentId"]?.jsonPrimitive?.content shouldBe "AAPL"
                body[0].jsonObject["note"]?.jsonPrimitive?.content shouldBe "Earnings call Friday"

                val recorded = backend.recordedRequests.single { it.path == "/api/v1/positions/port-1/notes" }
                recorded.method shouldBe "GET"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("GET /api/v1/positions/{bookId}/notes?instrumentId=AAPL — forwards instrumentId query parameter") {
        val backend = BackendStubServer {
            get("/api/v1/positions/port-1/notes") {
                call.respond(Json.parseToJsonElement("[$noteJson]").jsonArray)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val positionClient = HttpPositionServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(positionClient) }
                val response = client.get("/api/v1/positions/port-1/notes?instrumentId=AAPL")

                response.status shouldBe HttpStatusCode.OK

                val recorded = backend.recordedRequests.single { it.path == "/api/v1/positions/port-1/notes" }
                recorded.method shouldBe "GET"
                recorded.query["instrumentId"] shouldBe listOf("AAPL")
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("POST /api/v1/positions/{bookId}/notes — 201 — gateway forwards body and returns created note") {
        val backend = BackendStubServer {
            post("/api/v1/positions/port-1/notes") {
                val received = call.receiveText()
                // Echo back a fully-formed note. The exact author is set server-side; we
                // verify the gateway sent the body through unchanged via recordedRequests.
                val noteFromBody = Json.parseToJsonElement(received).jsonObject
                val responseJson = """
                    {
                      "id":"abc",
                      "bookId":"port-1",
                      "instrumentId":"${noteFromBody["instrumentId"]?.jsonPrimitive?.content}",
                      "note":"${noteFromBody["note"]?.jsonPrimitive?.content}",
                      "author":"anonymous",
                      "createdAt":"2026-05-18T10:00:00Z"
                    }
                """.trimIndent()
                call.respond(HttpStatusCode.Created, Json.parseToJsonElement(responseJson).jsonObject)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val positionClient = HttpPositionServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(positionClient) }
                val response = client.post("/api/v1/positions/port-1/notes") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"instrumentId":"AAPL","note":"watching earnings"}""")
                }

                response.status shouldBe HttpStatusCode.Created
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["bookId"]?.jsonPrimitive?.content shouldBe "port-1"
                body["instrumentId"]?.jsonPrimitive?.content shouldBe "AAPL"
                body["note"]?.jsonPrimitive?.content shouldBe "watching earnings"

                val recorded = backend.recordedRequests.single { it.path == "/api/v1/positions/port-1/notes" }
                recorded.method shouldBe "POST"
                // Gateway sets X-User from userIdOrDefault() — defaults to "anonymous"
                // when no JWT principal is present in this unauthenticated test wiring.
                recorded.headers["X-User"]?.single() shouldBe "anonymous"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("POST /api/v1/positions/{bookId}/notes — upstream 400 — propagates as 400") {
        val backend = BackendStubServer {
            post("/api/v1/positions/port-1/notes") {
                call.respond(
                    HttpStatusCode.BadRequest,
                    Json.parseToJsonElement("""{"error":"invalid_request","message":"note must not be blank"}""").jsonObject,
                )
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val positionClient = HttpPositionServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(positionClient) }
                val response = client.post("/api/v1/positions/port-1/notes") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"instrumentId":"AAPL","note":""}""")
                }

                response.status shouldBe HttpStatusCode.BadRequest
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("DELETE /api/v1/positions/notes/{id} — upstream 204 — gateway returns 204") {
        val backend = BackendStubServer {
            delete("/api/v1/positions/notes/abc") {
                call.respond(HttpStatusCode.NoContent)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val positionClient = HttpPositionServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(positionClient) }
                val response = client.delete("/api/v1/positions/notes/abc")

                response.status shouldBe HttpStatusCode.NoContent

                val recorded = backend.recordedRequests.single { it.path == "/api/v1/positions/notes/abc" }
                recorded.method shouldBe "DELETE"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("DELETE /api/v1/positions/notes/{id} — upstream 404 — gateway returns 404") {
        val backend = BackendStubServer {
            delete("/api/v1/positions/notes/missing") {
                call.respond(
                    HttpStatusCode.NotFound,
                    Json.parseToJsonElement("""{"error":"note_not_found","message":"No position note exists with id missing"}""").jsonObject,
                )
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val positionClient = HttpPositionServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(positionClient) }
                val response = client.delete("/api/v1/positions/notes/missing")

                response.status shouldBe HttpStatusCode.NotFound
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["error"]?.jsonPrimitive?.content shouldBe "note_not_found"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }
})

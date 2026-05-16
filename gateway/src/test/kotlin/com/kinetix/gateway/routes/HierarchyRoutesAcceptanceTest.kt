package com.kinetix.gateway.routes

import com.kinetix.gateway.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class HierarchyRoutesAcceptanceTest : FunSpec({

    test("GET /api/v1/divisions proxies to reference-data and returns the same list") {
        val divisionsPayload = buildJsonArray {
            add(buildJsonObject {
                put("id", "div-equities"); put("name", "Equities"); put("description", ""); put("deskCount", 3)
            })
            add(buildJsonObject {
                put("id", "div-rates"); put("name", "Rates"); put("description", ""); put("deskCount", 2)
            })
        }
        val backend = BackendStubServer {
            get("/api/v1/divisions") {
                call.respond(divisionsPayload)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { hierarchyRoutes(httpClient, backend.baseUrl) }
                }
                val response = client.get("/api/v1/divisions")
                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
                body.size shouldBe 2
                body[0].jsonObject["id"]?.jsonPrimitive?.content shouldBe "div-equities"
            }
        } finally {
            httpClient.close(); backend.close()
        }
    }

    test("GET /api/v1/desks proxies to reference-data") {
        val desksPayload = buildJsonArray {
            add(buildJsonObject { put("id", "desk-equities-cash"); put("name", "Cash Equities"); put("divisionId", "div-equities"); put("deskHead", "A"); put("description", ""); put("bookCount", 0) })
        }
        val backend = BackendStubServer {
            get("/api/v1/desks") { call.respond(desksPayload) }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { hierarchyRoutes(httpClient, backend.baseUrl) }
                }
                val response = client.get("/api/v1/desks")
                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
                body.size shouldBe 1
            }
        } finally {
            httpClient.close(); backend.close()
        }
    }

    test("GET /api/v1/firm/summary returns a zero-aggregate stub when no cross-book data is available") {
        val backend = BackendStubServer { /* no routes — gateway should not call backend */ }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { hierarchyRoutes(httpClient, backend.baseUrl) }
                }
                val response = client.get("/api/v1/firm/summary")
                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["bookId"]?.jsonPrimitive?.content shouldBe "firm"
                body["baseCurrency"]?.jsonPrimitive?.content shouldBe "USD"
                body["totalNav"]!!.jsonObject["amount"]?.jsonPrimitive?.content shouldBe "0"
            }
        } finally {
            httpClient.close(); backend.close()
        }
    }

    test("GET /api/v1/divisions/{id}/summary?baseCurrency=EUR honours the base currency") {
        val backend = BackendStubServer { }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { hierarchyRoutes(httpClient, backend.baseUrl) }
                }
                val response = client.get("/api/v1/divisions/div-rates/summary?baseCurrency=EUR")
                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["baseCurrency"]?.jsonPrimitive?.content shouldBe "EUR"
                body["totalNav"]!!.jsonObject["currency"]?.jsonPrimitive?.content shouldBe "EUR"
            }
        } finally {
            httpClient.close(); backend.close()
        }
    }
})

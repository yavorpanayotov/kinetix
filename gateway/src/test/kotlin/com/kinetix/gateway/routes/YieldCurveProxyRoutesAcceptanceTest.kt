package com.kinetix.gateway.routes

import com.kinetix.gateway.moduleWithYieldCurveProxy
import com.kinetix.gateway.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.http.ContentType
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Acceptance test for the gateway yield-curve proxy. Validates that the
 * proxy merges canonical interpolated tenors into the rates-service `/latest`
 * response so the UI gets a single response with the `interpolated` flag per
 * point — including the GBP 5Y missing-node anomaly.
 */
class YieldCurveProxyRoutesAcceptanceTest : FunSpec({

    test("GET yield-curves/{ccy} fills in missing canonical tenors as interpolated") {
        val backend = BackendStubServer {
            get("/api/v1/rates/yield-curves/GBP/latest") {
                // GBP latest is seeded without 5Y (Gap 8 anomaly).
                call.respondText(
                    contentType = ContentType.Application.Json,
                    text = """
                    {
                      "curveId": "GBP",
                      "currency": "GBP",
                      "tenors": [
                        {"label": "O/N", "days": 1, "rate": "0.0500"},
                        {"label": "1W", "days": 7, "rate": "0.0502"},
                        {"label": "1M", "days": 30, "rate": "0.0505"},
                        {"label": "3M", "days": 90, "rate": "0.0508"},
                        {"label": "6M", "days": 180, "rate": "0.0512"},
                        {"label": "1Y", "days": 365, "rate": "0.0515"},
                        {"label": "2Y", "days": 730, "rate": "0.0520"},
                        {"label": "10Y", "days": 3650, "rate": "0.0530"},
                        {"label": "30Y", "days": 10950, "rate": "0.0540"}
                      ],
                      "asOfDate": "2026-02-22T10:00:00Z",
                      "source": "CENTRAL_BANK"
                    }
                    """.trimIndent(),
                )
            }
            get("/api/v1/rates/yield-curves/GBP/tenor/5Y") {
                call.respondText(
                    contentType = ContentType.Application.Json,
                    text = """
                    {
                      "curveId": "GBP",
                      "tenor": "5Y",
                      "value": "0.052500",
                      "interpolated": true
                    }
                    """.trimIndent(),
                )
            }
        }

        val httpClient = HttpClient(CIO) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        try {
            testApplication {
                application { moduleWithYieldCurveProxy(httpClient, backend.baseUrl) }

                val response = client.get("/api/v1/rates/yield-curves/GBP")
                response.status shouldBe HttpStatusCode.OK

                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["curveId"]?.jsonPrimitive?.content shouldBe "GBP"
                body["currency"]?.jsonPrimitive?.content shouldBe "GBP"

                val points = body["points"]!!.jsonArray
                // 9 stored tenors + 1 interpolated (5Y) = 10
                points.size shouldBe 10

                val labels = points.map { it.jsonObject["label"]!!.jsonPrimitive.content }
                labels shouldContain "5Y"

                // Stored tenors carry interpolated=false; the merged 5Y carries interpolated=true.
                val byLabel = points.associateBy { it.jsonObject["label"]!!.jsonPrimitive.content }
                byLabel["5Y"]!!.jsonObject["interpolated"]!!.jsonPrimitive.boolean shouldBe true
                byLabel["2Y"]!!.jsonObject["interpolated"]!!.jsonPrimitive.boolean shouldBe false
                byLabel["10Y"]!!.jsonObject["interpolated"]!!.jsonPrimitive.boolean shouldBe false

                // Points are returned in ascending order of days.
                val days = points.map { it.jsonObject["days"]!!.jsonPrimitive.content.toInt() }
                days shouldBe days.sorted()
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("GET yield-curves/{ccy} returns 404 when rates-service has no latest curve") {
        val backend = BackendStubServer {
            get("/api/v1/rates/yield-curves/XYZ/latest") {
                call.respond(HttpStatusCode.NotFound)
            }
        }

        val httpClient = HttpClient(CIO) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        try {
            testApplication {
                application { moduleWithYieldCurveProxy(httpClient, backend.baseUrl) }
                val response = client.get("/api/v1/rates/yield-curves/XYZ")
                response.status shouldBe HttpStatusCode.NotFound
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("GET yield-curves/{ccy} marks every point as interpolated=false when stored curve is complete") {
        val backend = BackendStubServer {
            get("/api/v1/rates/yield-curves/USD/latest") {
                call.respondText(
                    contentType = ContentType.Application.Json,
                    text = """
                    {
                      "curveId": "USD",
                      "currency": "USD",
                      "tenors": [
                        {"label": "O/N", "days": 1, "rate": "0.0450"},
                        {"label": "1W", "days": 7, "rate": "0.0452"},
                        {"label": "1M", "days": 30, "rate": "0.0455"},
                        {"label": "3M", "days": 90, "rate": "0.0458"},
                        {"label": "6M", "days": 180, "rate": "0.0462"},
                        {"label": "1Y", "days": 365, "rate": "0.0465"},
                        {"label": "2Y", "days": 730, "rate": "0.0468"},
                        {"label": "5Y", "days": 1825, "rate": "0.0472"},
                        {"label": "10Y", "days": 3650, "rate": "0.0476"},
                        {"label": "30Y", "days": 10950, "rate": "0.0480"}
                      ],
                      "asOfDate": "2026-02-22T10:00:00Z",
                      "source": "CENTRAL_BANK"
                    }
                    """.trimIndent(),
                )
            }
        }

        val httpClient = HttpClient(CIO) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        try {
            testApplication {
                application { moduleWithYieldCurveProxy(httpClient, backend.baseUrl) }
                val response = client.get("/api/v1/rates/yield-curves/USD")
                response.status shouldBe HttpStatusCode.OK

                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val points = body["points"]!!.jsonArray
                points.size shouldBe 10
                points.forEach {
                    it.jsonObject["interpolated"]!!.jsonPrimitive.boolean shouldBe false
                }
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }
})

package com.kinetix.gateway.routes

import com.kinetix.gateway.client.PositionServiceClient
import com.kinetix.gateway.client.PriceServiceClient
import com.kinetix.gateway.client.RiskServiceClient
import com.kinetix.gateway.moduleWithDataQuality
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.*
import kotlinx.serialization.json.*

class DataQualityRoutesTest : FunSpec({

    val positionClient = mockk<PositionServiceClient>()
    val priceClient = mockk<PriceServiceClient>()
    val riskClient = mockk<RiskServiceClient>()

    beforeEach { clearMocks(positionClient, priceClient, riskClient) }

    test("GET /api/v1/data-quality/status returns 200 with quality checks") {
        testApplication {
            application { moduleWithDataQuality(positionClient, priceClient, riskClient) }
            val response = client.get("/api/v1/data-quality/status")
            response.status shouldBe HttpStatusCode.OK

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body.containsKey("overall") shouldBe true
            body.containsKey("checks") shouldBe true

            val checks = body["checks"]?.jsonArray
            checks!!.size shouldBe 4
        }
    }

    test("GET /api/v1/data-quality/status returns check names including FIX connectivity") {
        testApplication {
            application { moduleWithDataQuality(positionClient, priceClient, riskClient) }
            val response = client.get("/api/v1/data-quality/status")
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val checks = body["checks"]!!.jsonArray

            val checkNames = checks.map { it.jsonObject["name"]?.jsonPrimitive?.content }
            checkNames shouldBe listOf(
                "Price Freshness",
                "Position Count",
                "Risk Result Completeness",
                "FIX Connectivity",
            )
        }
    }

    test("GET /api/v1/data-quality/status returns UNKNOWN overall when all checks are not implemented") {
        testApplication {
            application { moduleWithDataQuality(positionClient, priceClient, riskClient) }
            val response = client.get("/api/v1/data-quality/status")
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["overall"]?.jsonPrimitive?.content shouldBe "UNKNOWN"
        }
    }

    test("each check has required fields: name, status, message, lastChecked") {
        testApplication {
            application { moduleWithDataQuality(positionClient, priceClient, riskClient) }
            val response = client.get("/api/v1/data-quality/status")
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val checks = body["checks"]!!.jsonArray

            for (check in checks) {
                val obj = check.jsonObject
                obj.containsKey("name") shouldBe true
                obj.containsKey("status") shouldBe true
                obj.containsKey("message") shouldBe true
                obj.containsKey("lastChecked") shouldBe true
            }
        }
    }

    // --- FIX connectivity check tests using MockEngine ---

    fun buildMockHttpClient(sessionsJson: String, statusCode: HttpStatusCode = HttpStatusCode.OK): HttpClient {
        val mockEngine = MockEngine { _ ->
            respond(
                content = sessionsJson,
                status = statusCode,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return HttpClient(mockEngine) {
            install(ContentNegotiation) { json() }
        }
    }

    fun Application.configureWithMockFixClient(mockHttpClient: HttpClient) {
        install(ServerContentNegotiation) { json() }
        routing {
            dataQualityRoutes(mockHttpClient, "http://position-service")
        }
    }

    test("FIX connectivity check returns OK when all sessions are CONNECTED") {
        val mockClient = buildMockHttpClient(
            """[{"sessionId":"S1","counterparty":"Broker A","status":"CONNECTED","inboundSeqNum":100,"outboundSeqNum":50}]"""
        )

        testApplication {
            application { configureWithMockFixClient(mockClient) }
            val response = client.get("/api/v1/data-quality/status")
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val fixCheck = body["checks"]!!.jsonArray
                .map { it.jsonObject }
                .first { it["name"]!!.jsonPrimitive.content == "FIX Connectivity" }

            fixCheck["status"]!!.jsonPrimitive.content shouldBe "OK"
            fixCheck["message"]!!.jsonPrimitive.content shouldContain "1 FIX session(s) connected"
        }
    }

    test("FIX connectivity check returns CRITICAL when all sessions are DISCONNECTED") {
        val mockClient = buildMockHttpClient(
            """[
                {"sessionId":"S1","counterparty":"Broker A","status":"DISCONNECTED","inboundSeqNum":0,"outboundSeqNum":0},
                {"sessionId":"S2","counterparty":"Broker B","status":"DISCONNECTED","inboundSeqNum":0,"outboundSeqNum":0}
            ]"""
        )

        testApplication {
            application { configureWithMockFixClient(mockClient) }
            val response = client.get("/api/v1/data-quality/status")
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val fixCheck = body["checks"]!!.jsonArray
                .map { it.jsonObject }
                .first { it["name"]!!.jsonPrimitive.content == "FIX Connectivity" }

            fixCheck["status"]!!.jsonPrimitive.content shouldBe "CRITICAL"
            body["overall"]!!.jsonPrimitive.content shouldBe "CRITICAL"
        }
    }

    test("FIX connectivity check returns WARNING when some sessions are DISCONNECTED") {
        val mockClient = buildMockHttpClient(
            """[
                {"sessionId":"S1","counterparty":"Broker A","status":"CONNECTED","inboundSeqNum":100,"outboundSeqNum":50},
                {"sessionId":"S2","counterparty":"Broker B","status":"DISCONNECTED","inboundSeqNum":0,"outboundSeqNum":0}
            ]"""
        )

        testApplication {
            application { configureWithMockFixClient(mockClient) }
            val response = client.get("/api/v1/data-quality/status")
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val fixCheck = body["checks"]!!.jsonArray
                .map { it.jsonObject }
                .first { it["name"]!!.jsonPrimitive.content == "FIX Connectivity" }

            fixCheck["status"]!!.jsonPrimitive.content shouldBe "WARNING"
        }
    }

    test("FIX connectivity check returns CRITICAL when position-service is unreachable") {
        val mockEngine = MockEngine { _ -> throw java.io.IOException("Connection refused") }
        val mockClient = HttpClient(mockEngine) { install(ContentNegotiation) { json() } }

        testApplication {
            application { configureWithMockFixClient(mockClient) }
            val response = client.get("/api/v1/data-quality/status")
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val fixCheck = body["checks"]!!.jsonArray
                .map { it.jsonObject }
                .first { it["name"]!!.jsonPrimitive.content == "FIX Connectivity" }

            fixCheck["status"]!!.jsonPrimitive.content shouldBe "CRITICAL"
            body["overall"]!!.jsonPrimitive.content shouldBe "CRITICAL"
        }
    }

    test("FIX connectivity check returns WARNING when no sessions are configured") {
        val mockClient = buildMockHttpClient("[]")

        testApplication {
            application { configureWithMockFixClient(mockClient) }
            val response = client.get("/api/v1/data-quality/status")
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val fixCheck = body["checks"]!!.jsonArray
                .map { it.jsonObject }
                .first { it["name"]!!.jsonPrimitive.content == "FIX Connectivity" }

            fixCheck["status"]!!.jsonPrimitive.content shouldBe "WARNING"
            fixCheck["message"]!!.jsonPrimitive.content shouldContain "No FIX sessions configured"
        }
    }

    // --- Wired data quality check tests ---

    fun buildMultiUrlMockClient(responsesByPath: Map<String, Pair<String, HttpStatusCode>>): HttpClient {
        val mockEngine = MockEngine { request ->
            val path = request.url.encodedPath
            val (body, status) = responsesByPath.entries
                .firstOrNull { (pattern, _) -> path.contains(pattern) }
                ?.value
                ?: ("""{"status":"READY","checks":{}}""" to HttpStatusCode.OK)
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return HttpClient(mockEngine) { install(ContentNegotiation) { json() } }
    }

    fun Application.configureWithWiredChecks(
        mockHttpClient: HttpClient,
        priceUrl: String = "http://price-service",
        riskUrl: String = "http://risk-orchestrator",
    ) {
        install(ServerContentNegotiation) { json() }
        routing {
            dataQualityRoutes(
                httpClient = mockHttpClient,
                positionUrl = "http://position-service",
                priceUrl = priceUrl,
                riskUrl = riskUrl,
            )
        }
    }

    test("Price Freshness check returns OK when price-service readiness endpoint reports READY") {
        val mockClient = buildMultiUrlMockClient(
            mapOf(
                "health/ready" to ("""{"status":"READY","checks":{}}""" to HttpStatusCode.OK),
                "fix/sessions" to ("""[]""" to HttpStatusCode.OK),
            )
        )

        testApplication {
            application { configureWithWiredChecks(mockClient) }
            val response = client.get("/api/v1/data-quality/status")
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val check = body["checks"]!!.jsonArray
                .map { it.jsonObject }
                .first { it["name"]!!.jsonPrimitive.content == "Price Freshness" }

            check["status"]!!.jsonPrimitive.content shouldBe "OK"
        }
    }

    test("Price Freshness check returns WARNING when price-service reports stale instruments") {
        val mockClient = buildMultiUrlMockClient(
            mapOf(
                "/api/v1/prices/stale" to ("""[{"instrumentId":"JNJ","lastUpdated":"2026-02-21T04:00:00Z","ageHours":30,"status":"STALE"}]""" to HttpStatusCode.OK),
                "health/ready" to ("""{"status":"READY","checks":{}}""" to HttpStatusCode.OK),
                "fix/sessions" to ("""[]""" to HttpStatusCode.OK),
            )
        )

        testApplication {
            application { configureWithWiredChecks(mockClient) }
            val response = client.get("/api/v1/data-quality/status")
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val check = body["checks"]!!.jsonArray
                .map { it.jsonObject }
                .first { it["name"]!!.jsonPrimitive.content == "Price Freshness" }

            check["status"]!!.jsonPrimitive.content shouldBe "WARNING"
            check["message"]!!.jsonPrimitive.content shouldContain "JNJ"
            check["message"]!!.jsonPrimitive.content shouldContain "30h"
        }
    }

    test("Price Freshness check returns CRITICAL when price-service is unreachable") {
        val mockEngine = MockEngine { request ->
            if (request.url.encodedPath.contains("health/ready") &&
                request.url.host == "price-service"
            ) {
                throw java.io.IOException("Connection refused")
            }
            respond(content = "[]", status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val mockClient = HttpClient(mockEngine) { install(ContentNegotiation) { json() } }

        testApplication {
            application { configureWithWiredChecks(mockClient) }
            val response = client.get("/api/v1/data-quality/status")
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val check = body["checks"]!!.jsonArray
                .map { it.jsonObject }
                .first { it["name"]!!.jsonPrimitive.content == "Price Freshness" }

            check["status"]!!.jsonPrimitive.content shouldBe "CRITICAL"
        }
    }

    test("Position Count check returns OK when position-service has portfolios") {
        val mockClient = buildMultiUrlMockClient(
            mapOf(
                "api/v1/books" to ("""[{"bookId":"port-1"}]""" to HttpStatusCode.OK),
                "health/ready" to ("""{"status":"READY","checks":{}}""" to HttpStatusCode.OK),
                "fix/sessions" to ("""[]""" to HttpStatusCode.OK),
            )
        )

        testApplication {
            application { configureWithWiredChecks(mockClient) }
            val response = client.get("/api/v1/data-quality/status")
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val check = body["checks"]!!.jsonArray
                .map { it.jsonObject }
                .first { it["name"]!!.jsonPrimitive.content == "Position Count" }

            check["status"]!!.jsonPrimitive.content shouldBe "OK"
        }
    }

    test("Position Count check returns WARNING when no portfolios exist") {
        val mockClient = buildMultiUrlMockClient(
            mapOf(
                "api/v1/books" to ("""[]""" to HttpStatusCode.OK),
                "health/ready" to ("""{"status":"READY","checks":{}}""" to HttpStatusCode.OK),
                "fix/sessions" to ("""[]""" to HttpStatusCode.OK),
            )
        )

        testApplication {
            application { configureWithWiredChecks(mockClient) }
            val response = client.get("/api/v1/data-quality/status")
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val check = body["checks"]!!.jsonArray
                .map { it.jsonObject }
                .first { it["name"]!!.jsonPrimitive.content == "Position Count" }

            check["status"]!!.jsonPrimitive.content shouldBe "WARNING"
        }
    }

    test("Risk Result Completeness check returns OK when risk-orchestrator is READY with no DLQ records") {
        val readyBody = """{"status":"READY","checks":{},"consumers":{"trades.lifecycle":{"lastProcessedAt":null,"recordsProcessedTotal":100,"recordsSentToDlqTotal":0,"lastErrorAt":null,"consecutiveErrorCount":0}}}"""
        val mockClient = buildMultiUrlMockClient(
            mapOf(
                "health/ready" to (readyBody to HttpStatusCode.OK),
                "fix/sessions" to ("""[]""" to HttpStatusCode.OK),
                "api/v1/books" to ("""[{"bookId":"port-1"}]""" to HttpStatusCode.OK),
            )
        )

        testApplication {
            application { configureWithWiredChecks(mockClient) }
            val response = client.get("/api/v1/data-quality/status")
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val check = body["checks"]!!.jsonArray
                .map { it.jsonObject }
                .first { it["name"]!!.jsonPrimitive.content == "Risk Result Completeness" }

            check["status"]!!.jsonPrimitive.content shouldBe "OK"
        }
    }

    test("Risk Result Completeness check returns WARNING when risk-orchestrator has DLQ records") {
        val readyBody = """{"status":"READY","checks":{},"consumers":{"trades.lifecycle":{"lastProcessedAt":null,"recordsProcessedTotal":100,"recordsSentToDlqTotal":5,"lastErrorAt":null,"consecutiveErrorCount":0}}}"""
        val mockClient = buildMultiUrlMockClient(
            mapOf(
                "health/ready" to (readyBody to HttpStatusCode.OK),
                "fix/sessions" to ("""[]""" to HttpStatusCode.OK),
                "api/v1/books" to ("""[{"bookId":"port-1"}]""" to HttpStatusCode.OK),
            )
        )

        testApplication {
            application { configureWithWiredChecks(mockClient) }
            val response = client.get("/api/v1/data-quality/status")
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val check = body["checks"]!!.jsonArray
                .map { it.jsonObject }
                .first { it["name"]!!.jsonPrimitive.content == "Risk Result Completeness" }

            check["status"]!!.jsonPrimitive.content shouldBe "WARNING"
        }
    }
})

package com.kinetix.gateway.contract

import com.kinetix.gateway.routes.limitsRoutes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Trader-review P0: the Limits screen previously rendered intraday/overnight
 * cells as raw ceiling figures (or em-dash where ceilings were null), giving
 * the trader no sense of "how close to the wall" each limit is. The contract
 * between gateway and position-service must now carry a current-utilisation
 * pair — `current` (dollar/quantity value consumed) and `utilisationPct`
 * (0–100, ratio of current to effective limit) — so the UI can render
 * `$640M (80%)` style cells instead of an em-dash.
 *
 * These tests pin the gateway side of the contract: the limits proxy must
 * pass the new fields through to the UI verbatim, in both shapes the
 * upstream may return (utilisation present, utilisation null). The fields
 * are decoded with strict JSON (unknown keys forbidden, missing keys would
 * deserialize to null but we assert the keys are present explicitly).
 */
private fun Application.configureLimitsProxy(mockEngine: MockEngine) {
    val upstreamClient = HttpClient(mockEngine)
    install(ContentNegotiation) { json() }
    routing {
        limitsRoutes(upstreamClient, "http://position-service")
    }
}

private val parser = Json { ignoreUnknownKeys = false }

class LimitsUtilisationAcceptanceTest : FunSpec({

    test("GET /api/v1/limits surfaces current + utilisationPct fields when upstream populates them") {
        val upstreamPayload = """
            [
              {
                "id": "firm-default-notional",
                "level": "FIRM",
                "entityId": "FIRM",
                "limitType": "NOTIONAL",
                "limitValue": "800000000",
                "intradayLimit": null,
                "overnightLimit": null,
                "current": "640000000",
                "utilisationPct": 80.0,
                "active": true
              }
            ]
        """.trimIndent()

        val mockEngine = MockEngine {
            respond(
                content = upstreamPayload,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        testApplication {
            application { configureLimitsProxy(mockEngine) }

            val response = client.get("/api/v1/limits")
            response.status shouldBe HttpStatusCode.OK

            val row = parser.parseToJsonElement(response.bodyAsText()).jsonArray
                .single().jsonObject

            // Gateway must not strip the new fields — UI depends on both being present.
            row["current"]?.jsonPrimitive?.contentOrNull() shouldBe "640000000"
            row["utilisationPct"].shouldNotBeNull()
            (row["utilisationPct"] as JsonPrimitive).float shouldBe 80.0f
            // Existing ceiling fields must still flow through alongside the new ones.
            row["limitValue"]?.jsonPrimitive?.contentOrNull() shouldBe "800000000"
        }
    }

    test("GET /api/v1/limits preserves utilisation fields as null when upstream cannot compute usage") {
        // VAR/CONCENTRATION limits live in risk-orchestrator land — position-service
        // has no current-value source for them and emits `null` for both
        // `current` and `utilisationPct`. Gateway must pass the nulls through
        // rather than substituting "0" / 0 (which would render as a misleading
        // "$0 (0%)" cell on a VAR limit that's actually 90% consumed).
        val upstreamPayload = """
            [
              {
                "id": "firm-var",
                "level": "FIRM",
                "entityId": "FIRM",
                "limitType": "VAR",
                "limitValue": "5000000",
                "intradayLimit": null,
                "overnightLimit": null,
                "current": null,
                "utilisationPct": null,
                "active": true
              }
            ]
        """.trimIndent()

        val mockEngine = MockEngine {
            respond(
                content = upstreamPayload,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        testApplication {
            application { configureLimitsProxy(mockEngine) }

            val response = client.get("/api/v1/limits")
            response.status shouldBe HttpStatusCode.OK

            val row = parser.parseToJsonElement(response.bodyAsText()).jsonArray
                .single().jsonObject

            // Both fields must be present and explicitly null — not absent.
            row.containsKey("current") shouldBe true
            row.containsKey("utilisationPct") shouldBe true
            row["current"] shouldBe JsonNull
            row["utilisationPct"] shouldBe JsonNull
        }
    }

    test("GET /api/v1/limits passes through current + utilisationPct for a BOOK-level row with intraday/overnight ceilings") {
        // Mirror the demo seed: BOOK-level NOTIONAL with separate intraday and
        // overnight ceilings. The gateway test pins the wire shape: both
        // ceilings AND the utilisation pair must reach the UI.
        val upstreamPayload = """
            [
              {
                "id": "book-eq-growth-notional",
                "level": "BOOK",
                "entityId": "equity-growth",
                "limitType": "NOTIONAL",
                "limitValue": "40000000",
                "intradayLimit": "45000000",
                "overnightLimit": "38000000",
                "current": "32000000",
                "utilisationPct": 71.11,
                "active": true
              }
            ]
        """.trimIndent()

        val mockEngine = MockEngine {
            respond(
                content = upstreamPayload,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        testApplication {
            application { configureLimitsProxy(mockEngine) }

            val response = client.get("/api/v1/limits")
            response.status shouldBe HttpStatusCode.OK

            val row = parser.parseToJsonElement(response.bodyAsText()).jsonArray
                .single().jsonObject

            row["current"]?.jsonPrimitive?.contentOrNull() shouldBe "32000000"
            (row["utilisationPct"] as JsonPrimitive).float shouldBe 71.11f
            row["intradayLimit"]?.jsonPrimitive?.contentOrNull() shouldBe "45000000"
            row["overnightLimit"]?.jsonPrimitive?.contentOrNull() shouldBe "38000000"
        }
    }
})

private fun JsonPrimitive.contentOrNull(): String? = if (this is JsonNull) null else content

@Suppress("unused")
private val _types: Pair<JsonArray, JsonObject>? = null

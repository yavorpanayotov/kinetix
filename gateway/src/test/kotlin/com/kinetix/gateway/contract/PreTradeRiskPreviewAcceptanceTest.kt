package com.kinetix.gateway.contract

import com.kinetix.gateway.client.HttpRiskServiceClient
import com.kinetix.gateway.module
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicReference

/**
 * Trader-review P2 (docs/plans/ui-trader-review.md): on Place Order form-blur,
 * the trader needs a risk-impact preview — Δ VaR, Δ Delta, Δ Notional,
 * Δ counterparty exposure — before clicking Submit. The gateway exposes
 * the preview via `POST /api/v1/risk/pretrade-preview`, which reuses the
 * existing What-If valuation path (risk.allium AnalyseWhatIf) so we do not
 * spin up a parallel pricing flow. These tests pin the contract end-to-end:
 *   - the gateway forwards the candidate order as a single-trade What-If
 *     request to the risk-orchestrator,
 *   - it projects the upstream response into the four-delta preview shape,
 *   - it computes Δ Notional and Δ counterparty exposure locally (the
 *     What-If response does not carry them — notional is a function of
 *     side * quantity * price and counterparty exposure requires a
 *     bilateral counterparty_id that cleared / venue-routed orders lack).
 *
 * The route reuses the production HttpRiskServiceClient against a Ktor
 * MockEngine: the real serialization, the real outbound What-If wire
 * format, the real client error handling are all exercised; only the
 * remote risk-orchestrator process is faked.
 */
private fun whatIfUpstreamResponse(
    baseVar: String,
    hypotheticalVar: String,
    varChange: String,
    baseDeltaPerClass: List<Pair<String, String>>,
    hypotheticalDeltaPerClass: List<Pair<String, String>>,
): String {
    fun greekBlock(byClass: List<Pair<String, String>>) = byClass.joinToString(",") { (cls, d) ->
        """{"assetClass":"$cls","delta":"$d","gamma":"0","vega":"0"}"""
    }
    return """
        {
          "baseVaR":"$baseVar",
          "baseExpectedShortfall":"0",
          "baseGreeks":{
            "bookId":"port-1",
            "assetClassGreeks":[${greekBlock(baseDeltaPerClass)}],
            "theta":"0",
            "rho":"0",
            "calculatedAt":"2026-05-29T10:00:00Z"
          },
          "basePositionRisk":[],
          "hypotheticalVaR":"$hypotheticalVar",
          "hypotheticalExpectedShortfall":"0",
          "hypotheticalGreeks":{
            "bookId":"port-1",
            "assetClassGreeks":[${greekBlock(hypotheticalDeltaPerClass)}],
            "theta":"0",
            "rho":"0",
            "calculatedAt":"2026-05-29T10:00:00Z"
          },
          "hypotheticalPositionRisk":[],
          "varChange":"$varChange",
          "esChange":"0",
          "calculatedAt":"2026-05-29T10:00:00Z"
        }
    """.trimIndent()
}

class PreTradeRiskPreviewAcceptanceTest : FunSpec({

    test("POST /api/v1/risk/pretrade-preview returns Δ VaR / Δ Delta / Δ Notional projected from the What-If valuation path") {
        // Base book: EQUITY delta 100, RATES delta 0  -> total 100
        // After BUY 50 of AAPL @ 100: EQUITY delta 150, RATES delta 0 -> total 150
        // Δ Delta = 50, Δ VaR = 1500, Δ Notional = +5000 (BUY 50 * 100).
        val upstreamBody = whatIfUpstreamResponse(
            baseVar = "10000.00",
            hypotheticalVar = "11500.00",
            varChange = "1500.00",
            baseDeltaPerClass = listOf("EQUITY" to "100.0", "RATES" to "0.0"),
            hypotheticalDeltaPerClass = listOf("EQUITY" to "150.0", "RATES" to "0.0"),
        )

        val capturedUpstreamUrl = AtomicReference<String?>(null)
        val capturedUpstreamBody = AtomicReference<String?>(null)
        val mockEngine = MockEngine { request ->
            capturedUpstreamUrl.set(request.url.toString())
            capturedUpstreamBody.set((request.body as io.ktor.http.content.TextContent).text)
            respond(
                content = upstreamBody,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val upstream = HttpClient(mockEngine) { install(ClientContentNegotiation) { json() } }
        val riskClient = HttpRiskServiceClient(upstream, "http://risk-orchestrator")

        testApplication {
            application { module(riskClient) }
            val response = client.post("/api/v1/risk/pretrade-preview") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "bookId":"port-1",
                      "instrumentId":"AAPL",
                      "assetClass":"EQUITY",
                      "side":"BUY",
                      "quantity":"50",
                      "priceAmount":"100.00",
                      "priceCurrency":"USD",
                      "instrumentType":"CASH_EQUITY"
                    }
                    """.trimIndent(),
                )
            }

            response.status shouldBe HttpStatusCode.OK
            val body: JsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject

            // The four deltas must all be present and numerically correct.
            body["baseVaR"]?.jsonPrimitive?.content shouldBe "10000.00"
            body["hypotheticalVaR"]?.jsonPrimitive?.content shouldBe "11500.00"
            body["varChange"]?.jsonPrimitive?.content shouldBe "1500.00"

            body["baseDelta"]?.jsonPrimitive?.content shouldBe "100.000000"
            body["hypotheticalDelta"]?.jsonPrimitive?.content shouldBe "150.000000"
            body["deltaChange"]?.jsonPrimitive?.content shouldBe "50.000000"

            // BUY 50 * 100 = +5000 (Δ Notional signed by side).
            body["notionalChange"]?.jsonPrimitive?.content shouldBe "5000.00"

            // No counterparty supplied — preview must still succeed and emit null for both.
            body["counterpartyId"] shouldBe JsonNull
            body["counterpartyExposureChange"] shouldBe JsonNull

            body.containsKey("calculatedAt") shouldBe true

            // Gateway must call the existing What-If valuation path on the
            // risk-orchestrator — no parallel pricing flow.
            capturedUpstreamUrl.get() shouldBe "http://risk-orchestrator/api/v1/risk/what-if/port-1"
            val outbound = Json.parseToJsonElement(capturedUpstreamBody.get()!!).jsonObject
            val trades = outbound["hypotheticalTrades"]!!.toString()
            // The single candidate trade is forwarded verbatim.
            (trades.contains("\"instrumentId\":\"AAPL\"")) shouldBe true
            (trades.contains("\"side\":\"BUY\"")) shouldBe true
            (trades.contains("\"quantity\":\"50\"")) shouldBe true
        }
    }

    test("POST /api/v1/risk/pretrade-preview signs Δ Notional negative for SELL and echoes counterparty exposure delta when counterpartyId is present") {
        // SELL 200 of UST-10Y @ 99.50 against counterparty JPM:
        //   Δ Notional = -19900.00
        //   Δ counterparty exposure = -19900.00 (signed notional applied to JPM).
        val upstreamBody = whatIfUpstreamResponse(
            baseVar = "50000.00",
            hypotheticalVar = "49500.00",
            varChange = "-500.00",
            baseDeltaPerClass = listOf("RATES" to "1200.0"),
            hypotheticalDeltaPerClass = listOf("RATES" to "1000.0"),
        )
        val mockEngine = MockEngine {
            respond(
                content = upstreamBody,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val upstream = HttpClient(mockEngine) { install(ClientContentNegotiation) { json() } }
        val riskClient = HttpRiskServiceClient(upstream, "http://risk-orchestrator")

        testApplication {
            application { module(riskClient) }
            val response = client.post("/api/v1/risk/pretrade-preview") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "bookId":"port-1",
                      "instrumentId":"UST-10Y",
                      "assetClass":"RATES",
                      "side":"SELL",
                      "quantity":"200",
                      "priceAmount":"99.50",
                      "priceCurrency":"USD",
                      "instrumentType":"GOVT_BOND",
                      "counterpartyId":"JPM"
                    }
                    """.trimIndent(),
                )
            }

            response.status shouldBe HttpStatusCode.OK
            val body: JsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject

            body["varChange"]?.jsonPrimitive?.content shouldBe "-500.00"
            body["deltaChange"]?.jsonPrimitive?.content shouldBe "-200.000000"
            // SELL 200 * 99.50 = -19900.00.
            body["notionalChange"]?.jsonPrimitive?.content shouldBe "-19900.00"
            body["counterpartyId"]?.jsonPrimitive?.content shouldBe "JPM"
            body["counterpartyExposureChange"]?.jsonPrimitive?.content shouldBe "-19900.00"
        }
    }

    test("POST /api/v1/risk/pretrade-preview surfaces upstream 502 as a 502 to the UI") {
        val mockEngine = MockEngine {
            respond(
                content = """{"code":"UPSTREAM_DOWN","message":"risk-orchestrator unavailable"}""",
                status = HttpStatusCode.BadGateway,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val upstream = HttpClient(mockEngine) { install(ClientContentNegotiation) { json() } }
        val riskClient = HttpRiskServiceClient(upstream, "http://risk-orchestrator")

        testApplication {
            application { module(riskClient) }
            val response = client.post("/api/v1/risk/pretrade-preview") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "bookId":"port-1",
                      "instrumentId":"AAPL",
                      "assetClass":"EQUITY",
                      "side":"BUY",
                      "quantity":"50",
                      "priceAmount":"100.00",
                      "priceCurrency":"USD",
                      "instrumentType":"CASH_EQUITY"
                    }
                    """.trimIndent(),
                )
            }

            response.status shouldBe HttpStatusCode.BadGateway
        }
    }
})

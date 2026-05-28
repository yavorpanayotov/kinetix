package com.kinetix.gateway.contract

import com.kinetix.gateway.client.HttpRiskServiceClient
import com.kinetix.gateway.module
import com.kinetix.gateway.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Trader-review P0 regression: `Risk → Position Risk Breakdown` was rendering
 * the same Delta/Gamma/Vega on every row inside an asset class because the
 * orchestrator was projecting the per-asset-class aggregate onto each
 * position. This acceptance test pins the gateway contract: when the upstream
 * risk-orchestrator returns **distinct per-instrument Greeks**, the gateway
 * must preserve that distinctness end-to-end. No row may equal any other
 * row's Greeks within the same asset class.
 */
class PositionRiskBreakdownAcceptanceTest : FunSpec({

    // Per-instrument breakdown for a cash-equity book with two positions of
    // different share counts and distinct dollar deltas. Gamma and vega are
    // zero because cash equity has no convexity / vol sensitivity. Treasury
    // row is included so we can also assert cross-asset-class distinctness.
    val positionRiskJson = """
        [
          {
            "instrumentId":"AAPL",
            "assetClass":"EQUITY",
            "marketValue":"17000.00",
            "delta":"17000.000000",
            "gamma":"0.000000",
            "vega":"0.000000",
            "varContribution":"4200.00",
            "esContribution":"5300.00",
            "percentageOfTotal":"40.00"
          },
          {
            "instrumentId":"JNJ",
            "assetClass":"EQUITY",
            "marketValue":"8000.00",
            "delta":"8000.000000",
            "gamma":"0.000000",
            "vega":"0.000000",
            "varContribution":"2100.00",
            "esContribution":"2600.00",
            "percentageOfTotal":"20.00"
          },
          {
            "instrumentId":"UST-10Y",
            "assetClass":"FIXED_INCOME",
            "marketValue":"100000.00",
            "delta":"-712.50",
            "gamma":"0.000000",
            "vega":"0.000000",
            "varContribution":"4200.00",
            "esContribution":"5300.00",
            "percentageOfTotal":"40.00"
          }
        ]
    """.trimIndent()

    test("per-instrument Greeks distinct across rows in the same asset class — no row collapses to the asset-class aggregate") {
        val backend = BackendStubServer {
            get("/api/v1/risk/positions/multi-asset-book") {
                call.respond(Json.parseToJsonElement(positionRiskJson).jsonArray)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(riskClient) }
                val response = client.get("/api/v1/risk/positions/multi-asset-book")

                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
                body shouldHaveSize 3

                val byInstrument = body.associate { row ->
                    val obj = row.jsonObject
                    obj["instrumentId"]!!.jsonPrimitive.content to obj
                }

                val aapl = byInstrument["AAPL"]!!
                val jnj = byInstrument["JNJ"]!!
                val ust = byInstrument["UST-10Y"]!!

                // Two equity rows must NOT share a delta — that is the bug the
                // trader saw on kinetixrisk.ai.
                aapl["delta"]!!.jsonPrimitive.content shouldNotBe jnj["delta"]!!.jsonPrimitive.content

                // Cash equity gamma and vega must be zero; the previous bug
                // smeared aggregate gamma/vega across every equity row.
                aapl["gamma"]!!.jsonPrimitive.content shouldBe "0.000000"
                aapl["vega"]!!.jsonPrimitive.content shouldBe "0.000000"
                jnj["gamma"]!!.jsonPrimitive.content shouldBe "0.000000"
                jnj["vega"]!!.jsonPrimitive.content shouldBe "0.000000"

                // Equity vs Fixed Income deltas must also differ.
                aapl["delta"]!!.jsonPrimitive.content shouldNotBe ust["delta"]!!.jsonPrimitive.content
                jnj["delta"]!!.jsonPrimitive.content shouldNotBe ust["delta"]!!.jsonPrimitive.content
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("dollar delta for a cash equity scales with shares (per-instrument, not per-asset-class)") {
        val backend = BackendStubServer {
            get("/api/v1/risk/positions/equity-book") {
                call.respond(Json.parseToJsonElement(positionRiskJson).jsonArray)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(riskClient) }
                val response = client.get("/api/v1/risk/positions/equity-book")

                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray

                val byInstrument = body.associate { row ->
                    val obj = row.jsonObject
                    obj["instrumentId"]!!.jsonPrimitive.content to obj
                }

                // AAPL: 100 shares * $170 = $17,000 dollar delta.
                byInstrument["AAPL"]!!["delta"]!!.jsonPrimitive.content shouldBe "17000.000000"
                // JNJ: 50 shares * $160 = $8,000 dollar delta.
                byInstrument["JNJ"]!!["delta"]!!.jsonPrimitive.content shouldBe "8000.000000"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }
})

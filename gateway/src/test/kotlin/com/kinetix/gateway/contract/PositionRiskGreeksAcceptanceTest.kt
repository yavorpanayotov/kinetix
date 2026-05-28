package com.kinetix.gateway.contract

import com.kinetix.gateway.client.HttpRiskServiceClient
import com.kinetix.gateway.module
import com.kinetix.gateway.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeGreaterThan
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
 * Trader-review P0 #2 regression: `Risk → Position Risk Breakdown` rendered
 * `—` for every row's DV01, Theta, and Rho because the gateway response DTO
 * dropped those fields even when the orchestrator surfaced them.
 *
 * This acceptance test pins the gateway contract end-to-end. The orchestrator
 * stub returns a payload with per-instrument **DV01**, **Theta**, and **Rho**
 * populated for instruments where they apply, and zero for instruments where
 * they don't:
 *
 *  - **Treasury (`UST-10Y`, FIXED_INCOME):** DV01 > 0 (rates instrument), and
 *    Theta / Rho are zero/null because options-style time-decay and
 *    rate-sensitivity don't apply to a plain government bond — rate exposure
 *    flows through DV01, not Rho.
 *  - **Cash equity (`AAPL`, EQUITY):** DV01 = 0 (no rate sensitivity); Theta
 *    and Rho are also zero (no time-decay, no option-style rate sensitivity).
 *  - **Equity option (`AAPL-CALL`, DERIVATIVE):** Theta and Rho are non-zero
 *    because the Black-Scholes engine attributes them per-position; DV01 is
 *    zero.
 *
 * The bug surfaces as: (a) the gateway DTO drops `dv01`/`theta`/`rho` even
 * when the orchestrator sends them, or (b) the orchestrator never sends them.
 * Either failure leaves a trader staring at `—` in the per-row DV01 column.
 */
class PositionRiskGreeksAcceptanceTest : FunSpec({

    // Per-instrument breakdown with DV01/Theta/Rho populated where applicable.
    // A trader looking at this table needs:
    //   - DV01 on the Treasury so they can size a hedge,
    //   - Theta/Rho on the option so they can see time decay & rate exposure,
    //   - explicit zeros on rows where the sensitivity does not apply (so the
    //     em-dash means "missing", not "zero").
    val positionRiskJson = """
        [
          {
            "instrumentId":"AAPL",
            "assetClass":"EQUITY",
            "marketValue":"17000.00",
            "delta":"17000.000000",
            "gamma":"0.000000",
            "vega":"0.000000",
            "theta":"0.000000",
            "rho":"0.000000",
            "dv01":"0.000000",
            "varContribution":"4200.00",
            "esContribution":"5300.00",
            "percentageOfTotal":"40.00"
          },
          {
            "instrumentId":"UST-10Y",
            "assetClass":"FIXED_INCOME",
            "marketValue":"100000.00",
            "delta":"-712.50",
            "gamma":"0.000000",
            "vega":"0.000000",
            "theta":"0.000000",
            "rho":"0.000000",
            "dv01":"87.50",
            "varContribution":"4200.00",
            "esContribution":"5300.00",
            "percentageOfTotal":"40.00"
          },
          {
            "instrumentId":"AAPL-CALL",
            "assetClass":"DERIVATIVE",
            "marketValue":"3500.00",
            "delta":"52.40",
            "gamma":"0.024000",
            "vega":"18.500000",
            "theta":"-4.250000",
            "rho":"6.100000",
            "dv01":"0.000000",
            "varContribution":"1200.00",
            "esContribution":"1500.00",
            "percentageOfTotal":"20.00"
          }
        ]
    """.trimIndent()

    test("DV01 is non-zero for a Treasury row and explicitly zero for a cash-equity row") {
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

                val ust = byInstrument["UST-10Y"]!!
                val aapl = byInstrument["AAPL"]!!

                // Treasury must carry a non-zero DV01 — a rates trader sizes
                // hedges off this number. An em-dash here is the bug.
                val ustDv01String = ust["dv01"]!!.jsonPrimitive.content
                ustDv01String shouldNotBe null
                ustDv01String.toDouble() shouldBeGreaterThan 0.0

                // Cash equity has no rate sensitivity. DV01 must be present
                // and explicitly zero (so the cell renders 0 / formatter
                // decides whether to em-dash by asset class, not by absence).
                aapl["dv01"]!!.jsonPrimitive.content.toDouble() shouldBe 0.0
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("Theta and Rho on an equity option are non-zero and survive the gateway projection") {
        val backend = BackendStubServer {
            get("/api/v1/risk/positions/options-book") {
                call.respond(Json.parseToJsonElement(positionRiskJson).jsonArray)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(riskClient) }
                val response = client.get("/api/v1/risk/positions/options-book")

                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray

                val byInstrument = body.associate { row ->
                    val obj = row.jsonObject
                    obj["instrumentId"]!!.jsonPrimitive.content to obj
                }

                val option = byInstrument["AAPL-CALL"]!!

                // Theta should be a non-zero negative number for a long
                // option (time decay). The bug surfaced as theta = null /
                // missing from the gateway DTO so the UI showed `—`.
                option["theta"]!!.jsonPrimitive.content.toDouble() shouldBe -4.25

                // Rho should also be present and non-zero for an option.
                option["rho"]!!.jsonPrimitive.content.toDouble() shouldBe 6.10
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("DV01 / Theta / Rho fields are preserved end-to-end without renaming or loss of precision") {
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

                // Every row must carry the new fields — even if the value is
                // zero — so the UI never needs to fall back to "—" for an
                // expected-zero sensitivity.
                body.forEach { row ->
                    val obj = row.jsonObject
                    obj.keys shouldNotBe null
                    obj["dv01"] shouldNotBe null
                    obj["theta"] shouldNotBe null
                    obj["rho"] shouldNotBe null
                }

                val ust = body.first { it.jsonObject["instrumentId"]!!.jsonPrimitive.content == "UST-10Y" }.jsonObject
                ust["dv01"]!!.jsonPrimitive.content shouldBe "87.50"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }
})

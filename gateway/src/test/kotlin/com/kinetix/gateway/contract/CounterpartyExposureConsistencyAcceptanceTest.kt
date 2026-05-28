package com.kinetix.gateway.contract

import com.kinetix.gateway.client.HttpPositionServiceClient
import com.kinetix.gateway.client.HttpRiskServiceClient
import com.kinetix.gateway.module
import com.kinetix.gateway.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.testing.*
import kotlinx.serialization.json.*

/**
 * Trader-review P0 #6 regression — `plans/ui-trader-review.md`.
 *
 * Two surfaces on the live demo showed counterparty exposure and disagreed
 * on count, sign, and magnitude:
 *
 *  - `Risk → Counterparty Exposure` widget (Risk tab tile) aggregates trade
 *    blotter rows client-side and rendered 10 counterparties with mixed-sign
 *    net notionals (`CP-UBS -$3.0M`, `CP-DB +$3.0M`, …).
 *  - `Counterparty Risk` dedicated tab calls `/api/v1/counterparty-risk` and
 *    rendered 6 counterparties with all-positive net exposures (`CP-JPM
 *    $6.5M`, `CP-CITI $5.3M`, …). Names like CP-WFC, CP-SOCG, CP-HAND, CP-SAN
 *    were missing from this view entirely.
 *
 * The two surfaces compute genuinely different metrics — net trade notional
 * (long/short skew) vs net credit exposure (post-netting, non-negative) —
 * but they MUST agree on the counterparty SET. If a counterparty has trades
 * with the firm, it should appear in both views; if it does not, it should
 * appear in neither. Today the risk-service's `CounterpartyExposureSnapshot`
 * stream is populated by a separate process from the trade booking flow, so
 * the two universes drift apart.
 *
 * The fix (P0 #6): the gateway's `/api/v1/counterparty-risk` endpoint must
 * merge the trade-derived counterparty set (from position-service) with the
 * credit-risk snapshot set (from risk-service). Any counterparty that has
 * trades but no snapshot gets a zero-PFE / null-CVA placeholder row, so the
 * two surfaces always agree on names.
 *
 * Acceptance test follows the in-JVM Netty-backed stub pattern from
 * `RiskDashboardReconciliationAcceptanceTest` and CLAUDE.md ("Project
 * Conventions"): the gateway's real `HttpPositionServiceClient` and
 * `HttpRiskServiceClient` call real Ktor servers, so serialisation,
 * content-negotiation, and HTTP wire behaviour are all exercised.
 */
class CounterpartyExposureConsistencyAcceptanceTest : FunSpec({

    // ------------------------------------------------------------------
    // Fixture book: trades across 4 counterparties (CP-JPM, CP-CITI,
    // CP-UBS, CP-BARC). The Risk-tab tile would aggregate these from the
    // /trades page endpoint; the dedicated tab calls /counterparty-risk.
    // Both surfaces MUST see {CP-JPM, CP-CITI, CP-UBS, CP-BARC}.
    // ------------------------------------------------------------------

    val bookId = "balanced-income"
    val tradesJson = """
        [
          {
            "tradeId":"t-1","bookId":"$bookId","instrumentId":"AAPL",
            "assetClass":"EQUITY","side":"BUY","quantity":"100",
            "price":{"amount":"150.00","currency":"USD"},
            "tradedAt":"2026-05-28T10:00:00Z","instrumentType":"CASH_EQUITY",
            "counterpartyId":"CP-JPM"
          },
          {
            "tradeId":"t-2","bookId":"$bookId","instrumentId":"MSFT",
            "assetClass":"EQUITY","side":"SELL","quantity":"200",
            "price":{"amount":"300.00","currency":"USD"},
            "tradedAt":"2026-05-28T10:01:00Z","instrumentType":"CASH_EQUITY",
            "counterpartyId":"CP-CITI"
          },
          {
            "tradeId":"t-3","bookId":"$bookId","instrumentId":"GOOG",
            "assetClass":"EQUITY","side":"BUY","quantity":"50",
            "price":{"amount":"2800.00","currency":"USD"},
            "tradedAt":"2026-05-28T10:02:00Z","instrumentType":"CASH_EQUITY",
            "counterpartyId":"CP-UBS"
          },
          {
            "tradeId":"t-4","bookId":"$bookId","instrumentId":"UST-10Y",
            "assetClass":"FIXED_INCOME","side":"BUY","quantity":"1000",
            "price":{"amount":"98.50","currency":"USD"},
            "tradedAt":"2026-05-28T10:03:00Z","instrumentType":"GOVERNMENT_BOND",
            "counterpartyId":"CP-BARC"
          }
        ]
    """.trimIndent()

    val tradesPageJson = """
        {
          "items":${tradesJson},
          "total":4,
          "offset":0,
          "limit":100,
          "hasMore":false
        }
    """.trimIndent()

    // ------------------------------------------------------------------
    // The bug: the risk-service snapshot set is a STRICT SUBSET of the
    // trade-derived set — CP-BARC has trades but never made it into the
    // exposure snapshot stream. A real production demo had several
    // counterparties in this state.
    // ------------------------------------------------------------------
    val riskCounterpartySnapshotsJson = """
        [
          {
            "counterpartyId":"CP-JPM",
            "calculatedAt":"2026-05-28T10:25:00Z",
            "currentNetExposure":6500000.0,
            "peakPfe":7200000.0,
            "cva":45000.0,
            "cvaEstimated":false,
            "currency":"USD",
            "pfeProfile":[]
          },
          {
            "counterpartyId":"CP-CITI",
            "calculatedAt":"2026-05-28T10:25:00Z",
            "currentNetExposure":5300000.0,
            "peakPfe":5900000.0,
            "cva":36000.0,
            "cvaEstimated":false,
            "currency":"USD",
            "pfeProfile":[]
          },
          {
            "counterpartyId":"CP-UBS",
            "calculatedAt":"2026-05-28T10:25:00Z",
            "currentNetExposure":4100000.0,
            "peakPfe":4600000.0,
            "cva":28000.0,
            "cvaEstimated":false,
            "currency":"USD",
            "pfeProfile":[]
          }
        ]
    """.trimIndent()

    /**
     * Extracts the counterparty IDs from a paged trades response by walking
     * `items[*].counterpartyId`. Mirrors the client-side aggregation done by
     * the Risk-tab CounterpartyExposureTile (excluding trades without a
     * counterpartyId, same as the tile's `if (!cp) continue`).
     */
    fun counterpartiesFromTrades(body: JsonObject): Set<String> =
        body["items"]!!.jsonArray
            .mapNotNull { it.jsonObject["counterpartyId"]?.jsonPrimitive?.contentOrNull }
            .filter { it.isNotBlank() }
            .toSet()

    /**
     * Extracts the counterparty IDs from a `/api/v1/counterparty-risk`
     * response (a JsonArray of CounterpartyExposureDto-shaped objects).
     */
    fun counterpartiesFromSnapshots(body: JsonArray): Set<String> =
        body.mapNotNull { it.jsonObject["counterpartyId"]?.jsonPrimitive?.contentOrNull }.toSet()

    test("Risk tab tile and Counterparty Risk tab agree on the counterparty name set for the same fixture book") {
        val riskBackend = BackendStubServer {
            get("/api/v1/counterparty-risk/") {
                call.respond(Json.parseToJsonElement(riskCounterpartySnapshotsJson).jsonArray)
            }
        }
        val positionBackend = BackendStubServer {
            get("/api/v1/books") {
                call.respond(
                    Json.parseToJsonElement("""[{"bookId":"$bookId"}]""").jsonArray,
                )
            }
            get("/api/v1/books/$bookId/trades/page") {
                call.respond(Json.parseToJsonElement(tradesPageJson).jsonObject)
            }
            get("/api/v1/books/$bookId/trades") {
                call.respond(Json.parseToJsonElement(tradesJson).jsonArray)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, riskBackend.baseUrl)
            val positionClient = HttpPositionServiceClient(httpClient, positionBackend.baseUrl)

            testApplication {
                application { module(positionClient, riskClient) }

                // 1. Risk-tab tile data path — trade-derived counterparty set.
                val tradesResp = client.get("/api/v1/books/$bookId/trades/page?offset=0&limit=200")
                tradesResp.status shouldBe HttpStatusCode.OK
                val tradesBody = Json.parseToJsonElement(tradesResp.bodyAsText()).jsonObject
                val tileCounterparties = counterpartiesFromTrades(tradesBody)

                // 2. Dedicated Counterparty Risk tab data path — risk-service snapshots.
                val snapshotsResp = client.get("/api/v1/counterparty-risk")
                snapshotsResp.status shouldBe HttpStatusCode.OK
                val snapshotsBody = Json.parseToJsonElement(snapshotsResp.bodyAsText()).jsonArray
                val tabCounterparties = counterpartiesFromSnapshots(snapshotsBody)

                // The fixture deliberately puts every trade-derived counterparty
                // {CP-JPM, CP-CITI, CP-UBS, CP-BARC} into the position-service
                // response, but only 3 of those 4 into the risk-service snapshot
                // stream. The trader-review bug: CP-BARC has trades booked but
                // disappears from the dedicated tab because no snapshot exists.
                //
                // The gateway must merge — every counterparty seen in the trade
                // history must appear in the /counterparty-risk response, with
                // zero/null risk metrics if no snapshot is available.
                tileCounterparties shouldBe setOf("CP-JPM", "CP-CITI", "CP-UBS", "CP-BARC")
                tabCounterparties shouldBe tileCounterparties
            }
        } finally {
            httpClient.close()
            riskBackend.close()
            positionBackend.close()
        }
    }

    test("Counterparty Risk response includes a placeholder row for counterparties seen in trades but missing from the snapshot stream") {
        val riskBackend = BackendStubServer {
            get("/api/v1/counterparty-risk/") {
                call.respond(Json.parseToJsonElement(riskCounterpartySnapshotsJson).jsonArray)
            }
        }
        val positionBackend = BackendStubServer {
            get("/api/v1/books") {
                call.respond(Json.parseToJsonElement("""[{"bookId":"$bookId"}]""").jsonArray)
            }
            get("/api/v1/books/$bookId/trades/page") {
                call.respond(Json.parseToJsonElement(tradesPageJson).jsonObject)
            }
            get("/api/v1/books/$bookId/trades") {
                call.respond(Json.parseToJsonElement(tradesJson).jsonArray)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, riskBackend.baseUrl)
            val positionClient = HttpPositionServiceClient(httpClient, positionBackend.baseUrl)

            testApplication {
                application { module(positionClient, riskClient) }

                val resp = client.get("/api/v1/counterparty-risk")
                resp.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(resp.bodyAsText()).jsonArray
                val ids = body.map { it.jsonObject["counterpartyId"]!!.jsonPrimitive.content }
                ids shouldContainAll listOf("CP-JPM", "CP-CITI", "CP-UBS", "CP-BARC")

                // The trade-only counterparty must be a valid row with sensible
                // defaults — net exposure / peak PFE / CVA are unknown for a
                // counterparty that has no snapshot yet, so the response should
                // expose those fields as null (CVA is already nullable in the
                // existing DTO; net exposure and peak PFE must also tolerate
                // null on the wire so the UI knows the snapshot is missing).
                val barcRow = body.single { it.jsonObject["counterpartyId"]!!.jsonPrimitive.content == "CP-BARC" }
                    .jsonObject

                // currentNetExposure / peakPfe MAY be 0.0 OR null — either is
                // acceptable. What is NOT acceptable is the row being absent
                // (the current production bug) or the row carrying made-up
                // non-zero risk numbers that the orchestrator never produced.
                val rawNetExposure = barcRow["currentNetExposure"]
                val rawPeakPfe = barcRow["peakPfe"]
                val netExposureSentinel =
                    rawNetExposure is JsonNull ||
                        rawNetExposure?.jsonPrimitive?.content?.toDoubleOrNull() == 0.0
                val peakPfeSentinel =
                    rawPeakPfe is JsonNull ||
                        rawPeakPfe?.jsonPrimitive?.content?.toDoubleOrNull() == 0.0
                netExposureSentinel shouldBe true
                peakPfeSentinel shouldBe true
            }
        } finally {
            httpClient.close()
            riskBackend.close()
            positionBackend.close()
        }
    }

    test("Counterparty Risk response preserves all snapshot fields for counterparties that have both trades and a snapshot") {
        // Regression: the merge step must not damage the existing snapshot
        // payload for counterparties that are already represented in the
        // risk-service stream. CP-JPM has a fixture snapshot with non-zero
        // currentNetExposure, peakPfe, and cva — all three must pass through
        // the merge unchanged.
        val riskBackend = BackendStubServer {
            get("/api/v1/counterparty-risk/") {
                call.respond(Json.parseToJsonElement(riskCounterpartySnapshotsJson).jsonArray)
            }
        }
        val positionBackend = BackendStubServer {
            get("/api/v1/books") {
                call.respond(Json.parseToJsonElement("""[{"bookId":"$bookId"}]""").jsonArray)
            }
            get("/api/v1/books/$bookId/trades/page") {
                call.respond(Json.parseToJsonElement(tradesPageJson).jsonObject)
            }
            get("/api/v1/books/$bookId/trades") {
                call.respond(Json.parseToJsonElement(tradesJson).jsonArray)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, riskBackend.baseUrl)
            val positionClient = HttpPositionServiceClient(httpClient, positionBackend.baseUrl)

            testApplication {
                application { module(positionClient, riskClient) }

                val resp = client.get("/api/v1/counterparty-risk")
                resp.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(resp.bodyAsText()).jsonArray
                val jpmRow = body.single { it.jsonObject["counterpartyId"]!!.jsonPrimitive.content == "CP-JPM" }
                    .jsonObject

                jpmRow["currentNetExposure"]!!.jsonPrimitive.content.toDouble() shouldBe 6500000.0
                jpmRow["peakPfe"]!!.jsonPrimitive.content.toDouble() shouldBe 7200000.0
                jpmRow["cva"]!!.jsonPrimitive.content.toDouble() shouldBe 45000.0
            }
        } finally {
            httpClient.close()
            riskBackend.close()
            positionBackend.close()
        }
    }
})

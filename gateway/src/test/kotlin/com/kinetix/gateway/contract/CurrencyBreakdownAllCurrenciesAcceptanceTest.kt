package com.kinetix.gateway.contract

import com.kinetix.gateway.client.HttpPositionServiceClient
import com.kinetix.gateway.routes.hierarchyRoutes
import com.kinetix.gateway.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.collections.shouldContainAll
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Trader-review P1 regression: the Firm Summary "Currency Breakdown" card
 * advertises USD/EUR/GBP/JPY chips but the gateway aggregation was rendering
 * only USD + EUR rows, leading a trader to assume zero GBP / JPY exposure
 * even when positions existed in those currencies.
 *
 * This pins the gateway contract for `/api/v1/firm/summary`:
 *   1. Every currency that has non-zero local-value exposure across the
 *      firm's books appears in `currencyBreakdown` — there is no hard-coded
 *      USD/EUR allow-list.
 *   2. Currencies whose per-book exposures net to zero (e.g. an FX hedge
 *      that closes flat) are suppressed so the card stays compact.
 *
 * The fixture deliberately mixes:
 *   - book-usd-eur     → USD + EUR non-zero
 *   - book-gbp-jpy     → GBP + JPY non-zero
 *   - book-hedge       → CHF with a zero local value (offsetting positions)
 * so we can assert both "include every non-zero currency" and "drop zero
 * exposures" in a single end-to-end call.
 */
class CurrencyBreakdownAllCurrenciesAcceptanceTest : FunSpec({

    test("GET /api/v1/firm/summary surfaces every non-zero currency exposure across all books") {
        val backend = BackendStubServer {
            get("/api/v1/books") {
                call.respond(
                    buildJsonArray {
                        add(buildJsonObject { put("bookId", "book-usd-eur") })
                        add(buildJsonObject { put("bookId", "book-gbp-jpy") })
                        add(buildJsonObject { put("bookId", "book-hedge") })
                    },
                )
            }
            get("/api/v1/books/book-usd-eur/summary") {
                call.respond(
                    buildJsonObject {
                        put("bookId", "book-usd-eur")
                        put("baseCurrency", "USD")
                        put("totalNav", buildJsonObject { put("amount", "3000.00"); put("currency", "USD") })
                        put("totalUnrealizedPnl", buildJsonObject { put("amount", "100.00"); put("currency", "USD") })
                        put(
                            "currencyBreakdown",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("currency", "USD")
                                        put("localValue", buildJsonObject { put("amount", "1000.00"); put("currency", "USD") })
                                        put("baseValue", buildJsonObject { put("amount", "1000.00"); put("currency", "USD") })
                                        put("fxRate", "1.0000")
                                    },
                                )
                                add(
                                    buildJsonObject {
                                        put("currency", "EUR")
                                        put("localValue", buildJsonObject { put("amount", "1800.00"); put("currency", "EUR") })
                                        put("baseValue", buildJsonObject { put("amount", "2000.00"); put("currency", "USD") })
                                        put("fxRate", "1.1111")
                                    },
                                )
                            },
                        )
                    },
                )
            }
            get("/api/v1/books/book-gbp-jpy/summary") {
                call.respond(
                    buildJsonObject {
                        put("bookId", "book-gbp-jpy")
                        put("baseCurrency", "USD")
                        put("totalNav", buildJsonObject { put("amount", "5050.00"); put("currency", "USD") })
                        put("totalUnrealizedPnl", buildJsonObject { put("amount", "-25.00"); put("currency", "USD") })
                        put(
                            "currencyBreakdown",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("currency", "GBP")
                                        put("localValue", buildJsonObject { put("amount", "4000.00"); put("currency", "GBP") })
                                        put("baseValue", buildJsonObject { put("amount", "5000.00"); put("currency", "USD") })
                                        put("fxRate", "1.2500")
                                    },
                                )
                                add(
                                    buildJsonObject {
                                        put("currency", "JPY")
                                        put("localValue", buildJsonObject { put("amount", "7462.69"); put("currency", "JPY") })
                                        put("baseValue", buildJsonObject { put("amount", "50.00"); put("currency", "USD") })
                                        put("fxRate", "0.0067")
                                    },
                                )
                            },
                        )
                    },
                )
            }
            get("/api/v1/books/book-hedge/summary") {
                // FX hedge book whose CHF leg nets to zero localValue. A real
                // book sometimes ends a session flat — those currencies should
                // NOT appear as $0 rows in the firm-level card.
                call.respond(
                    buildJsonObject {
                        put("bookId", "book-hedge")
                        put("baseCurrency", "USD")
                        put("totalNav", buildJsonObject { put("amount", "0.00"); put("currency", "USD") })
                        put("totalUnrealizedPnl", buildJsonObject { put("amount", "0.00"); put("currency", "USD") })
                        put(
                            "currencyBreakdown",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("currency", "CHF")
                                        put("localValue", buildJsonObject { put("amount", "0.00"); put("currency", "CHF") })
                                        put("baseValue", buildJsonObject { put("amount", "0.00"); put("currency", "USD") })
                                        put("fxRate", "1.1300")
                                    },
                                )
                            },
                        )
                    },
                )
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            testApplication {
                val positionClient = HttpPositionServiceClient(httpClient, backend.baseUrl)
                application {
                    install(ContentNegotiation) { json() }
                    routing { hierarchyRoutes(httpClient, backend.baseUrl, positionClient) }
                }
                val response = client.get("/api/v1/firm/summary")
                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val breakdown = body["currencyBreakdown"]!!.jsonArray
                val currencies = breakdown.map { it.jsonObject["currency"]!!.jsonPrimitive.content }

                // 1. Every non-zero currency must be present — no hard-coded USD/EUR allow-list.
                currencies.toSet() shouldContainAll setOf("USD", "EUR", "GBP", "JPY")

                // 2. Zero-exposure currencies (CHF here) are suppressed so the
                //    card stays compact instead of advertising fake $0 rows.
                currencies shouldNotContain "CHF"

                // 3. Sanity-check that the GBP/JPY rows carry through the
                //    upstream local + base values intact (not collapsed to
                //    zero somewhere in the fold).
                val gbp = breakdown.first { it.jsonObject["currency"]!!.jsonPrimitive.content == "GBP" }.jsonObject
                gbp["localValue"]!!.jsonObject["amount"]!!.jsonPrimitive.content shouldBe "4000.00"
                gbp["baseValue"]!!.jsonObject["amount"]!!.jsonPrimitive.content shouldBe "5000.00"
                val jpy = breakdown.first { it.jsonObject["currency"]!!.jsonPrimitive.content == "JPY" }.jsonObject
                jpy["localValue"]!!.jsonObject["amount"]!!.jsonPrimitive.content shouldBe "7462.69"
                jpy["baseValue"]!!.jsonObject["amount"]!!.jsonPrimitive.content shouldBe "50.00"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }
})

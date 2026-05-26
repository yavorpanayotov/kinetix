package com.kinetix.demo.client

import com.kinetix.demo.client.dtos.StrategyTradeRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest

class PositionServiceClientTest : FunSpec({

    fun mockHttpClient(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpClient = HttpClient(MockEngine { request -> handler(request) })

    fun sampleRequest(
        tradeId: String? = null,
        userId: String? = null,
        userRole: String? = null,
    ) = StrategyTradeRequest(
        tradeId = tradeId,
        instrumentId = "AAPL",
        assetClass = "EQUITY",
        side = "BUY",
        quantity = "100",
        priceAmount = "175.25",
        priceCurrency = "USD",
        tradedAt = "2026-05-18T10:15:30Z",
        instrumentType = "STOCK",
        userId = userId,
        userRole = userRole,
    )

    test("bookTrade posts to the expected URL with JSON body and returns the server tradeId") {
        var capturedUrl: String? = null
        var capturedMethod: HttpMethod? = null
        var capturedBody: String? = null
        var capturedContentType: String? = null

        val client = PositionServiceHttpClient(
            httpClient = mockHttpClient { request ->
                capturedUrl = request.url.toString()
                capturedMethod = request.method
                capturedContentType = request.body.contentType?.toString()
                capturedBody = String(request.body.toByteArray())
                respond(
                    content = """{"tradeId":"server-generated-uuid-1","bookId":"BOOK-EQ-01",
                                  "instrumentId":"AAPL","side":"BUY","quantity":"100",
                                  "strategyId":"STRAT-01"}""".trimIndent(),
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
            baseUrl = "http://position",
        )

        runTest {
            val tradeId = client.bookTrade(
                bookId = "BOOK-EQ-01",
                strategyId = "STRAT-01",
                request = sampleRequest(),
            )
            tradeId shouldBe "server-generated-uuid-1"
        }

        capturedUrl shouldBe "http://position/api/v1/books/BOOK-EQ-01/strategies/STRAT-01/trades"
        capturedMethod shouldBe HttpMethod.Post
        capturedContentType!! shouldContain "application/json"
        val body = capturedBody!!
        body shouldContain "\"instrumentId\":\"AAPL\""
        body shouldContain "\"assetClass\":\"EQUITY\""
        body shouldContain "\"side\":\"BUY\""
        body shouldContain "\"quantity\":\"100\""
        body shouldContain "\"priceAmount\":\"175.25\""
        body shouldContain "\"priceCurrency\":\"USD\""
        body shouldContain "\"tradedAt\":\"2026-05-18T10:15:30Z\""
        body shouldContain "\"instrumentType\":\"STOCK\""
    }

    test("bookTrade omits null optional fields (tradeId, userId, userRole) from the JSON body") {
        var capturedBody: String? = null
        val client = PositionServiceHttpClient(
            httpClient = mockHttpClient { request ->
                capturedBody = String(request.body.toByteArray())
                respond(
                    content = "{\"tradeId\":\"generated-1\"}",
                    status = HttpStatusCode.Created,
                )
            },
            baseUrl = "http://position",
        )

        runTest {
            client.bookTrade(
                bookId = "BOOK-EQ-01",
                strategyId = "STRAT-01",
                request = sampleRequest(tradeId = null, userId = null, userRole = null),
            )
        }

        val body = capturedBody!!
        body shouldNotContain "\"tradeId\""
        body shouldNotContain "\"userId\""
        body shouldNotContain "\"userRole\""
    }

    test("bookTrade includes optional fields when they are provided") {
        var capturedBody: String? = null
        val client = PositionServiceHttpClient(
            httpClient = mockHttpClient { request ->
                capturedBody = String(request.body.toByteArray())
                respond(
                    content = "{\"tradeId\":\"caller-supplied-id\"}",
                    status = HttpStatusCode.Created,
                )
            },
            baseUrl = "http://position",
        )

        runTest {
            val tradeId = client.bookTrade(
                bookId = "BOOK-EQ-01",
                strategyId = "STRAT-01",
                request = sampleRequest(
                    tradeId = "caller-supplied-id",
                    userId = "trader-1",
                    userRole = "TRADER",
                ),
            )
            tradeId shouldBe "caller-supplied-id"
        }

        val body = capturedBody!!
        body shouldContain "\"tradeId\":\"caller-supplied-id\""
        body shouldContain "\"userId\":\"trader-1\""
        body shouldContain "\"userRole\":\"TRADER\""
    }

    test("bookTrade throws IllegalStateException on 5xx") {
        val client = PositionServiceHttpClient(
            httpClient = mockHttpClient {
                respond(content = "boom", status = HttpStatusCode.InternalServerError)
            },
            baseUrl = "http://position",
        )

        runTest {
            val thrown = shouldThrow<IllegalStateException> {
                client.bookTrade(
                    bookId = "BOOK-EQ-01",
                    strategyId = "STRAT-01",
                    request = sampleRequest(),
                )
            }
            thrown.message!! shouldContain "500"
            thrown.message!! shouldContain "POST"
            thrown.message!! shouldContain "boom"
        }
    }

    test("bookTrade throws IllegalStateException on 4xx so validation failures surface") {
        val client = PositionServiceHttpClient(
            httpClient = mockHttpClient {
                respond(
                    content = "Trade quantity must be positive",
                    status = HttpStatusCode.BadRequest,
                )
            },
            baseUrl = "http://position",
        )

        runTest {
            val thrown = shouldThrow<IllegalStateException> {
                client.bookTrade(
                    bookId = "BOOK-EQ-01",
                    strategyId = "STRAT-01",
                    request = sampleRequest(),
                )
            }
            thrown.message!! shouldContain "400"
            thrown.message!! shouldContain "POST"
            thrown.message!! shouldContain "Trade quantity must be positive"
        }
    }

    test("listStrategies GETs the books endpoint and returns the strategy ids") {
        var capturedUrl: String? = null
        var capturedMethod: HttpMethod? = null
        val client = PositionServiceHttpClient(
            httpClient = mockHttpClient { request ->
                capturedUrl = request.url.toString()
                capturedMethod = request.method
                respond(
                    content = """
                        [
                          {"strategyId":"equity-growth-core","bookId":"equity-growth","strategyType":"CUSTOM","name":"core","createdAt":"2026-05-18T00:00:00Z"},
                          {"strategyId":"equity-growth-satellite","bookId":"equity-growth","strategyType":"CUSTOM","name":"satellite","createdAt":"2026-05-18T00:00:00Z"}
                        ]
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
            baseUrl = "http://position",
        )

        runTest {
            val ids = client.listStrategies("equity-growth")
            ids shouldBe listOf("equity-growth-core", "equity-growth-satellite")
        }

        capturedUrl shouldBe "http://position/api/v1/books/equity-growth/strategies"
        capturedMethod shouldBe HttpMethod.Get
    }

    test("listStrategies returns an empty list when the server returns []") {
        val client = PositionServiceHttpClient(
            httpClient = mockHttpClient {
                respond(
                    content = "[]",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
            baseUrl = "http://position",
        )

        runTest {
            client.listStrategies("equity-growth") shouldBe emptyList()
        }
    }

    test("listStrategies throws IllegalStateException on a non-2xx response") {
        val client = PositionServiceHttpClient(
            httpClient = mockHttpClient {
                respond(content = "boom", status = HttpStatusCode.InternalServerError)
            },
            baseUrl = "http://position",
        )

        runTest {
            val thrown = shouldThrow<IllegalStateException> {
                client.listStrategies("equity-growth")
            }
            thrown.message!! shouldContain "500"
            thrown.message!! shouldContain "GET"
        }
    }
})

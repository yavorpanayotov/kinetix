package com.kinetix.demo.client

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import java.math.BigDecimal
import java.time.LocalDate

class RiskOrchestratorClientTest : FunSpec({

    fun mockHttpClient(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpClient = HttpClient(MockEngine { request -> handler(request) })

    test("readBookExposure parses varValue and bookId from the hierarchy response") {
        val client = RiskOrchestratorHttpClient(
            httpClient = mockHttpClient { request ->
                request.method shouldBe HttpMethod.Get
                request.url.toString() shouldBe "http://orchestrator/api/v1/risk/hierarchy/BOOK/BOOK-EQ-01"
                respond(
                    content = """
                        {
                          "level":"BOOK",
                          "entityId":"BOOK-EQ-01",
                          "entityName":"Equity Book 01",
                          "parentId":"DESK-EQ",
                          "varValue":"1234567.89",
                          "expectedShortfall":"1500000.00",
                          "pnlToday":null,
                          "limitUtilisation":null,
                          "marginalVar":null,
                          "incrementalVar":null,
                          "childCount":0,
                          "isPartial":false,
                          "generatedAt":"2026-05-18T06:05:00Z"
                        }
                    """.trimIndent(),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
            baseUrl = "http://orchestrator",
        )

        runTest {
            val snapshot = client.readBookExposure("BOOK-EQ-01")
            snapshot.bookId shouldBe "BOOK-EQ-01"
            snapshot.varValue.compareTo(BigDecimal("1234567.89")) shouldBe 0
            snapshot.absoluteDelta shouldBe null
        }
    }

    test("readBookExposure throws IllegalStateException on 5xx") {
        val client = RiskOrchestratorHttpClient(
            httpClient = mockHttpClient {
                respond(content = "boom", status = HttpStatusCode.InternalServerError)
            },
            baseUrl = "http://orchestrator",
        )

        runTest {
            val thrown = shouldThrow<IllegalStateException> {
                client.readBookExposure("BOOK-EQ-01")
            }
            thrown.message!! shouldContain "500"
            thrown.message!! shouldContain "GET"
            thrown.message!! shouldContain "boom"
        }
    }

    test("seedLimit(VAR_95) POSTs the expected URL and body shape") {
        var capturedUrl: String? = null
        var capturedMethod: HttpMethod? = null
        var capturedBody: String? = null
        var capturedContentType: String? = null

        val client = RiskOrchestratorHttpClient(
            httpClient = mockHttpClient { request ->
                capturedUrl = request.url.toString()
                capturedMethod = request.method
                capturedContentType = request.body.contentType?.toString()
                capturedBody = String(request.body.toByteArray())
                respond(content = "{\"id\":\"any\"}", status = HttpStatusCode.Created)
            },
            baseUrl = "http://orchestrator",
        )

        runTest {
            client.seedLimit("BOOK-EQ-01", LimitType.VAR_95, BigDecimal("987654.32"))
        }

        capturedUrl shouldBe "http://orchestrator/api/v1/risk/budgets"
        capturedMethod shouldBe HttpMethod.Post
        capturedContentType!! shouldContain "application/json"
        val body = capturedBody!!
        body shouldContain "\"budgetType\":\"VAR_95\""
        body shouldContain "\"entityId\":\"BOOK-EQ-01\""
        body shouldContain "\"entityLevel\":\"BOOK\""
        body shouldContain "\"budgetPeriod\":\"DAILY\""
        body shouldContain "\"budgetAmount\":\"987654.32\""
        body shouldContain "\"allocatedBy\":\"demo-orchestrator\""
    }

    test("seedLimit(DELTA_ABS) maps to budgetType=DELTA_ABS") {
        var capturedBody: String? = null
        val client = RiskOrchestratorHttpClient(
            httpClient = mockHttpClient { request ->
                capturedBody = String(request.body.toByteArray())
                respond(content = "{}", status = HttpStatusCode.Created)
            },
            baseUrl = "http://orchestrator",
        )

        runTest {
            client.seedLimit("BOOK-FX-02", LimitType.DELTA_ABS, BigDecimal("250000"))
        }

        capturedBody!! shouldContain "\"budgetType\":\"DELTA_ABS\""
        capturedBody!! shouldContain "\"entityId\":\"BOOK-FX-02\""
        capturedBody!! shouldContain "\"budgetAmount\":\"250000\""
    }

    test("eodTimeline parses entries with the requested date window") {
        val client = RiskOrchestratorHttpClient(
            httpClient = mockHttpClient { request ->
                request.method shouldBe HttpMethod.Get
                request.url.toString() shouldBe
                    "http://orchestrator/api/v1/risk/eod-timeline/BOOK-EQ-01?from=2026-04-17&to=2026-05-18"
                respond(
                    content = """
                        {
                          "bookId":"BOOK-EQ-01",
                          "from":"2026-04-17",
                          "to":"2026-05-18",
                          "entries":[
                            {"valuationDate":"2026-04-17","jobId":"j1","varValue":1000.0,"pvValue":50000.0},
                            {"valuationDate":"2026-04-18","jobId":"j2","varValue":1100.0,"pvValue":50250.0,"delta":1.2}
                          ]
                        }
                    """.trimIndent(),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
            baseUrl = "http://orchestrator",
        )

        runTest {
            val response = client.eodTimeline(
                bookId = "BOOK-EQ-01",
                from = LocalDate.parse("2026-04-17"),
                to = LocalDate.parse("2026-05-18"),
            )
            response.bookId shouldBe "BOOK-EQ-01"
            response.from shouldBe "2026-04-17"
            response.to shouldBe "2026-05-18"
            response.entries.size shouldBe 2
            response.entries[0].valuationDate shouldBe "2026-04-17"
            response.entries[0].varValue shouldBe 1000.0
            response.entries[0].pvValue shouldBe 50000.0
            response.entries[1].varValue shouldBe 1100.0
            response.entries[1].pvValue shouldBe 50250.0
        }
    }

    test("eodTimeline throws IllegalStateException on 5xx") {
        val client = RiskOrchestratorHttpClient(
            httpClient = mockHttpClient {
                respond(content = "timeline down", status = HttpStatusCode.InternalServerError)
            },
            baseUrl = "http://orchestrator",
        )

        runTest {
            val thrown = shouldThrow<IllegalStateException> {
                client.eodTimeline(
                    bookId = "BOOK-EQ-01",
                    from = LocalDate.parse("2026-04-17"),
                    to = LocalDate.parse("2026-05-18"),
                )
            }
            thrown.message!! shouldContain "500"
            thrown.message!! shouldContain "GET"
            thrown.message!! shouldContain "timeline down"
        }
    }

    test("seedLimit throws IllegalStateException on 4xx") {
        val client = RiskOrchestratorHttpClient(
            httpClient = mockHttpClient {
                respond(
                    content = "Invalid hierarchy level",
                    status = HttpStatusCode.BadRequest,
                )
            },
            baseUrl = "http://orchestrator",
        )

        runTest {
            val thrown = shouldThrow<IllegalStateException> {
                client.seedLimit("BAD-BOOK", LimitType.VAR_95, BigDecimal("1.00"))
            }
            thrown.message!! shouldContain "400"
            thrown.message!! shouldContain "POST"
            thrown.message!! shouldContain "Invalid hierarchy level"
        }
    }

    test("calculateCrossBookVaR POSTs to /api/v1/risk/var/cross-book with the expected body shape") {
        var capturedUrl: String? = null
        var capturedMethod: HttpMethod? = null
        var capturedBody: String? = null
        var capturedContentType: String? = null

        val client = RiskOrchestratorHttpClient(
            httpClient = mockHttpClient { request ->
                capturedUrl = request.url.toString()
                capturedMethod = request.method
                capturedContentType = request.body.contentType?.toString()
                capturedBody = String(request.body.toByteArray())
                // Return a minimal valid cross-book VaR response
                respond(
                    content = """
                        {
                          "portfolioGroupId":"firm",
                          "bookIds":["equity-growth","tech-momentum"],
                          "calculationType":"PARAMETRIC",
                          "confidenceLevel":"CL_95",
                          "varValue":"800000.00",
                          "expectedShortfall":"960000.00",
                          "componentBreakdown":[],
                          "bookContributions":[],
                          "totalStandaloneVar":"900000.00",
                          "diversificationBenefit":"100000.00",
                          "calculatedAt":"2026-05-26T08:00:00Z"
                        }
                    """.trimIndent(),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
            baseUrl = "http://orchestrator",
        )

        runTest {
            client.calculateCrossBookVaR(
                bookIds = listOf("equity-growth", "tech-momentum"),
                portfolioGroupId = "firm",
                confidenceLevel = "CL_95",
                horizonDays = 10,
                method = "PARAMETRIC",
            )
        }

        capturedUrl shouldBe "http://orchestrator/api/v1/risk/var/cross-book"
        capturedMethod shouldBe HttpMethod.Post
        capturedContentType!! shouldContain "application/json"
        val body = capturedBody!!
        body shouldContain "\"portfolioGroupId\":\"firm\""
        body shouldContain "equity-growth"
        body shouldContain "tech-momentum"
        body shouldContain "\"calculationType\":\"PARAMETRIC\""
        body shouldContain "\"confidenceLevel\":\"CL_95\""
        body shouldContain "\"timeHorizonDays\":\"10\""
    }

    test("calculateCrossBookVaR throws IllegalStateException on 5xx") {
        val client = RiskOrchestratorHttpClient(
            httpClient = mockHttpClient {
                respond(content = "VaR service down", status = HttpStatusCode.ServiceUnavailable)
            },
            baseUrl = "http://orchestrator",
        )

        runTest {
            val thrown = shouldThrow<IllegalStateException> {
                client.calculateCrossBookVaR(
                    bookIds = listOf("equity-growth"),
                    portfolioGroupId = "firm",
                    confidenceLevel = "CL_95",
                    horizonDays = 10,
                    method = "PARAMETRIC",
                )
            }
            thrown.message!! shouldContain "503"
            thrown.message!! shouldContain "POST"
            thrown.message!! shouldContain "VaR service down"
        }
    }
})

package com.kinetix.demo.client

import com.kinetix.demo.client.dtos.BacktestRequest
import com.kinetix.demo.client.dtos.CreateSubmissionRequest
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

class RegulatoryServiceClientTest : FunSpec({

    fun mockHttpClient(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpClient = HttpClient(MockEngine { request -> handler(request) })

    fun sampleBacktestRequest(
        dailyVarPredictions: List<Double> = listOf(1000.0, 1100.0, 1050.0),
        dailyPnl: List<Double> = listOf(-200.0, -500.0, 300.0),
        confidenceLevel: Double = 0.99,
        calculationType: String = "PARAMETRIC",
    ) = BacktestRequest(
        dailyVarPredictions = dailyVarPredictions,
        dailyPnl = dailyPnl,
        confidenceLevel = confidenceLevel,
        calculationType = calculationType,
    )

    test("runBacktest posts to the expected URL with JSON body and returns the parsed result") {
        var capturedUrl: String? = null
        var capturedMethod: HttpMethod? = null
        var capturedBody: String? = null
        var capturedContentType: String? = null

        val client = RegulatoryServiceHttpClient(
            httpClient = mockHttpClient { request ->
                capturedUrl = request.url.toString()
                capturedMethod = request.method
                capturedContentType = request.body.contentType?.toString()
                capturedBody = String(request.body.toByteArray())
                respond(
                    content = """
                        {
                          "id":"bt-1",
                          "bookId":"BOOK-EQ-01",
                          "calculationType":"PARAMETRIC",
                          "confidenceLevel":"0.9900",
                          "totalDays":30,
                          "violationCount":2,
                          "violationRate":"0.066667",
                          "kupiecStatistic":"0.1234",
                          "kupiecPValue":"0.7251",
                          "kupiecPass":true,
                          "christoffersenStatistic":"0.0500",
                          "christoffersenPValue":"0.8200",
                          "christoffersenPass":true,
                          "trafficLightZone":"GREEN",
                          "calculatedAt":"2026-05-18T06:05:00Z"
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
            baseUrl = "http://regulatory",
        )

        runTest {
            val result = client.runBacktest(
                bookId = "BOOK-EQ-01",
                request = sampleBacktestRequest(),
            )
            result.violationCount shouldBe 2
            result.kupiecPass shouldBe true
            result.trafficLightZone shouldBe "GREEN"
        }

        capturedUrl shouldBe "http://regulatory/api/v1/regulatory/backtest/BOOK-EQ-01"
        capturedMethod shouldBe HttpMethod.Post
        capturedContentType!! shouldContain "application/json"
        val body = capturedBody!!
        body shouldContain "\"dailyVarPredictions\":[1000.0,1100.0,1050.0]"
        body shouldContain "\"dailyPnl\":[-200.0,-500.0,300.0]"
        body shouldContain "\"confidenceLevel\":0.99"
        body shouldContain "\"calculationType\":\"PARAMETRIC\""
    }

    test("runBacktest throws IllegalStateException on 5xx") {
        val client = RegulatoryServiceHttpClient(
            httpClient = mockHttpClient {
                respond(content = "boom", status = HttpStatusCode.InternalServerError)
            },
            baseUrl = "http://regulatory",
        )

        runTest {
            val thrown = shouldThrow<IllegalStateException> {
                client.runBacktest(
                    bookId = "BOOK-EQ-01",
                    request = sampleBacktestRequest(),
                )
            }
            thrown.message!! shouldContain "500"
            thrown.message!! shouldContain "POST"
            thrown.message!! shouldContain "boom"
        }
    }

    test("calculateFrtb POSTs to the frtb calculate endpoint for the book (kx-kzbs)") {
        var capturedUrl: String? = null
        var capturedMethod: HttpMethod? = null
        val client = RegulatoryServiceHttpClient(
            httpClient = mockHttpClient { request ->
                capturedUrl = request.url.toString()
                capturedMethod = request.method
                respond(
                    content = """{"id":"frtb-1","bookId":"macro-hedge","sbmCharges":[],"totalSbmCharge":"0.00","grossJtd":"0.00","hedgeBenefit":"0.00","netDrc":"0.00","exoticNotional":"0.00","otherNotional":"0.00","totalRrao":"0.00","totalCapitalCharge":"0.00","calculatedAt":"2026-05-18T06:05:00Z","storedAt":"2026-05-18T06:05:01Z"}""",
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
            baseUrl = "http://regulatory",
        )

        runTest {
            client.calculateFrtb("macro-hedge")
        }

        capturedMethod shouldBe HttpMethod.Post
        capturedUrl shouldBe "http://regulatory/api/v1/regulatory/frtb/macro-hedge/calculate"
    }

    test("calculateFrtb throws IllegalStateException on 5xx (kx-kzbs)") {
        val client = RegulatoryServiceHttpClient(
            httpClient = mockHttpClient {
                respond(content = "boom", status = HttpStatusCode.InternalServerError)
            },
            baseUrl = "http://regulatory",
        )

        runTest {
            val thrown = shouldThrow<IllegalStateException> {
                client.calculateFrtb("macro-hedge")
            }
            thrown.message!! shouldContain "500"
            thrown.message!! shouldContain "POST"
        }
    }

    test("createSubmission posts to the expected URL with JSON body and returns the parsed ref") {
        var capturedUrl: String? = null
        var capturedMethod: HttpMethod? = null
        var capturedBody: String? = null
        var capturedContentType: String? = null

        val client = RegulatoryServiceHttpClient(
            httpClient = mockHttpClient { request ->
                capturedUrl = request.url.toString()
                capturedMethod = request.method
                capturedContentType = request.body.contentType?.toString()
                capturedBody = String(request.body.toByteArray())
                respond(
                    content = """
                        {
                          "id":"sub-42",
                          "reportType":"DAILY_RISK_SUMMARY",
                          "status":"DRAFT",
                          "preparerId":"demo-orchestrator",
                          "approverId":null,
                          "deadline":"2026-05-19T17:00:00Z",
                          "submittedAt":null,
                          "acknowledgedAt":null,
                          "createdAt":"2026-05-18T06:05:00Z"
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
            baseUrl = "http://regulatory",
        )

        runTest {
            val ref = client.createSubmission(
                CreateSubmissionRequest(
                    reportType = "DAILY_RISK_SUMMARY",
                    preparerId = "demo-orchestrator",
                    deadline = "2026-05-19T17:00:00Z",
                ),
            )
            ref.id shouldBe "sub-42"
            ref.reportType shouldBe "DAILY_RISK_SUMMARY"
            ref.status shouldBe "DRAFT"
        }

        capturedUrl shouldBe "http://regulatory/api/v1/submissions"
        capturedMethod shouldBe HttpMethod.Post
        capturedContentType!! shouldContain "application/json"
        val body = capturedBody!!
        body shouldContain "\"reportType\":\"DAILY_RISK_SUMMARY\""
        body shouldContain "\"preparerId\":\"demo-orchestrator\""
        body shouldContain "\"deadline\":\"2026-05-19T17:00:00Z\""
    }

    test("createSubmission throws IllegalStateException on 4xx") {
        val client = RegulatoryServiceHttpClient(
            httpClient = mockHttpClient {
                respond(
                    content = "Invalid reportType",
                    status = HttpStatusCode.BadRequest,
                )
            },
            baseUrl = "http://regulatory",
        )

        runTest {
            val thrown = shouldThrow<IllegalStateException> {
                client.createSubmission(
                    CreateSubmissionRequest(
                        reportType = "BOGUS",
                        preparerId = "demo-orchestrator",
                        deadline = "2026-05-19T17:00:00Z",
                    ),
                )
            }
            thrown.message!! shouldContain "400"
            thrown.message!! shouldContain "POST"
            thrown.message!! shouldContain "Invalid reportType"
        }
    }
})

package com.kinetix.rates.routes

import com.kinetix.common.model.*
import com.kinetix.rates.module
import com.kinetix.rates.persistence.ForwardCurveRepository
import com.kinetix.rates.persistence.RiskFreeRateRepository
import com.kinetix.rates.persistence.YieldCurveRepository
import com.kinetix.rates.service.RateIngestionService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.mockk.just
import io.mockk.runs
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

private val USD = Currency.getInstance("USD")
private val NOW = Instant.parse("2026-02-24T10:00:00Z")

class RatesRoutesTest : FunSpec({

    val yieldCurveRepo = mockk<YieldCurveRepository>()
    val riskFreeRateRepo = mockk<RiskFreeRateRepository>()
    val forwardCurveRepo = mockk<ForwardCurveRepository>()
    val ingestionService = mockk<RateIngestionService>()

    beforeEach {
        clearMocks(yieldCurveRepo, riskFreeRateRepo, forwardCurveRepo)
    }

    test("GET yield-curves latest returns 200 with curve") {
        val curve = YieldCurve(
            currency = USD,
            asOf = NOW,
            tenors = listOf(
                Tenor.oneMonth(BigDecimal("0.04")),
                Tenor.oneYear(BigDecimal("0.05")),
            ),
            curveId = "USD-TREASURY",
            source = RateSource.CENTRAL_BANK,
        )
        coEvery { yieldCurveRepo.findLatest("USD-TREASURY") } returns curve

        testApplication {
            application { module(yieldCurveRepo, riskFreeRateRepo, forwardCurveRepo, ingestionService) }

            val response = client.get("/api/v1/rates/yield-curves/USD-TREASURY/latest")
            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            body shouldContain "USD-TREASURY"
            body shouldContain "CENTRAL_BANK"
        }
    }

    test("GET yield-curves latest returns 404 for unknown curve") {
        coEvery { yieldCurveRepo.findLatest("UNKNOWN") } returns null

        testApplication {
            application { module(yieldCurveRepo, riskFreeRateRepo, forwardCurveRepo, ingestionService) }

            val response = client.get("/api/v1/rates/yield-curves/UNKNOWN/latest")
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("GET risk-free rate latest returns 200 with rate") {
        val rate = RiskFreeRate(
            currency = USD,
            tenor = "3M",
            rate = 0.0525,
            asOfDate = NOW,
            source = RateSource.CENTRAL_BANK,
        )
        coEvery { riskFreeRateRepo.findLatest(USD, "3M") } returns rate

        testApplication {
            application { module(yieldCurveRepo, riskFreeRateRepo, forwardCurveRepo, ingestionService) }

            val response = client.get("/api/v1/rates/risk-free/USD/latest?tenor=3M")
            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            body shouldContain "0.0525"
            body shouldContain "CENTRAL_BANK"
        }
    }

    test("GET risk-free rate latest returns 404 for unknown currency") {
        coEvery { riskFreeRateRepo.findLatest(any(), any()) } returns null

        testApplication {
            application { module(yieldCurveRepo, riskFreeRateRepo, forwardCurveRepo, ingestionService) }

            val response = client.get("/api/v1/rates/risk-free/JPY/latest?tenor=O/N")
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("GET forward curve latest returns 200 with curve") {
        val curve = ForwardCurve(
            instrumentId = InstrumentId("EURUSD"),
            assetClass = AssetClass.FX,
            points = listOf(
                CurvePoint("1M", 1.0855),
                CurvePoint("3M", 1.0870),
            ),
            asOfDate = NOW,
            source = RateSource.REUTERS,
        )
        coEvery { forwardCurveRepo.findLatest(InstrumentId("EURUSD")) } returns curve

        testApplication {
            application { module(yieldCurveRepo, riskFreeRateRepo, forwardCurveRepo, ingestionService) }

            val response = client.get("/api/v1/rates/forwards/EURUSD/latest")
            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            body shouldContain "EURUSD"
            body shouldContain "REUTERS"
        }
    }

    test("GET forward curve latest returns 404 for unknown instrument") {
        coEvery { forwardCurveRepo.findLatest(InstrumentId("UNKNOWN")) } returns null

        testApplication {
            application { module(yieldCurveRepo, riskFreeRateRepo, forwardCurveRepo, ingestionService) }

            val response = client.get("/api/v1/rates/forwards/UNKNOWN/latest")
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("POST yield-curves returns 201 Created") {
        coEvery { ingestionService.ingest(any<YieldCurve>()) } just runs

        testApplication {
            application { module(yieldCurveRepo, riskFreeRateRepo, forwardCurveRepo, ingestionService) }

            val response = client.post("/api/v1/rates/yield-curves") {
                contentType(ContentType.Application.Json)
                setBody("""
                    {
                        "curveId": "USD-TREASURY",
                        "currency": "USD",
                        "tenors": [{"label": "1M", "days": 30, "rate": "0.04"}],
                        "source": "CENTRAL_BANK"
                    }
                """.trimIndent())
            }
            response.status shouldBe HttpStatusCode.Created
            response.bodyAsText() shouldContain "USD-TREASURY"
        }
    }

    test("POST risk-free returns 201 Created") {
        coEvery { ingestionService.ingest(any<RiskFreeRate>()) } just runs

        testApplication {
            application { module(yieldCurveRepo, riskFreeRateRepo, forwardCurveRepo, ingestionService) }

            val response = client.post("/api/v1/rates/risk-free") {
                contentType(ContentType.Application.Json)
                setBody("""
                    {
                        "currency": "USD",
                        "tenor": "3M",
                        "rate": "0.0525",
                        "source": "CENTRAL_BANK"
                    }
                """.trimIndent())
            }
            response.status shouldBe HttpStatusCode.Created
            response.bodyAsText() shouldContain "0.0525"
        }
    }

    test("POST forwards returns 201 Created") {
        coEvery { ingestionService.ingest(any<ForwardCurve>()) } just runs

        testApplication {
            application { module(yieldCurveRepo, riskFreeRateRepo, forwardCurveRepo, ingestionService) }

            val response = client.post("/api/v1/rates/forwards") {
                contentType(ContentType.Application.Json)
                setBody("""
                    {
                        "instrumentId": "EURUSD",
                        "assetClass": "FX",
                        "points": [{"tenor": "1M", "value": "1.0855"}],
                        "source": "REUTERS"
                    }
                """.trimIndent())
            }
            response.status shouldBe HttpStatusCode.Created
            response.bodyAsText() shouldContain "EURUSD"
        }
    }

    test("GET yield-curves history returns 200 with curves") {
        val curves = listOf(
            YieldCurve(
                currency = USD,
                asOf = NOW,
                tenors = listOf(Tenor.oneMonth(BigDecimal("0.04"))),
                curveId = "USD-TREASURY",
                source = RateSource.CENTRAL_BANK,
            ),
        )
        coEvery { yieldCurveRepo.findByTimeRange("USD-TREASURY", any(), any()) } returns curves

        testApplication {
            application { module(yieldCurveRepo, riskFreeRateRepo, forwardCurveRepo, ingestionService) }

            val response = client.get("/api/v1/rates/yield-curves/USD-TREASURY/history?from=2026-01-01T00:00:00Z&to=2026-12-31T00:00:00Z")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "USD-TREASURY"
        }
    }

    test("POST yield-curves returns 400 for empty tenors") {
        testApplication {
            application { module(yieldCurveRepo, riskFreeRateRepo, forwardCurveRepo, ingestionService) }

            val response = client.post("/api/v1/rates/yield-curves") {
                contentType(ContentType.Application.Json)
                setBody("""
                    {
                        "curveId": "USD-TREASURY",
                        "currency": "USD",
                        "tenors": [],
                        "source": "CENTRAL_BANK"
                    }
                """.trimIndent())
            }
            response.status shouldBe HttpStatusCode.BadRequest
            response.bodyAsText() shouldContain "tenors must contain at least one entry"
        }
    }

    test("POST yield-curves returns 400 for non-monotonic tenor days") {
        testApplication {
            application { module(yieldCurveRepo, riskFreeRateRepo, forwardCurveRepo, ingestionService) }

            val response = client.post("/api/v1/rates/yield-curves") {
                contentType(ContentType.Application.Json)
                setBody("""
                    {
                        "curveId": "USD-TREASURY",
                        "currency": "USD",
                        "tenors": [
                            {"label": "1Y", "days": 365, "rate": "0.05"},
                            {"label": "1M", "days": 30, "rate": "0.04"}
                        ],
                        "source": "CENTRAL_BANK"
                    }
                """.trimIndent())
            }
            response.status shouldBe HttpStatusCode.BadRequest
            response.bodyAsText() shouldContain "tenors must be strictly monotonic in days"
        }
    }

    test("POST risk-free returns 400 for blank tenor") {
        testApplication {
            application { module(yieldCurveRepo, riskFreeRateRepo, forwardCurveRepo, ingestionService) }

            val response = client.post("/api/v1/rates/risk-free") {
                contentType(ContentType.Application.Json)
                setBody("""
                    {
                        "currency": "USD",
                        "tenor": "",
                        "rate": "0.0525",
                        "source": "CENTRAL_BANK"
                    }
                """.trimIndent())
            }
            response.status shouldBe HttpStatusCode.BadRequest
            response.bodyAsText() shouldContain "tenor must not be blank"
        }
    }

    test("POST forwards returns 400 for empty points") {
        testApplication {
            application { module(yieldCurveRepo, riskFreeRateRepo, forwardCurveRepo, ingestionService) }

            val response = client.post("/api/v1/rates/forwards") {
                contentType(ContentType.Application.Json)
                setBody("""
                    {
                        "instrumentId": "EURUSD",
                        "assetClass": "FX",
                        "points": [],
                        "source": "REUTERS"
                    }
                """.trimIndent())
            }
            response.status shouldBe HttpStatusCode.BadRequest
            response.bodyAsText() shouldContain "points must contain at least one entry"
        }
    }

    test("GET forwards history returns 200 with curves") {
        val curves = listOf(
            ForwardCurve(
                instrumentId = InstrumentId("EURUSD"),
                assetClass = AssetClass.FX,
                points = listOf(CurvePoint("1M", 1.0855)),
                asOfDate = NOW,
                source = RateSource.REUTERS,
            ),
        )
        coEvery { forwardCurveRepo.findByTimeRange(InstrumentId("EURUSD"), any(), any()) } returns curves

        testApplication {
            application { module(yieldCurveRepo, riskFreeRateRepo, forwardCurveRepo, ingestionService) }

            val response = client.get("/api/v1/rates/forwards/EURUSD/history?from=2026-01-01T00:00:00Z&to=2026-12-31T00:00:00Z")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "EURUSD"
        }
    }

    test("GET yield-curve tenor returns the rate with interpolated=false when present") {
        val curve = YieldCurve(
            currency = Currency.getInstance("GBP"),
            asOf = NOW,
            tenors = listOf(
                Tenor.twoYears(BigDecimal("0.0420")),
                Tenor.fiveYears(BigDecimal("0.0445")),
                Tenor.tenYears(BigDecimal("0.0470")),
            ),
            curveId = "GBP",
            source = RateSource.CENTRAL_BANK,
        )
        coEvery { yieldCurveRepo.findLatest("GBP") } returns curve

        testApplication {
            application { module(yieldCurveRepo, riskFreeRateRepo, forwardCurveRepo, ingestionService) }

            val response = client.get("/api/v1/rates/yield-curves/GBP/tenor/5Y")
            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            body shouldContain "\"tenor\":\"5Y\""
            body shouldContain "\"interpolated\":false"
            body shouldContain "0.0445"
        }
    }

    test("GET yield-curve tenor linearly interpolates when the requested node is missing") {
        val curve = YieldCurve(
            currency = Currency.getInstance("GBP"),
            asOf = NOW,
            tenors = listOf(
                Tenor.twoYears(BigDecimal("0.0420")),
                // 5Y omitted — Gap 8 anomaly
                Tenor.tenYears(BigDecimal("0.0470")),
            ),
            curveId = "GBP",
            source = RateSource.CENTRAL_BANK,
        )
        coEvery { yieldCurveRepo.findLatest("GBP") } returns curve

        testApplication {
            application { module(yieldCurveRepo, riskFreeRateRepo, forwardCurveRepo, ingestionService) }

            val response = client.get("/api/v1/rates/yield-curves/GBP/tenor/5Y")
            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            body shouldContain "\"interpolated\":true"
            // Linear interp between 2Y (0.0420) and 10Y (0.0470) at 5Y (1825 days):
            //   below=730, above=3650, span=2920, offset=1095
            //   value = 0.0420 + (0.0470-0.0420) * 1095/2920 ≈ 0.043875
            body shouldContain "0.043875"
        }
    }

    test("GET yield-curve tenor returns 404 when the curve is unknown") {
        coEvery { yieldCurveRepo.findLatest("UNKNOWN") } returns null

        testApplication {
            application { module(yieldCurveRepo, riskFreeRateRepo, forwardCurveRepo, ingestionService) }

            val response = client.get("/api/v1/rates/yield-curves/UNKNOWN/tenor/5Y")
            response.status shouldBe HttpStatusCode.NotFound
        }
    }
})

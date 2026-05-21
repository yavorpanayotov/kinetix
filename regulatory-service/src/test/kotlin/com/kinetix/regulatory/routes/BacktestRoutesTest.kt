package com.kinetix.regulatory.routes

import com.kinetix.regulatory.client.RiskOrchestratorClient
import com.kinetix.regulatory.model.BacktestResultRecord
import com.kinetix.regulatory.module
import com.kinetix.regulatory.persistence.BacktestResultRepository
import com.kinetix.regulatory.persistence.FrtbCalculationRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Instant

class BacktestRoutesTest : FunSpec({

    val sampleRecord = BacktestResultRecord(
        id = "bt-1",
        bookId = "port-1",
        calculationType = "PARAMETRIC",
        confidenceLevel = 0.99,
        totalDays = 250,
        violationCount = 3,
        violationRate = 0.012,
        kupiecStatistic = 0.15,
        kupiecPValue = 0.70,
        kupiecPass = true,
        christoffersenStatistic = 0.08,
        christoffersenPValue = 0.78,
        christoffersenPass = true,
        trafficLightZone = "GREEN",
        calculatedAt = Instant.parse("2026-01-15T10:00:00Z"),
    )

    test("POST /backtest triggers backtest and returns 201") {
        val frtbRepo = mockk<FrtbCalculationRepository>()
        val backtestRepo = mockk<BacktestResultRepository>()
        val client = mockk<RiskOrchestratorClient>()
        coEvery { backtestRepo.save(any()) } returns Unit

        testApplication {
            application { module(frtbRepo, client, backtestRepo) }
            val response = this.client.post("/api/v1/regulatory/backtest/port-1") {
                contentType(ContentType.Application.Json)
                setBody("""{"dailyVarPredictions":[100.0,100.0,100.0],"dailyPnl":[-50.0,-150.0,-80.0],"confidenceLevel":0.99}""")
            }
            response.status shouldBe HttpStatusCode.Created
            val body = response.bodyAsText()
            body shouldContain "\"bookId\":\"port-1\""
            body shouldContain "\"violationCount\":1"
            body shouldContain "\"trafficLightZone\":\"GREEN\""
        }
    }

    test("GET /backtest/latest returns most recent result") {
        val frtbRepo = mockk<FrtbCalculationRepository>()
        val backtestRepo = mockk<BacktestResultRepository>()
        val client = mockk<RiskOrchestratorClient>()
        coEvery { backtestRepo.findLatestByBookId("port-1") } returns sampleRecord

        testApplication {
            application { module(frtbRepo, client, backtestRepo) }
            val response = this.client.get("/api/v1/regulatory/backtest/port-1/latest")
            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            body shouldContain "\"trafficLightZone\":\"GREEN\""
            body shouldContain "\"kupiecPass\":true"
        }
    }

    test("GET /backtest/latest returns 404 when no results exist") {
        val frtbRepo = mockk<FrtbCalculationRepository>()
        val backtestRepo = mockk<BacktestResultRepository>()
        val client = mockk<RiskOrchestratorClient>()
        coEvery { backtestRepo.findLatestByBookId("port-2") } returns null

        testApplication {
            application { module(frtbRepo, client, backtestRepo) }
            val response = this.client.get("/api/v1/regulatory/backtest/port-2/latest")
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("GET /backtest/history returns paginated results") {
        val frtbRepo = mockk<FrtbCalculationRepository>()
        val backtestRepo = mockk<BacktestResultRepository>()
        val client = mockk<RiskOrchestratorClient>()
        coEvery { backtestRepo.findByBookId("port-1", 20, 0) } returns listOf(sampleRecord)

        testApplication {
            application { module(frtbRepo, client, backtestRepo) }
            val response = this.client.get("/api/v1/regulatory/backtest/port-1/history")
            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            body shouldContain "\"results\""
            body shouldContain "\"bookId\":\"port-1\""
        }
    }

    test("POST /backtest exposes governance metrics on /metrics at the real call site") {
        val frtbRepo = mockk<FrtbCalculationRepository>()
        val backtestRepo = mockk<BacktestResultRepository>()
        val client = mockk<RiskOrchestratorClient>()
        coEvery { backtestRepo.save(any()) } returns Unit

        testApplication {
            application { module(frtbRepo, client, backtestRepo) }
            // Three days, one VaR breach (day 2: -150 loss vs 100 VaR) — GREEN zone.
            this.client.post("/api/v1/regulatory/backtest/port-metrics") {
                contentType(ContentType.Application.Json)
                setBody("""{"dailyVarPredictions":[100.0,100.0,100.0],"dailyPnl":[-50.0,-150.0,-80.0],"confidenceLevel":0.99}""")
            }

            val metrics = this.client.get("/metrics").bodyAsText()
            metrics shouldContain "regulatory_backtest_runs_total{"
            metrics shouldContain "regulatory_backtest_exceptions_total{"
            metrics shouldContain "regulatory_backtest_current_exceptions{"
            metrics shouldContain "book_id=\"port-metrics\""
        }
    }
})

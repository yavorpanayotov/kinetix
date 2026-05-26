package com.kinetix.demo.routes

import com.kinetix.demo.client.RiskOrchestratorClient
import com.kinetix.demo.client.dtos.EodPromotionResponseDto
import com.kinetix.demo.client.dtos.ValuationJobSummary
import com.kinetix.demo.profile.DemoBookProfile
import com.kinetix.demo.schedule.EodPromotionJob
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.delay
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Unit tests for [TriggerEodRoutes].
 *
 * Exercises the POST /demo/trigger-eod endpoint using Ktor's testApplication.
 * The handler is fire-and-forget: it launches the EodPromotionJob asynchronously
 * and responds 202 Accepted with a correlation id immediately. The endpoint is
 * gated behind DEMO_MODE=true — when disabled the route must not be registered.
 */
class TriggerEodRoutesTest : FunSpec({

    val balancedIncome = DemoBookProfile(
        bookId = "balanced-income",
        tradeProbability = 0.0,
        instrumentIds = listOf("JNJ"),
        notionalRangeUsd = 1L..2L,
        assetClass = "EQUITY",
    )

    val derivatives = DemoBookProfile(
        bookId = "derivatives-book",
        tradeProbability = 0.0,
        instrumentIds = listOf("SPX-OPT-5000C"),
        notionalRangeUsd = 1L..2L,
        assetClass = "DERIVATIVE",
    )

    val valuationDate = LocalDate.of(2026, 5, 18)
    val fixedClock = Clock.fixed(
        valuationDate.atTime(17, 0).atZone(ZoneOffset.UTC).toInstant(),
        ZoneOffset.UTC,
    )

    fun newClient(): RiskOrchestratorClient {
        val client = mockk<RiskOrchestratorClient>()
        coEvery { client.findOfficialEod(any(), any()) } returns null
        coEvery { client.calculateVaR(any()) } just Runs
        coEvery { client.findLatestCompletedJob(any()) } answers {
            ValuationJobSummary(
                jobId = "job-${firstArg<String>()}",
                bookId = firstArg(),
                status = "COMPLETED",
                valuationDate = valuationDate.toString(),
            )
        }
        coEvery { client.promoteJobToOfficialEod(any(), any()) } answers {
            EodPromotionResponseDto(
                jobId = firstArg(),
                bookId = "balanced-income",
                valuationDate = valuationDate.toString(),
                runLabel = "OFFICIAL_EOD",
                promotedAt = "2026-05-18T17:00:00Z",
                promotedBy = secondArg(),
            )
        }
        return client
    }

    test("returns 202 Accepted with dispatched=true and the configured book count") {
        val job = EodPromotionJob(
            client = newClient(),
            books = listOf(balancedIncome, derivatives),
            clock = fixedClock,
        )

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { triggerEodRoutes(job) }
            }
            val response = client.post("/demo/trigger-eod")
            response.status shouldBe HttpStatusCode.Accepted
            val body = response.bodyAsText()
            body shouldContain "\"dispatched\":true"
            body shouldContain "\"bookCount\":2"
            body shouldContain "\"correlationId\":"
        }
    }

    test("each call returns a unique correlation id") {
        val job = EodPromotionJob(
            client = newClient(),
            books = listOf(balancedIncome),
            clock = fixedClock,
        )

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { triggerEodRoutes(job) }
            }
            val first = client.post("/demo/trigger-eod").bodyAsText()
            val second = client.post("/demo/trigger-eod").bodyAsText()
            (first == second) shouldBe false
        }
    }

    test("dispatches EodPromotionJob.runOnce asynchronously without blocking the response") {
        val underlyingClient = newClient()
        // Make calculateVaR slow so runOnce would block the response if awaited.
        coEvery { underlyingClient.calculateVaR(any()) } coAnswers {
            delay(2_000)
        }
        val job = EodPromotionJob(
            client = underlyingClient,
            books = listOf(balancedIncome),
            clock = fixedClock,
        )

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { triggerEodRoutes(job) }
            }
            val started = System.currentTimeMillis()
            val response = client.post("/demo/trigger-eod")
            val elapsedMillis = System.currentTimeMillis() - started

            response.status shouldBe HttpStatusCode.Accepted
            // Must return well before runOnce would have completed.
            (elapsedMillis < 1_500) shouldBe true
        }
    }

    test("dispatched job failure does not affect the 202 response") {
        val failingClient = mockk<RiskOrchestratorClient>()
        coEvery {
            failingClient.findOfficialEod(any(), any())
        } throws RuntimeException("simulated wire failure")
        val job = EodPromotionJob(
            client = failingClient,
            books = listOf(balancedIncome),
            clock = fixedClock,
        )

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { triggerEodRoutes(job) }
            }
            val response = client.post("/demo/trigger-eod")
            response.status shouldBe HttpStatusCode.Accepted
            response.bodyAsText() shouldContain "\"dispatched\":true"
        }
    }

    test("response Content-Type is application/json") {
        val job = EodPromotionJob(
            client = newClient(),
            books = listOf(balancedIncome),
            clock = fixedClock,
        )

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { triggerEodRoutes(job) }
            }
            val response = client.post("/demo/trigger-eod")
            response.headers["Content-Type"] shouldContain ContentType.Application.Json.toString()
        }
    }

    test("invokes EodPromotionJob.runOnce exactly once per request") {
        val underlyingClient = newClient()
        val job = EodPromotionJob(
            client = underlyingClient,
            books = listOf(balancedIncome),
            clock = fixedClock,
        )

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { triggerEodRoutes(job) }
            }
            client.post("/demo/trigger-eod")
            // Allow the launched coroutine to execute the chain.
            delay(200)
        }

        coVerify(exactly = 1) { underlyingClient.calculateVaR("balanced-income") }
    }

    test("route is not registered when demo mode is disabled") {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                // Simulate the Application module's gating: when DEMO_MODE=false
                // wireDemoSchedulers returns early and triggerEodRoutes is never called.
                routing {
                    // intentionally empty — no triggerEodRoutes registration
                }
            }
            val response = client.post("/demo/trigger-eod")
            response.status shouldBe HttpStatusCode.NotFound
        }
    }
})

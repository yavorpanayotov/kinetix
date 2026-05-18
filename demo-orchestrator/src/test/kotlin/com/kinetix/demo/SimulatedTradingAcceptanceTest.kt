package com.kinetix.demo

import com.kinetix.demo.client.PositionServiceHttpClient
import com.kinetix.demo.client.dtos.StrategyTradeRequest
import com.kinetix.demo.profile.DemoBookProfile
import com.kinetix.demo.schedule.DefaultPriceBook
import com.kinetix.demo.schedule.DefaultStrategyIdResolver
import com.kinetix.demo.schedule.SimulatedTraderJob
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random

/**
 * Acceptance test for [SimulatedTraderJob] wired against a real local Ktor
 * stub of `position-service`.
 *
 * Per CLAUDE.md (Project Conventions / Acceptance tests), the HTTP boundary
 * to another Kinetix service is exercised over a real localhost HTTP/1.1
 * channel — never via [io.ktor.client.engine.mock.MockEngine]. The stub
 * binds to port 0 and the [PositionServiceHttpClient] is pointed at the
 * resolved port, so serialisation, content negotiation, and the HTTP wire
 * are all real.
 *
 * No Testcontainers Postgres/Kafka here: [SimulatedTraderJob] has no DB
 * or Kafka dependency — its only collaborator is the HTTP position client.
 * The plan's `EodCycleObserverJob` acceptance test (later checkbox) is the
 * right place to introduce Testcontainers Kafka.
 */
class SimulatedTradingAcceptanceTest : FunSpec({

    test("one in-hours tick produces at least one StrategyTradeRequest POST observed by the position-service stub") {
        val receivedRequests = ConcurrentLinkedQueue<RecordedTradePost>()
        val responseJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        val stubServer = embeddedServer(Netty, port = 0) {
            routing {
                post("/api/v1/books/{bookId}/strategies/{strategyId}/trades") {
                    val bookId = call.parameters["bookId"].orEmpty()
                    val strategyId = call.parameters["strategyId"].orEmpty()
                    val rawBody = call.receiveText()
                    val parsed = responseJson.decodeFromString(
                        StrategyTradeRequest.serializer(),
                        rawBody,
                    )
                    receivedRequests.add(
                        RecordedTradePost(
                            bookId = bookId,
                            strategyId = strategyId,
                            rawBody = rawBody,
                            request = parsed,
                        ),
                    )
                    val tradeId = "stub-trade-${receivedRequests.size}"
                    call.respondText(
                        text = """{"tradeId":"$tradeId"}""",
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.Created,
                    )
                }
            }
        }.start(wait = false)

        val port = runBlocking { stubServer.engine.resolvedConnectors().first().port }
        val baseUrl = "http://localhost:$port"

        val httpClient = HttpClient(CIO) {
            install(ClientContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }

        try {
            val positionClient = PositionServiceHttpClient(
                httpClient = httpClient,
                baseUrl = baseUrl,
            )

            // 2026-05-18 is a Monday; noon UTC is comfortably inside the
            // 09:00–16:30 window the production scheduler uses.
            val fixedClock = Clock.fixed(
                LocalDate.of(2026, 5, 18)
                    .atTime(12, 0)
                    .atZone(ZoneOffset.UTC)
                    .toInstant(),
                ZoneOffset.UTC,
            )

            val book = DemoBookProfile(
                bookId = "test-equity-book",
                tradeProbability = 1.0,
                instrumentIds = listOf("AAPL"),
                notionalRangeUsd = 10_000L..20_000L,
                assetClass = "EQUITY",
            )

            val job = SimulatedTraderJob(
                positionClient = positionClient,
                strategyIdResolver = DefaultStrategyIdResolver(),
                priceBook = DefaultPriceBook(),
                books = listOf(book),
                tradingHoursStart = LocalTime.of(9, 0),
                tradingHoursEnd = LocalTime.of(16, 30),
                clock = fixedClock,
                random = Random(seed = 42L),
            )

            val posted = runBlocking { job.runTick() }

            posted shouldBeGreaterThan 0

            val captured = receivedRequests.toList()
            captured.size shouldBe posted

            val first = captured.first()
            first.bookId shouldBe "test-equity-book"
            // Default resolver maps {bookId} -> "{bookId}-default".
            first.strategyId shouldBe "test-equity-book-default"

            first.request.instrumentId shouldBe "AAPL"
            first.request.assetClass shouldBe "EQUITY"
            first.request.priceCurrency shouldBe "USD"
            first.request.instrumentType shouldBe "CASH_EQUITY"
            first.request.userId shouldBe "demo-orchestrator"
            first.request.userRole shouldBe "DEMO"
            first.request.tradeId shouldBe null
            listOf("BUY", "SELL") shouldContain first.request.side

            // The Json config on the client omits null fields — confirm the
            // wire body excludes them, so the upstream route generates the
            // tradeId server-side as documented.
            first.rawBody shouldContain "\"instrumentId\":\"AAPL\""
            first.rawBody shouldContain "\"assetClass\":\"EQUITY\""
        } finally {
            httpClient.close()
            stubServer.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
        }
    }
})

private data class RecordedTradePost(
    val bookId: String,
    val strategyId: String,
    val rawBody: String,
    val request: StrategyTradeRequest,
)

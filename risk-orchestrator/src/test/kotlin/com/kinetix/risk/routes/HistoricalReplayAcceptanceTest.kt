package com.kinetix.risk.routes

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Position
import com.kinetix.proto.risk.HistoricalReplayResponse
import com.kinetix.proto.risk.InstrumentShock
import com.kinetix.proto.risk.ListScenariosResponse
import com.kinetix.proto.risk.PositionReplayImpact
import com.kinetix.proto.risk.ReverseStressResponse
import com.kinetix.proto.risk.StressTestServiceGrpcKt.StressTestServiceCoroutineStub
import com.kinetix.risk.cache.RedisTestSetup
import com.kinetix.risk.cache.RedisVaRCache
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.grpc.FakeStressTestService
import com.kinetix.risk.grpc.GrpcFakeServer
import com.kinetix.risk.service.VaRCalculationService
import com.google.protobuf.Timestamp
import com.kinetix.proto.common.AssetClass as ProtoAssetClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

class HistoricalReplayAcceptanceTest : FunSpec({

    val varCache = RedisVaRCache(RedisTestSetup.start())
    val positionProvider = mockk<PositionProvider>()
    val varCalculationService = mockk<VaRCalculationService>()

    val fakeStressService = FakeStressTestService()
    val grpcServer = GrpcFakeServer(fakeStressService)
    val stressTestStub = StressTestServiceCoroutineStub(grpcServer.channel())

    beforeEach {
        clearMocks(positionProvider)
        fakeStressService.runHistoricalReplayRequests.clear()
        fakeStressService.runReverseStressRequests.clear()
        fakeStressService.listScenariosRequests.clear()
        fakeStressService.listScenariosHandler = {
            ListScenariosResponse.newBuilder()
                .addAllScenarioNames(listOf("GFC_2008", "COVID_2020"))
                .build()
        }
        coEvery { positionProvider.getPositions(any()) } returns listOf(
            Position(
                bookId = BookId("port-1"),
                instrumentId = InstrumentId("AAPL"),
                assetClass = AssetClass.EQUITY,
                quantity = java.math.BigDecimal("100"),
                averageCost = com.kinetix.common.model.Money(java.math.BigDecimal("150.00"), java.util.Currency.getInstance("USD")),
                marketPrice = com.kinetix.common.model.Money(java.math.BigDecimal("155.00"), java.util.Currency.getInstance("USD")),
                instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
            )
        )
    }

    afterSpec {
        grpcServer.close()
    }

    fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) {
        testApplication {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing {
                riskRoutes(
                    varCalculationService = varCalculationService,
                    varCache = varCache,
                    positionProvider = positionProvider,
                    stressTestStub = stressTestStub,
                    regulatoryStub = mockk(),
                )
            }
            block()
        }
    }

    test("POST /api/v1/risk/stress/{bookId}/historical-replay returns 200 with position impacts") {
        fakeStressService.runHistoricalReplayHandler = {
            HistoricalReplayResponse.newBuilder()
                .setScenarioName("GFC_2008")
                .setTotalPnlImpact(-125_000.0)
                .addPositionImpacts(
                    PositionReplayImpact.newBuilder()
                        .setInstrumentId("AAPL")
                        .setAssetClass(ProtoAssetClass.EQUITY)
                        .setMarketValue(15_500.0)
                        .setPnlImpact(-125_000.0)
                        .addAllDailyPnl(listOf(-50_000.0, -25_000.0, 10_000.0, -40_000.0, -20_000.0))
                        .setProxyUsed(false)
                        .build()
                )
                .setWindowStart("2008-09-15")
                .setWindowEnd("2008-09-19")
                .setCalculatedAt(Timestamp.newBuilder().setSeconds(Instant.now().epochSecond))
                .build()
        }

        testApp {
            val response = client.post("/api/v1/risk/stress/port-1/historical-replay") {
                contentType(ContentType.Application.Json)
                setBody("""{"instrumentReturns": [], "windowStart": "2008-09-15", "windowEnd": "2008-09-19"}""")
            }
            response.status shouldBe HttpStatusCode.OK
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            json["scenarioName"]!!.jsonPrimitive.content shouldBe "GFC_2008"
            json["totalPnlImpact"]!!.jsonPrimitive.content shouldContain "-125000"
            json["windowStart"]!!.jsonPrimitive.content shouldBe "2008-09-15"
            val impacts = json["positionImpacts"]!!.jsonArray
            impacts.size shouldBe 1
            val impact = impacts[0].jsonObject
            impact["instrumentId"]!!.jsonPrimitive.content shouldBe "AAPL"
            impact["proxyUsed"]!!.jsonPrimitive.content shouldBe "false"
        }
    }

    test("POST /api/v1/risk/stress/{bookId}/historical-replay passes instrument returns to gRPC") {
        fakeStressService.runHistoricalReplayHandler = { req ->
            HistoricalReplayResponse.newBuilder()
                .setScenarioName(req.scenarioName)
                .setTotalPnlImpact(-50_000.0)
                .setCalculatedAt(Timestamp.newBuilder().setSeconds(Instant.now().epochSecond))
                .build()
        }

        testApp {
            val response = client.post("/api/v1/risk/stress/port-1/historical-replay") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                        "instrumentReturns": [
                            {"instrumentId": "AAPL", "dailyReturns": [-0.05, -0.03, 0.01, -0.04, -0.02]}
                        ],
                        "windowStart": "2008-09-15",
                        "windowEnd": "2008-09-19"
                    }
                    """.trimIndent()
                )
            }
            response.status shouldBe HttpStatusCode.OK
        }
    }

    test("POST /api/v1/risk/stress/{bookId}/reverse returns 200 with shock vector") {
        fakeStressService.runReverseStressHandler = {
            ReverseStressResponse.newBuilder()
                .addShocks(InstrumentShock.newBuilder().setInstrumentId("AAPL").setShock(-0.10))
                .setAchievedLoss(100_000.0)
                .setTargetLoss(100_000.0)
                .setConverged(true)
                .setCalculatedAt(Timestamp.newBuilder().setSeconds(Instant.now().epochSecond))
                .build()
        }

        testApp {
            val response = client.post("/api/v1/risk/stress/port-1/reverse") {
                contentType(ContentType.Application.Json)
                setBody("""{"targetLoss": 100000.0, "maxShock": -1.0}""")
            }
            response.status shouldBe HttpStatusCode.OK
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            json["converged"]!!.jsonPrimitive.content shouldBe "true"
            json["targetLoss"]!!.jsonPrimitive.content shouldContain "100000"
            val shocks = json["shocks"]!!.jsonArray
            shocks.size shouldBe 1
            shocks[0].jsonObject["instrumentId"]!!.jsonPrimitive.content shouldBe "AAPL"
        }
    }

    test("POST /api/v1/risk/stress/{bookId}/reverse with non-converged result returns 200 with converged=false") {
        fakeStressService.runReverseStressHandler = {
            ReverseStressResponse.newBuilder()
                .setAchievedLoss(1_500_000.0)
                .setTargetLoss(5_000_000.0)
                .setConverged(false)
                .setCalculatedAt(Timestamp.newBuilder().setSeconds(Instant.now().epochSecond))
                .build()
        }

        testApp {
            val response = client.post("/api/v1/risk/stress/port-1/reverse") {
                contentType(ContentType.Application.Json)
                setBody("""{"targetLoss": 5000000.0}""")
            }
            response.status shouldBe HttpStatusCode.OK
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            json["converged"]!!.jsonPrimitive.content shouldBe "false"
        }
    }
})

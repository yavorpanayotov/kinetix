package com.kinetix.risk.routes

import com.google.protobuf.Timestamp
import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Position
import com.kinetix.proto.risk.AssetClassImpact
import com.kinetix.proto.risk.ListScenariosResponse
import com.kinetix.proto.risk.StressTestResponse
import com.kinetix.proto.risk.StressTestServiceGrpcKt.StressTestServiceCoroutineStub
import com.kinetix.risk.cache.RedisVaRCache
import com.kinetix.risk.cache.RedisTestSetup
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.grpc.FakeStressTestService
import com.kinetix.risk.grpc.GrpcFakeServer
import com.kinetix.risk.service.BatchStressTestService
import com.kinetix.risk.service.VaRCalculationService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
import java.math.BigDecimal
import java.util.Currency
import com.kinetix.proto.common.AssetClass as ProtoAssetClass
import java.time.Instant

private val USD = Currency.getInstance("USD")

private fun position() = Position(
    bookId = BookId("book-1"),
    instrumentId = InstrumentId("AAPL"),
    assetClass = AssetClass.EQUITY,
    quantity = BigDecimal("100"),
    averageCost = Money(BigDecimal("150.00"), USD),
    marketPrice = Money(BigDecimal("155.00"), USD),
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

private fun stressResponse(scenarioName: String, pnlImpact: Double) =
    StressTestResponse.newBuilder()
        .setScenarioName(scenarioName)
        .setBaseVar(50_000.0)
        .setStressedVar(80_000.0)
        .setPnlImpact(pnlImpact)
        .addAssetClassImpacts(
            AssetClassImpact.newBuilder()
                .setAssetClass(ProtoAssetClass.EQUITY)
                .setBaseExposure(1_000_000.0)
                .setStressedExposure(1_000_000.0 + pnlImpact)
                .setPnlImpact(pnlImpact)
                .build()
        )
        .setCalculatedAt(Timestamp.newBuilder().setSeconds(Instant.now().epochSecond).build())
        .build()

class BatchStressTestAcceptanceTest : FunSpec({

    val varCache = RedisVaRCache(RedisTestSetup.start())
    val positionProvider = mockk<PositionProvider>()
    val varCalculationService = mockk<VaRCalculationService>()

    val fakeStressService = FakeStressTestService()
    val grpcServer = GrpcFakeServer(fakeStressService)
    val stressTestStub = StressTestServiceCoroutineStub(grpcServer.channel())
    val batchService = BatchStressTestService(stressTestStub, positionProvider)

    beforeEach {
        clearMocks(positionProvider, varCalculationService)
        fakeStressService.runStressTestRequests.clear()
        fakeStressService.listScenariosRequests.clear()
        fakeStressService.listScenariosHandler = {
            ListScenariosResponse.newBuilder()
                .addAllScenarioNames(listOf("GFC_2008", "COVID_2020"))
                .build()
        }
        coEvery { positionProvider.getPositions(any()) } returns listOf(position())
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
                    batchStressTestService = batchService,
                )
            }
            block()
        }
    }

    test("POST /api/v1/risk/stress/{bookId}/batch returns 200 with ranked results") {
        fakeStressService.runStressTestHandler = { req ->
            val pnl = when (req.scenarioName) {
                "GFC_2008" -> -400_000.0
                "COVID_2020" -> -150_000.0
                else -> -10_000.0
            }
            stressResponse(req.scenarioName, pnl)
        }

        testApp {
            val response = client.post("/api/v1/risk/stress/book-1/batch") {
                contentType(ContentType.Application.Json)
                setBody("""{"scenarioNames": ["COVID_2020", "GFC_2008"]}""")
            }

            response.status shouldBe HttpStatusCode.OK
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val results = json["results"]!!.jsonArray
            results.size shouldBe 2
            // First result should be the worst (most negative P&L)
            results[0].jsonObject["scenarioName"]!!.jsonPrimitive.content shouldBe "GFC_2008"
            results[1].jsonObject["scenarioName"]!!.jsonPrimitive.content shouldBe "COVID_2020"
        }
    }

    test("POST /api/v1/risk/stress/{bookId}/batch reports failed scenarios without aborting") {
        fakeStressService.runStressTestHandler = { req ->
            if (req.scenarioName == "BROKEN") throw RuntimeException("engine unavailable")
            stressResponse(req.scenarioName, -100_000.0)
        }

        testApp {
            val response = client.post("/api/v1/risk/stress/book-1/batch") {
                contentType(ContentType.Application.Json)
                setBody("""{"scenarioNames": ["GFC_2008", "BROKEN"]}""")
            }

            response.status shouldBe HttpStatusCode.OK
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val results = json["results"]!!.jsonArray
            results.size shouldBe 1
            val failures = json["failedScenarios"]!!.jsonArray
            failures.size shouldBe 1
            failures[0].jsonObject["scenarioName"]!!.jsonPrimitive.content shouldBe "BROKEN"
        }
    }

    test("POST /api/v1/risk/stress/{bookId}/batch returns worstScenarioName in summary") {
        fakeStressService.runStressTestHandler = { req ->
            stressResponse(req.scenarioName, -300_000.0)
        }

        testApp {
            val response = client.post("/api/v1/risk/stress/book-1/batch") {
                contentType(ContentType.Application.Json)
                setBody("""{"scenarioNames": ["GFC_2008"]}""")
            }

            response.status shouldBe HttpStatusCode.OK
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            json["worstScenarioName"]!!.jsonPrimitive.content shouldBe "GFC_2008"
        }
    }

    test("POST /api/v1/risk/stress/{bookId}/batch with empty scenario list returns empty result") {
        testApp {
            val response = client.post("/api/v1/risk/stress/book-1/batch") {
                contentType(ContentType.Application.Json)
                setBody("""{"scenarioNames": []}""")
            }

            response.status shouldBe HttpStatusCode.OK
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            json["results"]!!.jsonArray.size shouldBe 0
            json["failedScenarios"]!!.jsonArray.size shouldBe 0
        }
    }
})

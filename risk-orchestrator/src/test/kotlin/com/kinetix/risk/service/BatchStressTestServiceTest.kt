package com.kinetix.risk.service

import com.google.protobuf.Timestamp
import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Position
import com.kinetix.proto.risk.AssetClassImpact
import com.kinetix.proto.risk.ListScenariosRequest
import com.kinetix.proto.risk.ListScenariosResponse
import com.kinetix.proto.risk.StressTestRequest
import com.kinetix.proto.risk.StressTestResponse
import com.kinetix.proto.risk.StressTestServiceGrpcKt.StressTestServiceCoroutineStub
import com.kinetix.risk.client.PositionProvider
import com.kinetix.common.model.AssetClass as ProtoMappedAssetClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.math.BigDecimal
import java.util.Currency
import com.kinetix.proto.common.AssetClass as ProtoAssetClass
import java.time.Instant

private val USD = Currency.getInstance("USD")

private fun position(instrumentId: String = "AAPL", assetClass: AssetClass = AssetClass.EQUITY) =
    Position(
        bookId = BookId("book-1"),
        instrumentId = InstrumentId(instrumentId),
        assetClass = assetClass,
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

class BatchStressTestServiceTest : FunSpec({

    val stressTestStub = mockk<StressTestServiceCoroutineStub>()
    val positionProvider = mockk<PositionProvider>()
    val service = BatchStressTestService(stressTestStub, positionProvider)
    val positions = listOf(position("AAPL"), position("MSFT"))

    beforeEach {
        clearMocks(stressTestStub, positionProvider)
        coEvery { positionProvider.getPositions(BookId("book-1")) } returns positions
    }

    test("runs all provided scenarios and returns one result per scenario") {
        coEvery { stressTestStub.runStressTest(any(), any()) } answers {
            val req = firstArg<StressTestRequest>()
            stressResponse(req.scenarioName, -100_000.0)
        }

        val result = service.runBatch(
            bookId = BookId("book-1"),
            scenarioNames = listOf("GFC_2008", "COVID_2020", "TAPER_TANTRUM_2013"),
        )

        result.results shouldHaveSize 3
    }

    test("results are ranked from worst to best P&L impact") {
        coEvery { stressTestStub.runStressTest(any(), any()) } answers {
            val req = firstArg<StressTestRequest>()
            val pnl = when (req.scenarioName) {
                "GFC_2008" -> -400_000.0
                "COVID_2020" -> -150_000.0
                "TAPER_TANTRUM_2013" -> -50_000.0
                else -> -10_000.0
            }
            stressResponse(req.scenarioName, pnl)
        }

        val result = service.runBatch(
            bookId = BookId("book-1"),
            scenarioNames = listOf("TAPER_TANTRUM_2013", "COVID_2020", "GFC_2008"),
        )

        result.results shouldHaveSize 3
        result.results[0].scenarioName shouldBe "GFC_2008"
        result.results[1].scenarioName shouldBe "COVID_2020"
        result.results[2].scenarioName shouldBe "TAPER_TANTRUM_2013"
    }

    test("base VaR is computed once and shared across all scenario results") {
        coEvery { stressTestStub.runStressTest(any(), any()) } answers {
            val req = firstArg<StressTestRequest>()
            stressResponse(req.scenarioName, -100_000.0)
        }

        val result = service.runBatch(
            bookId = BookId("book-1"),
            scenarioNames = listOf("GFC_2008", "COVID_2020"),
        )

        // All scenario results share the same base VaR value
        val baseVars = result.results.map { it.baseVar }.distinct()
        baseVars shouldHaveSize 1
    }

    test("individual scenario failure does not abort the batch") {
        coEvery { stressTestStub.runStressTest(any(), any()) } answers {
            val req = firstArg<StressTestRequest>()
            if (req.scenarioName == "BROKEN_SCENARIO") {
                throw RuntimeException("gRPC failure for BROKEN_SCENARIO")
            }
            stressResponse(req.scenarioName, -100_000.0)
        }

        val result = service.runBatch(
            bookId = BookId("book-1"),
            scenarioNames = listOf("GFC_2008", "BROKEN_SCENARIO", "COVID_2020"),
        )

        // Only successful scenarios appear in results
        result.results shouldHaveSize 2
        result.results.none { it.scenarioName == "BROKEN_SCENARIO" } shouldBe true
        result.failedScenarios shouldHaveSize 1
        result.failedScenarios[0].scenarioName shouldBe "BROKEN_SCENARIO"
        result.failedScenarios[0].errorMessage shouldContain "gRPC failure"
    }

    test("positions are fetched once and reused across all scenario calls") {
        coEvery { stressTestStub.runStressTest(any(), any()) } answers {
            val req = firstArg<StressTestRequest>()
            stressResponse(req.scenarioName, -100_000.0)
        }

        service.runBatch(
            bookId = BookId("book-1"),
            scenarioNames = listOf("GFC_2008", "COVID_2020", "TAPER_TANTRUM_2013"),
        )

        // Position provider called exactly once regardless of scenario count
        coVerify(exactly = 1) { positionProvider.getPositions(BookId("book-1")) }
    }

    test("returns empty results list when no scenarios are provided") {
        val result = service.runBatch(
            bookId = BookId("book-1"),
            scenarioNames = emptyList(),
        )

        result.results shouldHaveSize 0
        result.failedScenarios shouldHaveSize 0
    }

    test("worst P&L is correctly identified in summary") {
        coEvery { stressTestStub.runStressTest(any(), any()) } answers {
            val req = firstArg<StressTestRequest>()
            val pnl = when (req.scenarioName) {
                "GFC_2008" -> -400_000.0
                else -> -100_000.0
            }
            stressResponse(req.scenarioName, pnl)
        }

        val result = service.runBatch(
            bookId = BookId("book-1"),
            scenarioNames = listOf("GFC_2008", "COVID_2020"),
        )

        result.worstScenarioName shouldBe "GFC_2008"
        result.worstPnlImpact shouldBe "-400000.00"
    }

    test("scenario results include the base VaR in the response") {
        coEvery { stressTestStub.runStressTest(any(), any()) } answers {
            val req = firstArg<StressTestRequest>()
            // The proto stub returns base_var for each scenario
            StressTestResponse.newBuilder()
                .setScenarioName(req.scenarioName)
                .setBaseVar(75_000.0)
                .setStressedVar(120_000.0)
                .setPnlImpact(-200_000.0)
                .setCalculatedAt(Timestamp.newBuilder().setSeconds(Instant.now().epochSecond).build())
                .build()
        }

        val result = service.runBatch(
            bookId = BookId("book-1"),
            scenarioNames = listOf("GFC_2008"),
        )

        result.results[0].baseVar shouldBe "75000.00"
    }
})

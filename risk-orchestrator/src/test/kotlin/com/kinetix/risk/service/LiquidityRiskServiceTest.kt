package com.kinetix.risk.service

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.LiquidityRiskResult
import com.kinetix.common.model.LiquidityTier
import com.kinetix.common.model.Money
import com.kinetix.common.model.Position
import com.kinetix.common.model.PositionLiquidityRisk
import com.kinetix.risk.client.GrpcLiquidityClient
import com.kinetix.risk.client.LiquidityInputRequest
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.client.ReferenceDataServiceClient
import com.kinetix.risk.client.dtos.InstrumentLiquidityDto
import com.kinetix.risk.kafka.LiquidityConcentrationAlertPublisher
import com.kinetix.risk.persistence.LiquidityRiskSnapshotRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.math.BigDecimal
import java.util.Currency

private val USD = Currency.getInstance("USD")

private fun position(
    instrumentId: String = "AAPL",
    assetClass: AssetClass = AssetClass.EQUITY,
    quantity: String = "100",
    marketPrice: String = "170.00",
) = Position(
    bookId = BookId("BOOK-1"),
    instrumentId = InstrumentId(instrumentId),
    assetClass = assetClass,
    quantity = BigDecimal(quantity),
    averageCost = Money(BigDecimal("150.00"), USD),
    marketPrice = Money(BigDecimal(marketPrice), USD),
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

private fun liquidityDto(
    instrumentId: String = "AAPL",
    adv: Double = 10_000_000.0,
    advStale: Boolean = false,
    advStalenessDays: Int = 0,
) = InstrumentLiquidityDto(
    instrumentId = instrumentId,
    adv = adv,
    bidAskSpreadBps = 5.0,
    assetClass = "EQUITY",
    advUpdatedAt = "2026-03-24T09:00:00Z",
    advStale = advStale,
    advStalenessDays = advStalenessDays,
    createdAt = "2026-03-24T09:00:00Z",
    updatedAt = "2026-03-24T09:00:00Z",
)

private fun sampleLiquidityResult(bookId: String = "BOOK-1") = LiquidityRiskResult(
    bookId = bookId,
    portfolioLvar = 316_227.76,
    dataCompleteness = 0.85,
    positionRisks = listOf(
        PositionLiquidityRisk(
            instrumentId = "AAPL",
            assetClass = "EQUITY",
            marketValue = 17_000.0,
            tier = LiquidityTier.HIGH_LIQUID,
            horizonDays = 1,
            adv = 10_000_000.0,
            advMissing = false,
            advStale = false,
            lvarContribution = 316_227.76,
            stressedLiquidationValue = 16_500.0,
            concentrationStatus = "OK",
        )
    ),
    portfolioConcentrationStatus = "OK",
    calculatedAt = "2026-03-24T10:00:00Z",
)

class LiquidityRiskServiceTest : FunSpec({

    val positionProvider = mockk<PositionProvider>()
    val referenceDataClient = mockk<ReferenceDataServiceClient>()
    val grpcClient = mockk<GrpcLiquidityClient>()
    val repository = mockk<LiquidityRiskSnapshotRepository>(relaxed = true)

    val service = LiquidityRiskService(
        positionProvider = positionProvider,
        referenceDataClient = referenceDataClient,
        grpcLiquidityClient = grpcClient,
        repository = repository,
    )

    val bookId = BookId("BOOK-1")

    test("returns null when the book has no positions") {
        coEvery { positionProvider.getPositions(bookId) } returns emptyList()

        val result = service.calculateAndSave(bookId, baseVar = 50_000.0)

        result.shouldBeNull()
        coVerify(exactly = 0) { grpcClient.calculateLiquidityAdjustedVaR(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.save(any()) }
    }

    test("fetches ADV data for all positions in the book") {
        val positions = listOf(
            position(instrumentId = "AAPL"),
            position(instrumentId = "MSFT"),
        )
        coEvery { positionProvider.getPositions(bookId) } returns positions
        coEvery { referenceDataClient.getLiquidityDataBatch(listOf("AAPL", "MSFT")) } returns
            mapOf(
                "AAPL" to liquidityDto(instrumentId = "AAPL"),
                "MSFT" to liquidityDto(instrumentId = "MSFT", adv = 8_000_000.0),
            )
        coEvery { grpcClient.calculateLiquidityAdjustedVaR(any(), any(), any(), any(), any(), any()) } returns
            sampleLiquidityResult()

        service.calculateAndSave(bookId, baseVar = 50_000.0)

        coVerify { referenceDataClient.getLiquidityDataBatch(listOf("AAPL", "MSFT")) }
    }

    test("passes instrument IDs with missing ADV data as advMissing=true to the gRPC client") {
        val positions = listOf(position(instrumentId = "AAPL"))
        coEvery { positionProvider.getPositions(bookId) } returns positions
        coEvery { referenceDataClient.getLiquidityDataBatch(listOf("AAPL")) } returns emptyMap()

        val inputsSlot = slot<List<LiquidityInputRequest>>()
        coEvery {
            grpcClient.calculateLiquidityAdjustedVaR(any(), any(), any(), capture(inputsSlot), any(), any())
        } returns sampleLiquidityResult()

        service.calculateAndSave(bookId, baseVar = 50_000.0)

        val capturedInputs = inputsSlot.captured
        capturedInputs.size shouldBe 1
        capturedInputs[0].liquidityDto.shouldBeNull()
        capturedInputs[0].instrumentId shouldBe "AAPL"
    }

    test("assembles LiquidityInputRequest with correct market value and ADV from position and DTO") {
        val positions = listOf(position(instrumentId = "AAPL", quantity = "100", marketPrice = "170.00"))
        coEvery { positionProvider.getPositions(bookId) } returns positions
        coEvery { referenceDataClient.getLiquidityDataBatch(listOf("AAPL")) } returns
            mapOf("AAPL" to liquidityDto(instrumentId = "AAPL", adv = 10_000_000.0))

        val inputsSlot = slot<List<LiquidityInputRequest>>()
        coEvery {
            grpcClient.calculateLiquidityAdjustedVaR(any(), any(), any(), capture(inputsSlot), any(), any())
        } returns sampleLiquidityResult()

        service.calculateAndSave(bookId, baseVar = 50_000.0)

        val inp = inputsSlot.captured.single()
        inp.instrumentId shouldBe "AAPL"
        inp.marketValue shouldBeExactly 17_000.0
        inp.liquidityDto.shouldNotBeNull()
        inp.liquidityDto!!.adv shouldBeExactly 10_000_000.0
    }

    test("passes baseVar to the gRPC client") {
        val positions = listOf(position())
        coEvery { positionProvider.getPositions(bookId) } returns positions
        coEvery { referenceDataClient.getLiquidityDataBatch(any()) } returns
            mapOf("AAPL" to liquidityDto())

        val baseVarSlot = slot<Double>()
        coEvery {
            grpcClient.calculateLiquidityAdjustedVaR(any(), capture(baseVarSlot), any(), any(), any(), any())
        } returns sampleLiquidityResult()

        service.calculateAndSave(bookId, baseVar = 75_000.0)

        baseVarSlot.captured shouldBeExactly 75_000.0
    }

    test("saves the result to the repository after a successful gRPC call") {
        val positions = listOf(position())
        coEvery { positionProvider.getPositions(bookId) } returns positions
        coEvery { referenceDataClient.getLiquidityDataBatch(any()) } returns
            mapOf("AAPL" to liquidityDto())

        val expectedResult = sampleLiquidityResult()
        coEvery { grpcClient.calculateLiquidityAdjustedVaR(any(), any(), any(), any(), any(), any()) } returns expectedResult

        service.calculateAndSave(bookId, baseVar = 50_000.0)

        coVerify { repository.save(expectedResult) }
    }

    test("publishes LIQUIDITY_CONCENTRATION alert when concentrationCount is greater than zero") {
        val alertPublisher = mockk<LiquidityConcentrationAlertPublisher>(relaxed = true)
        val serviceWithPublisher = LiquidityRiskService(
            positionProvider = positionProvider,
            referenceDataClient = referenceDataClient,
            grpcLiquidityClient = grpcClient,
            repository = repository,
            concentrationAlertPublisher = alertPublisher,
        )
        val positions = listOf(position())
        coEvery { positionProvider.getPositions(bookId) } returns positions
        coEvery { referenceDataClient.getLiquidityDataBatch(any()) } returns mapOf("AAPL" to liquidityDto())
        val concentratedResult = sampleLiquidityResult().copy(
            concentrationCount = 2,
            portfolioConcentrationStatus = "BREACHED",
        )
        coEvery { grpcClient.calculateLiquidityAdjustedVaR(any(), any(), any(), any(), any(), any()) } returns concentratedResult

        serviceWithPublisher.calculateAndSave(bookId, baseVar = 50_000.0)

        coVerify(exactly = 1) { alertPublisher.publishConcentrationAlert(concentratedResult) }
    }

    test("does not publish LIQUIDITY_CONCENTRATION alert when concentrationCount is zero") {
        val alertPublisher = mockk<LiquidityConcentrationAlertPublisher>(relaxed = true)
        val serviceWithPublisher = LiquidityRiskService(
            positionProvider = positionProvider,
            referenceDataClient = referenceDataClient,
            grpcLiquidityClient = grpcClient,
            repository = repository,
            concentrationAlertPublisher = alertPublisher,
        )
        val positions = listOf(position())
        coEvery { positionProvider.getPositions(bookId) } returns positions
        coEvery { referenceDataClient.getLiquidityDataBatch(any()) } returns mapOf("AAPL" to liquidityDto())
        val okResult = sampleLiquidityResult().copy(concentrationCount = 0, portfolioConcentrationStatus = "OK")
        coEvery { grpcClient.calculateLiquidityAdjustedVaR(any(), any(), any(), any(), any(), any()) } returns okResult

        serviceWithPublisher.calculateAndSave(bookId, baseVar = 50_000.0)

        coVerify(exactly = 0) { alertPublisher.publishConcentrationAlert(any()) }
    }

    test("returns the LiquidityRiskResult from the gRPC client") {
        val positions = listOf(position())
        coEvery { positionProvider.getPositions(bookId) } returns positions
        coEvery { referenceDataClient.getLiquidityDataBatch(any()) } returns
            mapOf("AAPL" to liquidityDto())

        val expectedResult = sampleLiquidityResult()
        coEvery { grpcClient.calculateLiquidityAdjustedVaR(any(), any(), any(), any(), any(), any()) } returns expectedResult

        val result = service.calculateAndSave(bookId, baseVar = 50_000.0)

        result shouldBe expectedResult
    }

    test("uses asset class from position for LiquidityInputRequest") {
        val positions = listOf(position(instrumentId = "TLT", assetClass = AssetClass.FIXED_INCOME))
        coEvery { positionProvider.getPositions(bookId) } returns positions
        coEvery { referenceDataClient.getLiquidityDataBatch(any()) } returns emptyMap()

        val inputsSlot = slot<List<LiquidityInputRequest>>()
        coEvery {
            grpcClient.calculateLiquidityAdjustedVaR(any(), any(), any(), capture(inputsSlot), any(), any())
        } returns sampleLiquidityResult()

        service.calculateAndSave(bookId, baseVar = 50_000.0)

        inputsSlot.captured.single().assetClass shouldBe "FIXED_INCOME"
    }
})

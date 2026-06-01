package com.kinetix.risk.service

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Position
import com.kinetix.risk.client.AttributionEngineClient
import com.kinetix.risk.client.BenchmarkServiceClient
import com.kinetix.risk.client.ClientResponse
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.client.SectorInput
import com.kinetix.risk.client.dtos.BenchmarkConstituentDto
import com.kinetix.risk.client.dtos.BenchmarkDetailDto
import com.kinetix.risk.model.BrinsonAttributionResult
import com.kinetix.risk.model.BrinsonSectorAttribution
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val BOOK_ID = BookId("BOOK-EQ-01")
private val TODAY = LocalDate.of(2026, 3, 25)
private val USD = Currency.getInstance("USD")

// marketValue = marketPrice * quantity; to get marketValue=X with qty=1, set marketPrice=X
private fun position(instrumentId: String, marketValue: Double) = Position(
    bookId = BOOK_ID,
    instrumentId = InstrumentId(instrumentId),
    quantity = BigDecimal.ONE,
    averageCost = Money(BigDecimal.valueOf(marketValue), USD),
    marketPrice = Money(BigDecimal.valueOf(marketValue), USD),
    assetClass = AssetClass.EQUITY,
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

private val POSITIONS = listOf(
    position("AAPL", 700_000.0),
    position("MSFT", 650_000.0),
    position("NVDA", 600_000.0),
    position("OTHER", 50_000.0),
)

private val BENCHMARK_DETAIL = BenchmarkDetailDto(
    benchmarkId = "SP500",
    name = "S&P 500",
    description = "Large-cap US equity index",
    createdAt = "2026-01-01T00:00:00Z",
    constituents = listOf(
        BenchmarkConstituentDto("AAPL", "0.0700", "2026-03-25"),
        BenchmarkConstituentDto("MSFT", "0.0650", "2026-03-25"),
        BenchmarkConstituentDto("NVDA", "0.0600", "2026-03-25"),
    ),
)

private val ATTRIBUTION_RESULT = BrinsonAttributionResult(
    sectors = listOf(
        BrinsonSectorAttribution(
            sectorLabel = "AAPL",
            portfolioWeight = 0.35,
            benchmarkWeight = 0.07,
            portfolioReturn = 0.12,
            benchmarkReturn = 0.10,
            allocationEffect = 0.028,
            selectionEffect = 0.014,
            interactionEffect = 0.004,
            totalActiveContribution = 0.046,
        ),
    ),
    totalActiveReturn = 0.046,
    totalAllocationEffect = 0.028,
    totalSelectionEffect = 0.014,
    totalInteractionEffect = 0.004,
)

class BenchmarkAttributionServiceTest : FunSpec({

    val positionProvider = mockk<PositionProvider>()
    val benchmarkServiceClient = mockk<BenchmarkServiceClient>()
    val attributionEngineClient = mockk<AttributionEngineClient>()
    val service = BenchmarkAttributionService(
        positionProvider = positionProvider,
        benchmarkServiceClient = benchmarkServiceClient,
        attributionEngineClient = attributionEngineClient,
    )

    beforeEach { clearMocks(positionProvider, benchmarkServiceClient, attributionEngineClient) }

    context("calculateAttribution") {

        test("fetches positions, benchmark constituents, and delegates to attribution engine") {
            coEvery { positionProvider.getPositions(BOOK_ID) } returns POSITIONS
            coEvery {
                benchmarkServiceClient.getBenchmarkDetail("SP500", TODAY)
            } returns ClientResponse.Success(BENCHMARK_DETAIL)
            coEvery { attributionEngineClient.calculateBrinsonAttribution(any()) } returns ATTRIBUTION_RESULT

            val result = service.calculateAttribution(
                bookId = BOOK_ID,
                benchmarkId = "SP500",
                asOfDate = TODAY,
            )

            result shouldNotBe null
            result.sectors.size shouldBe 1
            result.sectors[0].sectorLabel shouldBe "AAPL"
            result.totalActiveReturn shouldBe 0.046
        }

        test("portfolio weights are computed from market values relative to total") {
            coEvery { positionProvider.getPositions(BOOK_ID) } returns POSITIONS
            coEvery {
                benchmarkServiceClient.getBenchmarkDetail("SP500", TODAY)
            } returns ClientResponse.Success(BENCHMARK_DETAIL)

            val capturedSectors = mutableListOf<List<com.kinetix.risk.client.SectorInput>>()
            coEvery { attributionEngineClient.calculateBrinsonAttribution(any()) } answers {
                capturedSectors.add(firstArg())
                ATTRIBUTION_RESULT
            }

            service.calculateAttribution(BOOK_ID, "SP500", TODAY)

            val sectors = capturedSectors.first()
            // Total market value = 700k + 650k + 600k + 50k = 2_000_000
            // AAPL portfolio weight = 700_000 / 2_000_000 = 0.35
            val aaplSector = sectors.find { it.sectorLabel == "AAPL" }
            aaplSector shouldNotBe null
            aaplSector!!.portfolioWeight shouldBe 0.35
        }

        test("benchmark weights are taken from benchmark constituents") {
            coEvery { positionProvider.getPositions(BOOK_ID) } returns POSITIONS
            coEvery {
                benchmarkServiceClient.getBenchmarkDetail("SP500", TODAY)
            } returns ClientResponse.Success(BENCHMARK_DETAIL)

            val capturedSectors = mutableListOf<List<com.kinetix.risk.client.SectorInput>>()
            coEvery { attributionEngineClient.calculateBrinsonAttribution(any()) } answers {
                capturedSectors.add(firstArg())
                ATTRIBUTION_RESULT
            }

            service.calculateAttribution(BOOK_ID, "SP500", TODAY)

            val sectors = capturedSectors.first()
            val msftSector = sectors.find { it.sectorLabel == "MSFT" }
            msftSector shouldNotBe null
            // Weights are normalised over the book's benchmark overlap (AAPL+MSFT+NVDA
            // = 0.07+0.065+0.06 = 0.195), so MSFT = 0.065 / 0.195.
            msftSector!!.benchmarkWeight shouldBe (0.065 / 0.195 plusOrMinus 1e-9)
        }

        test("benchmark weights are normalised to sum to 1.0 over the book's benchmark overlap") {
            coEvery { positionProvider.getPositions(BOOK_ID) } returns POSITIONS
            coEvery {
                benchmarkServiceClient.getBenchmarkDetail("SP500", TODAY)
            } returns ClientResponse.Success(BENCHMARK_DETAIL)

            val capturedSectors = mutableListOf<List<com.kinetix.risk.client.SectorInput>>()
            coEvery { attributionEngineClient.calculateBrinsonAttribution(any()) } answers {
                capturedSectors.add(firstArg())
                ATTRIBUTION_RESULT
            }

            service.calculateAttribution(BOOK_ID, "SP500", TODAY)

            // The risk-engine Brinson endpoint requires the benchmark weight vector to
            // be a distribution summing to 1.0; otherwise it rejects with INVALID_ARGUMENT.
            val sectors = capturedSectors.first()
            sectors.sumOf { it.benchmarkWeight } shouldBe (1.0 plusOrMinus 1e-9)
        }

        test("throws when the book holds none of the benchmark constituents") {
            val offBenchmark = listOf(position("OTHER", 100_000.0))
            coEvery { positionProvider.getPositions(BOOK_ID) } returns offBenchmark
            coEvery {
                benchmarkServiceClient.getBenchmarkDetail("SP500", TODAY)
            } returns ClientResponse.Success(BENCHMARK_DETAIL)

            try {
                service.calculateAttribution(BOOK_ID, "SP500", TODAY)
                throw AssertionError("Expected exception was not thrown")
            } catch (e: IllegalArgumentException) {
                e.message shouldNotBe null
            }
        }

        test("instruments in portfolio but not in benchmark have zero benchmark weight") {
            coEvery { positionProvider.getPositions(BOOK_ID) } returns POSITIONS
            coEvery {
                benchmarkServiceClient.getBenchmarkDetail("SP500", TODAY)
            } returns ClientResponse.Success(BENCHMARK_DETAIL)

            val capturedSectors = mutableListOf<List<com.kinetix.risk.client.SectorInput>>()
            coEvery { attributionEngineClient.calculateBrinsonAttribution(any()) } answers {
                capturedSectors.add(firstArg())
                ATTRIBUTION_RESULT
            }

            service.calculateAttribution(BOOK_ID, "SP500", TODAY)

            val sectors = capturedSectors.first()
            val otherSector = sectors.find { it.sectorLabel == "OTHER" }
            otherSector shouldNotBe null
            otherSector!!.benchmarkWeight shouldBe 0.0
        }

        test("when benchmark not found, throws IllegalArgumentException") {
            coEvery { positionProvider.getPositions(BOOK_ID) } returns POSITIONS
            coEvery {
                benchmarkServiceClient.getBenchmarkDetail("MISSING", TODAY)
            } returns ClientResponse.NotFound(404)

            try {
                service.calculateAttribution(BOOK_ID, "MISSING", TODAY)
                throw AssertionError("Expected exception was not thrown")
            } catch (e: IllegalArgumentException) {
                e.message shouldNotBe null
            }
        }

        test("when book has no positions, throws IllegalArgumentException") {
            coEvery { positionProvider.getPositions(BOOK_ID) } returns emptyList()

            try {
                service.calculateAttribution(BOOK_ID, "SP500", TODAY)
                throw AssertionError("Expected exception was not thrown")
            } catch (e: IllegalArgumentException) {
                e.message shouldNotBe null
            }
        }

        test("calls attribution engine exactly once") {
            coEvery { positionProvider.getPositions(BOOK_ID) } returns POSITIONS
            coEvery {
                benchmarkServiceClient.getBenchmarkDetail("SP500", TODAY)
            } returns ClientResponse.Success(BENCHMARK_DETAIL)
            coEvery { attributionEngineClient.calculateBrinsonAttribution(any()) } returns ATTRIBUTION_RESULT

            service.calculateAttribution(BOOK_ID, "SP500", TODAY)

            coVerify(exactly = 1) { attributionEngineClient.calculateBrinsonAttribution(any()) }
        }
    }
})

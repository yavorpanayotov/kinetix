package com.kinetix.risk.service

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Position
import com.kinetix.risk.client.GrpcKrdClient
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.client.RatesServiceClient
import com.kinetix.risk.client.ClientResponse
import com.kinetix.risk.model.KrdBucket
import com.kinetix.risk.model.InstrumentKrdResult
import com.kinetix.common.model.Tenor
import com.kinetix.common.model.YieldCurve
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

private val USD = Currency.getInstance("USD")

private fun bondPosition(
    instrumentId: String = "UST-10Y",
    bookId: String = "BOOK-1",
) = Position(
    bookId = BookId(bookId),
    instrumentId = InstrumentId(instrumentId),
    assetClass = AssetClass.FIXED_INCOME,
    quantity = BigDecimal("1"),
    averageCost = Money(BigDecimal("1000000"), USD),
    marketPrice = Money(BigDecimal("1000000"), USD),
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

private fun equityPosition(
    instrumentId: String = "AAPL",
    bookId: String = "BOOK-1",
) = Position(
    bookId = BookId(bookId),
    instrumentId = InstrumentId(instrumentId),
    assetClass = AssetClass.EQUITY,
    quantity = BigDecimal("100"),
    averageCost = Money(BigDecimal("150"), USD),
    marketPrice = Money(BigDecimal("170"), USD),
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

private fun flatYieldCurve() = YieldCurve(
    currency = USD,
    asOf = Instant.parse("2026-03-25T00:00:00Z"),
    tenors = listOf(
        Tenor("2Y", 730, BigDecimal("0.045")),
        Tenor("5Y", 1825, BigDecimal("0.05")),
        Tenor("10Y", 3650, BigDecimal("0.055")),
        Tenor("30Y", 10950, BigDecimal("0.06")),
    ),
    curveId = "USD-SWAP",
    source = com.kinetix.common.model.RateSource.BLOOMBERG,
)

private fun krdResult(instrumentId: String = "UST-10Y") = InstrumentKrdResult(
    instrumentId = instrumentId,
    krdBuckets = listOf(
        KrdBucket("2Y", 730, BigDecimal("50.12")),
        KrdBucket("5Y", 1825, BigDecimal("150.34")),
        KrdBucket("10Y", 3650, BigDecimal("600.55")),
        KrdBucket("30Y", 10950, BigDecimal("10.01")),
    ),
    totalDv01 = BigDecimal("810.02"),
)

class KeyRateDurationServiceTest : FunSpec({

    val positionProvider = mockk<PositionProvider>()
    val ratesServiceClient = mockk<RatesServiceClient>()
    val grpcKrdClient = mockk<GrpcKrdClient>()
    val service = KeyRateDurationService(positionProvider, ratesServiceClient, grpcKrdClient)

    test("returns empty list when book has no fixed-income positions") {
        coEvery { positionProvider.getPositions(BookId("BOOK-1")) } returns listOf(equityPosition())

        val results = service.calculate(BookId("BOOK-1"))

        results shouldHaveSize 0
    }

    test("skips equity positions and only processes fixed-income") {
        coEvery { positionProvider.getPositions(BookId("BOOK-1")) } returns listOf(
            equityPosition("AAPL"),
            bondPosition("UST-10Y"),
        )
        coEvery { ratesServiceClient.getLatestYieldCurve("USD-SWAP") } returns ClientResponse.Success(flatYieldCurve())
        coEvery { grpcKrdClient.calculateKrd(any()) } returns krdResult("UST-10Y")

        val results = service.calculate(BookId("BOOK-1"))

        results shouldHaveSize 1
        results[0].instrumentId shouldBe "UST-10Y"
    }

    test("returns KRD result per fixed-income instrument") {
        coEvery { positionProvider.getPositions(BookId("BOOK-1")) } returns listOf(
            bondPosition("UST-10Y"),
            bondPosition("UST-5Y"),
        )
        coEvery { ratesServiceClient.getLatestYieldCurve("USD-SWAP") } returns ClientResponse.Success(flatYieldCurve())
        coEvery { grpcKrdClient.calculateKrd(any()) } returnsMany listOf(
            krdResult("UST-10Y"),
            krdResult("UST-5Y"),
        )

        val results = service.calculate(BookId("BOOK-1"))

        results shouldHaveSize 2
    }

    test("each result has four KRD buckets for standard tenors") {
        coEvery { positionProvider.getPositions(BookId("BOOK-1")) } returns listOf(bondPosition("UST-10Y"))
        coEvery { ratesServiceClient.getLatestYieldCurve("USD-SWAP") } returns ClientResponse.Success(flatYieldCurve())
        coEvery { grpcKrdClient.calculateKrd(any()) } returns krdResult("UST-10Y")

        val results = service.calculate(BookId("BOOK-1"))

        results[0].krdBuckets shouldHaveSize 4
    }

    test("returns empty list when yield curve is not found") {
        coEvery { positionProvider.getPositions(BookId("BOOK-1")) } returns listOf(bondPosition("UST-10Y"))
        coEvery { ratesServiceClient.getLatestYieldCurve("USD-SWAP") } returns ClientResponse.NotFound(404)

        val results = service.calculate(BookId("BOOK-1"))

        results shouldHaveSize 0
    }
})

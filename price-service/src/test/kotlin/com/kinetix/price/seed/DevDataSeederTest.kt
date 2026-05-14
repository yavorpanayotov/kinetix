package com.kinetix.price.seed

import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.PricePoint
import com.kinetix.price.persistence.PriceRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import java.time.Instant

class DevDataSeederTest : FunSpec({

    val repository = mockk<PriceRepository>()
    val seeder = DevDataSeeder(repository)

    beforeEach {
        clearMocks(repository)
    }

    test("seeds data when database is empty") {
        coEvery { repository.findLatest(InstrumentId("AAPL")) } returns null
        coEvery { repository.save(any()) } just runs

        seeder.seed()

        // 83 instruments × 169 hourly points (i in 0..168)
        // + 83 instruments × 244 daily closes (day in 8 until 252) sourced from DemoTape;
        //   days 0..7 are covered by the intraday writer to avoid PK collisions
        // + 252 daily prices for IDX-SPX benchmark (full 252-day tape window; no intraday)
        val expectedSaves = 83 * 169 + 83 * 244 + 252
        coVerify(exactly = expectedSaves) { repository.save(any()) }
    }

    test("skips seeding when data already exists") {
        coEvery { repository.findLatest(InstrumentId("AAPL")) } returns PricePoint(
            instrumentId = InstrumentId("AAPL"),
            price = com.kinetix.common.model.Money(java.math.BigDecimal("189.25"), java.util.Currency.getInstance("USD")),
            timestamp = Instant.now(),
            source = com.kinetix.common.model.PriceSource.EXCHANGE,
        )

        seeder.seed()

        coVerify(exactly = 0) { repository.save(any()) }
    }

    test("instruments list contains all 83 expected instruments") {
        DevDataSeeder.INSTRUMENT_IDS shouldBe setOf(
            "AAPL", "GOOGL", "MSFT", "AMZN", "TSLA",
            "EURUSD", "US2Y", "US10Y", "US30Y", "GC", "SPX-PUT-4500",
            "NVDA", "META", "JPM", "BABA",
            "GBPUSD", "USDJPY",
            "CL", "SI",
            "SPX-CALL-5000", "VIX-PUT-15",
            "DE10Y",
            "SPX-PUT-4800", "SPX-CALL-5200",
            "NVDA-C-950-20260620", "NVDA-P-800-20260620",
            "AAPL-P-180-20260620", "AAPL-C-200-20260620",
            "JPM-BOND-2031", "USD-SOFR-5Y",
            "GBPUSD-3M", "WTI-AUG26", "GC-C-2200-DEC26",
            "EURUSD-P-1.08-SEP26", "SPX-SEP26",
            "AMD", "INTC", "CRM", "ORCL", "ADBE",
            "BAC", "GS", "MS",
            "DIS", "KO", "WMT",
            "JNJ", "PFE", "UNH",
            "XOM", "CVX",
            "MSFT-C-450-20260620", "MSFT-P-400-20260620",
            "TSLA-C-280-20260620", "TSLA-P-220-20260620",
            "GOOGL-C-190-20260620", "GOOGL-P-160-20260620",
            "AMZN-C-220-20260620", "AMZN-P-190-20260620",
            "US5Y", "UK10Y", "JP10Y", "DE2Y",
            "AAPL-BOND-2030", "GS-BOND-2029", "MSFT-BOND-2032",
            "AUDUSD", "USDCAD", "USDCHF", "EURGBP", "NZDUSD",
            "EURUSD-6M", "USDJPY-3M",
            "USDJPY-C-155-SEP26",
            "USD-SOFR-10Y", "EUR-ESTR-5Y",
            "NDX-SEP26", "RTY-SEP26",
            "NG", "HG", "PL", "ZC",
            "CL-P-70-DEC26",
        )
    }

    test("AAPL has 169 hourly and 244 daily saved points") {
        coEvery { repository.findLatest(InstrumentId("AAPL")) } returns null
        val savedPoints = mutableListOf<PricePoint>()
        coEvery { repository.save(capture(savedPoints)) } just runs

        seeder.seed()

        val aaplPoints = savedPoints.filter { it.instrumentId.value == "AAPL" }
        // 169 hourly (covering days 0..7) + 244 daily (days 8..251)
        aaplPoints.size shouldBe 169 + 244
    }

    test("all daily close prices are strictly positive") {
        coEvery { repository.findLatest(InstrumentId("AAPL")) } returns null
        val savedPoints = mutableListOf<PricePoint>()
        coEvery { repository.save(capture(savedPoints)) } just runs

        seeder.seed()

        // Daily closes are for timestamps older than 7 days (outside the hourly window)
        val dailyCutoff = DevDataSeeder.LATEST_TIME.minus(7, java.time.temporal.ChronoUnit.DAYS)
        val dailyPoints = savedPoints.filter { it.timestamp.isBefore(dailyCutoff) }
        dailyPoints.forEach { point ->
            (point.price.amount > java.math.BigDecimal.ZERO) shouldBe true
        }
    }
})

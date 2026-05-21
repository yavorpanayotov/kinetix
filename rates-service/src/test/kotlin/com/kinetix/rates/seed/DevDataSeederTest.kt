package com.kinetix.rates.seed

import com.kinetix.common.demo.RegimeCalendar
import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.CurvePoint
import com.kinetix.common.model.ForwardCurve
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.RateSource
import com.kinetix.common.model.RiskFreeRate
import com.kinetix.common.model.Tenor
import com.kinetix.common.model.YieldCurve
import com.kinetix.rates.persistence.ForwardCurveRepository
import com.kinetix.rates.persistence.RiskFreeRateRepository
import com.kinetix.rates.persistence.YieldCurveRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import java.math.BigDecimal
import java.util.Currency

class DevDataSeederTest : FunSpec({

    val yieldCurveRepository = mockk<YieldCurveRepository>()
    val riskFreeRateRepository = mockk<RiskFreeRateRepository>()
    val forwardCurveRepository = mockk<ForwardCurveRepository>()
    val curveCurrencies = listOf("USD", "EUR", "GBP", "JPY")
    val seeder = DevDataSeeder(yieldCurveRepository, riskFreeRateRepository, forwardCurveRepository, curveCurrencies = curveCurrencies)

    beforeEach {
        clearMocks(yieldCurveRepository, riskFreeRateRepository, forwardCurveRepository)
    }

    test("seeds 252 daily yield curve snapshots per currency from the demo tape") {
        coEvery { yieldCurveRepository.findLatest("USD") } returns null
        coEvery { yieldCurveRepository.save(any()) } just runs
        coEvery { riskFreeRateRepository.save(any()) } just runs
        coEvery { forwardCurveRepository.save(any()) } just runs

        seeder.seed()

        val expected = curveCurrencies.size * RegimeCalendar.DAYS
        coVerify(exactly = expected) { yieldCurveRepository.save(any()) }
        coVerify(exactly = 2) { riskFreeRateRepository.save(any()) }
        coVerify(exactly = 6) { forwardCurveRepository.save(any()) }
    }

    test("skips seeding when data already exists") {
        coEvery { yieldCurveRepository.findLatest("USD") } returns YieldCurve(
            currency = Currency.getInstance("USD"),
            asOf = DevDataSeeder.AS_OF,
            tenors = listOf(Tenor.oneMonth(BigDecimal("0.0455"))),
            curveId = "USD",
            source = RateSource.CENTRAL_BANK,
        )

        seeder.seed()

        coVerify(exactly = 0) { yieldCurveRepository.save(any()) }
        coVerify(exactly = 0) { riskFreeRateRepository.save(any()) }
        coVerify(exactly = 0) { forwardCurveRepository.save(any()) }
    }

    test("seeded yield curves carry 10 tenors and CENTRAL_BANK source") {
        coEvery { yieldCurveRepository.findLatest("USD") } returns null
        val savedCurves = mutableListOf<YieldCurve>()
        coEvery { yieldCurveRepository.save(capture(savedCurves)) } just runs
        coEvery { riskFreeRateRepository.save(any()) } just runs
        coEvery { forwardCurveRepository.save(any()) } just runs

        seeder.seed()

        val usdCurves = savedCurves.filter { it.curveId == "USD" }
        usdCurves.size shouldBe RegimeCalendar.DAYS
        usdCurves.first().tenors.size shouldBe 10
        usdCurves.first().source shouldBe RateSource.CENTRAL_BANK
        usdCurves.first().tenors.first().label shouldBe "O/N"
        usdCurves.first().tenors.last().label shouldBe "30Y"
    }

    test("seeded yield curve dates span the full 252-day calendar") {
        coEvery { yieldCurveRepository.findLatest("USD") } returns null
        val savedCurves = mutableListOf<YieldCurve>()
        coEvery { yieldCurveRepository.save(capture(savedCurves)) } just runs
        coEvery { riskFreeRateRepository.save(any()) } just runs
        coEvery { forwardCurveRepository.save(any()) } just runs

        seeder.seed()

        val usdDates = savedCurves.filter { it.curveId == "USD" }.map { it.asOf }.toSet()
        usdDates.size shouldBe RegimeCalendar.DAYS
    }

    test("forward curves have correct asset classes (single snapshot, unchanged)") {
        coEvery { yieldCurveRepository.findLatest("USD") } returns null
        coEvery { yieldCurveRepository.save(any()) } just runs
        coEvery { riskFreeRateRepository.save(any()) } just runs
        val savedCurves = mutableListOf<ForwardCurve>()
        coEvery { forwardCurveRepository.save(capture(savedCurves)) } just runs

        seeder.seed()

        val eurusd = savedCurves.first { it.instrumentId.value == "EURUSD" }
        eurusd.assetClass shouldBe AssetClass.FX
        eurusd.points.size shouldBe 5

        val gc = savedCurves.first { it.instrumentId.value == "GC" }
        gc.assetClass shouldBe AssetClass.COMMODITY
    }
})

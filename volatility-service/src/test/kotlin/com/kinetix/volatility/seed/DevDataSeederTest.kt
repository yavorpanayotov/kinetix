package com.kinetix.volatility.seed

import com.kinetix.common.demo.RegimeCalendar
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.VolSurface
import com.kinetix.common.model.VolatilitySource
import com.kinetix.volatility.persistence.VolSurfaceRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import java.math.BigDecimal

class DevDataSeederTest : FunSpec({

    val volSurfaceRepository = mockk<VolSurfaceRepository>()
    val seeder = DevDataSeeder(volSurfaceRepository)

    beforeEach {
        clearMocks(volSurfaceRepository)
    }

    test("seeds 252 daily snapshots per tape underlier plus standalone surfaces") {
        coEvery { volSurfaceRepository.findLatest(InstrumentId("SPX")) } returns null
        coEvery { volSurfaceRepository.save(any()) } just runs

        seeder.seed()

        // 9 tape-driven underliers × 252 days + 1 standalone (VIX) = 2269
        val expected = 9 * RegimeCalendar.DAYS + 1
        coVerify(exactly = expected) { volSurfaceRepository.save(any()) }
    }

    test("skips seeding when data already exists") {
        coEvery { volSurfaceRepository.findLatest(InstrumentId("SPX")) } returns VolSurface.flat(
            InstrumentId("SPX"),
            DevDataSeeder.AS_OF,
            BigDecimal("0.18"),
        )

        seeder.seed()

        coVerify(exactly = 0) { volSurfaceRepository.save(any()) }
    }

    test("each surface has correct number of points from strike-maturity grid") {
        coEvery { volSurfaceRepository.findLatest(InstrumentId("SPX")) } returns null
        val savedSurfaces = mutableListOf<VolSurface>()
        coEvery { volSurfaceRepository.save(capture(savedSurfaces)) } just runs

        seeder.seed()

        // 7 strikes × 5 maturities = 35 points per surface
        val expectedPoints = DevDataSeeder.STRIKE_PERCENTS.size * DevDataSeeder.MATURITY_DAYS.size
        savedSurfaces.forEach { surface -> surface.points.size shouldBe expectedPoints }
    }

    test("surfaces cover the configured underlyings with EXCHANGE source") {
        coEvery { volSurfaceRepository.findLatest(InstrumentId("SPX")) } returns null
        val savedSurfaces = mutableListOf<VolSurface>()
        coEvery { volSurfaceRepository.save(capture(savedSurfaces)) } just runs

        seeder.seed()

        val underlyings = savedSurfaces.map { it.instrumentId.value }.distinct()
        underlyings shouldContainAll listOf("SPX", "VIX", "NVDA", "TSLA", "AAPL", "GOOGL", "MSFT", "META", "JPM", "BABA")
        savedSurfaces.forEach { it.source shouldBe VolatilitySource.EXCHANGE }
    }

    test("SPX surface dates span the full 252-day calendar") {
        coEvery { volSurfaceRepository.findLatest(InstrumentId("SPX")) } returns null
        val savedSurfaces = mutableListOf<VolSurface>()
        coEvery { volSurfaceRepository.save(capture(savedSurfaces)) } just runs

        seeder.seed()

        val spxDates = savedSurfaces.filter { it.instrumentId.value == "SPX" }.map { it.asOf }.toSet()
        spxDates.size shouldBe RegimeCalendar.DAYS
    }

    test("implied vol smile has higher vol for OTM puts than OTM calls") {
        val otmPutVol = DevDataSeeder.computeImpliedVol(0.20, 80, 90)
        val atmVol = DevDataSeeder.computeImpliedVol(0.20, 100, 90)
        val otmCallVol = DevDataSeeder.computeImpliedVol(0.20, 120, 90)

        (otmPutVol > atmVol) shouldBe true
        (otmCallVol > atmVol) shouldBe true
        (otmPutVol > otmCallVol) shouldBe true
    }
})

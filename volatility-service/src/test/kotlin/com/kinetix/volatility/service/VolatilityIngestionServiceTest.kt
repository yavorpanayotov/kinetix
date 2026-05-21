package com.kinetix.volatility.service

import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.VolPoint
import com.kinetix.common.model.VolSurface
import com.kinetix.common.model.VolatilitySource
import com.kinetix.volatility.cache.VolatilityCache
import com.kinetix.volatility.kafka.VolatilityPublisher
import com.kinetix.volatility.metrics.VolatilitySurfaceHealthMetrics
import com.kinetix.volatility.persistence.VolSurfaceRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.*
import java.math.BigDecimal
import java.time.Instant

class VolatilityIngestionServiceTest : FunSpec({

    val repository = mockk<VolSurfaceRepository>()
    val cache = mockk<VolatilityCache>()
    val publisher = mockk<VolatilityPublisher>()

    fun surface(underlying: String = "AAPL"): VolSurface = VolSurface(
        instrumentId = InstrumentId(underlying),
        asOf = Instant.parse("2026-02-24T10:00:00Z"),
        points = listOf(
            VolPoint(BigDecimal("100"), 30, BigDecimal("0.25")),
            VolPoint(BigDecimal("100"), 90, BigDecimal("0.28")),
        ),
        source = VolatilitySource.BLOOMBERG,
    )

    beforeEach {
        clearMocks(repository, cache, publisher)
    }

    test("saves, caches, and publishes volatility surface") {
        val surface = surface()
        val service = VolatilityIngestionService(repository, cache, publisher)

        coEvery { repository.save(surface) } just Runs
        coEvery { cache.putSurface(surface) } just Runs
        coEvery { publisher.publishSurface(surface) } just Runs

        service.ingest(surface)

        coVerify(ordering = Ordering.ORDERED) {
            repository.save(surface)
            cache.putSurface(surface)
            publisher.publishSurface(surface)
        }
    }

    test("a successful ingest records the surface point count and last-update timestamp") {
        val surface = surface()
        val registry = SimpleMeterRegistry()
        val healthMetrics = VolatilitySurfaceHealthMetrics(registry)
        val service = VolatilityIngestionService(repository, cache, publisher, healthMetrics)

        coEvery { repository.save(surface) } just Runs
        coEvery { cache.putSurface(surface) } just Runs
        coEvery { publisher.publishSurface(surface) } just Runs

        service.ingest(surface)

        registry.find("volatility_surface_points").tag("underlying", "AAPL").gauge()!!
            .value() shouldBe 2.0
        registry.find("volatility_surface_last_update_timestamp_seconds")
            .tag("underlying", "AAPL").gauge()!!.value() shouldBe registry
            .find("volatility_surface_last_update_timestamp_seconds")
            .tag("underlying", "AAPL").gauge()!!.value() // present, non-null
    }

    test("a failed ingest records a calibration failure and re-throws") {
        val surface = surface()
        val registry = SimpleMeterRegistry()
        val healthMetrics = VolatilitySurfaceHealthMetrics(registry)
        val service = VolatilityIngestionService(repository, cache, publisher, healthMetrics)

        coEvery { repository.save(surface) } throws RuntimeException("db down")

        shouldThrow<RuntimeException> { service.ingest(surface) }

        registry.counter("volatility_surface_calibration_failures_total", "underlying", "AAPL")
            .count() shouldBe 1.0
        registry.find("volatility_surface_points").tag("underlying", "AAPL").gauge() shouldBe null
    }

    test("a failed ingest does not record a surface update") {
        val surface = surface()
        val registry = SimpleMeterRegistry()
        val healthMetrics = VolatilitySurfaceHealthMetrics(registry)
        val service = VolatilityIngestionService(repository, cache, publisher, healthMetrics)

        coEvery { repository.save(surface) } just Runs
        coEvery { cache.putSurface(surface) } just Runs
        coEvery { publisher.publishSurface(surface) } throws RuntimeException("kafka down")

        shouldThrow<RuntimeException> { service.ingest(surface) }

        registry.counter("volatility_surface_calibration_failures_total", "underlying", "AAPL")
            .count() shouldBe 1.0
    }
})

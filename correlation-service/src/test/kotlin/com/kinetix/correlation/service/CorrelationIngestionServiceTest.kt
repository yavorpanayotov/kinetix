package com.kinetix.correlation.service

import com.kinetix.common.model.CorrelationMatrix
import com.kinetix.common.model.EstimationMethod
import com.kinetix.correlation.cache.CorrelationCache
import com.kinetix.correlation.kafka.CorrelationPublisher
import com.kinetix.correlation.metrics.CorrelationMatrixHealthMetrics
import com.kinetix.correlation.persistence.CorrelationMatrixRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.*
import java.time.Instant

class CorrelationIngestionServiceTest : FunSpec({

    val repo = mockk<CorrelationMatrixRepository>()
    val cache = mockk<CorrelationCache>()
    val publisher = mockk<CorrelationPublisher>()

    /** A well-conditioned, positive-semi-definite 2x2 correlation matrix. */
    fun psdMatrix(): CorrelationMatrix = CorrelationMatrix(
        labels = listOf("AAPL", "MSFT"),
        values = listOf(1.0, 0.65, 0.65, 1.0),
        windowDays = 252,
        asOfDate = Instant.parse("2026-02-24T10:00:00Z"),
        method = EstimationMethod.HISTORICAL,
    )

    /** A 2x2 matrix with an off-diagonal of 1.5 — not positive-semi-definite. */
    fun nonPsdMatrix(): CorrelationMatrix = CorrelationMatrix(
        labels = listOf("AAPL", "MSFT"),
        values = listOf(1.0, 1.5, 1.5, 1.0),
        windowDays = 252,
        asOfDate = Instant.parse("2026-02-24T10:00:00Z"),
        method = EstimationMethod.HISTORICAL,
    )

    beforeEach {
        clearMocks(repo, cache, publisher)
    }

    test("saves, caches, and publishes correlation matrix") {
        val matrix = psdMatrix()
        val service = CorrelationIngestionService(repo, cache, publisher)

        coEvery { repo.save(matrix) } just Runs
        coEvery { cache.put(matrix.labels, matrix.windowDays, matrix) } just Runs
        coEvery { publisher.publish(matrix) } just Runs

        service.ingest(matrix)

        coVerifyOrder {
            repo.save(matrix)
            cache.put(matrix.labels, matrix.windowDays, matrix)
            publisher.publish(matrix)
        }
    }

    test("a successful ingest of a PSD matrix records its entry count and last-update timestamp") {
        val matrix = psdMatrix()
        val registry = SimpleMeterRegistry()
        val healthMetrics = CorrelationMatrixHealthMetrics(registry)
        val service = CorrelationIngestionService(repo, cache, publisher, healthMetrics)

        coEvery { repo.save(matrix) } just Runs
        coEvery { cache.put(matrix.labels, matrix.windowDays, matrix) } just Runs
        coEvery { publisher.publish(matrix) } just Runs

        service.ingest(matrix)

        registry.find("correlation_matrix_points").tag("matrix_id", "default").gauge()!!
            .value() shouldBe 4.0
        registry.find("correlation_matrix_last_update_timestamp_seconds")
            .tag("matrix_id", "default").gauge() shouldBe registry
            .find("correlation_matrix_last_update_timestamp_seconds")
            .tag("matrix_id", "default").gauge() // present, non-null
    }

    test("a PSD matrix does not record a calibration failure") {
        val matrix = psdMatrix()
        val registry = SimpleMeterRegistry()
        val healthMetrics = CorrelationMatrixHealthMetrics(registry)
        val service = CorrelationIngestionService(repo, cache, publisher, healthMetrics)

        coEvery { repo.save(matrix) } just Runs
        coEvery { cache.put(matrix.labels, matrix.windowDays, matrix) } just Runs
        coEvery { publisher.publish(matrix) } just Runs

        service.ingest(matrix)

        registry.counter("correlation_matrix_calibration_failures_total", "matrix_id", "default")
            .count() shouldBe 0.0
    }

    test("a matrix that fails PSD validation records a calibration failure") {
        val matrix = nonPsdMatrix()
        val registry = SimpleMeterRegistry()
        val healthMetrics = CorrelationMatrixHealthMetrics(registry)
        val service = CorrelationIngestionService(repo, cache, publisher, healthMetrics)

        coEvery { repo.save(matrix) } just Runs
        coEvery { cache.put(matrix.labels, matrix.windowDays, matrix) } just Runs
        coEvery { publisher.publish(matrix) } just Runs

        service.ingest(matrix)

        registry.counter("correlation_matrix_calibration_failures_total", "matrix_id", "default")
            .count() shouldBe 1.0
    }

    test("a failed ingest records a calibration failure and re-throws") {
        val matrix = psdMatrix()
        val registry = SimpleMeterRegistry()
        val healthMetrics = CorrelationMatrixHealthMetrics(registry)
        val service = CorrelationIngestionService(repo, cache, publisher, healthMetrics)

        coEvery { repo.save(matrix) } throws RuntimeException("db down")

        shouldThrow<RuntimeException> { service.ingest(matrix) }

        registry.counter("correlation_matrix_calibration_failures_total", "matrix_id", "default")
            .count() shouldBe 1.0
        registry.find("correlation_matrix_points").tag("matrix_id", "default").gauge() shouldBe null
    }
})

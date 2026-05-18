package com.kinetix.correlation.persistence

import com.kinetix.common.model.CorrelationMatrix
import com.kinetix.common.model.EstimationMethod
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant

/**
 * Boundary-value coverage for correlation matrix construction and repository
 * lookups: degenerate sizes (1x1), symmetry, exact time-range boundaries, and
 * lookup invariance under label reordering. These complement
 * [ExposedCorrelationMatrixRepositoryIntegrationTest] which covers the happy
 * path.
 */
class CorrelationMatrixEdgeCasesIntegrationTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val repository: CorrelationMatrixRepository = ExposedCorrelationMatrixRepository()

    beforeEach {
        newSuspendedTransaction { CorrelationMatrixTable.deleteAll() }
    }

    test("CorrelationMatrix rejects an empty label set") {
        shouldThrow<IllegalArgumentException> {
            CorrelationMatrix(
                labels = emptyList(),
                values = emptyList(),
                windowDays = 252,
                asOfDate = Instant.parse("2026-01-15T10:00:00Z"),
                method = EstimationMethod.HISTORICAL,
            )
        }
    }

    test("CorrelationMatrix rejects values whose size is not labels.size squared") {
        shouldThrow<IllegalArgumentException> {
            CorrelationMatrix(
                labels = listOf("AAPL", "MSFT"),
                values = listOf(1.0, 0.5, 0.5),
                windowDays = 252,
                asOfDate = Instant.parse("2026-01-15T10:00:00Z"),
                method = EstimationMethod.HISTORICAL,
            )
        }
    }

    test("a 1x1 singleton matrix round-trips through save and findLatest") {
        val singleton = CorrelationMatrix(
            labels = listOf("AAPL"),
            values = listOf(1.0),
            windowDays = 252,
            asOfDate = Instant.parse("2026-01-15T10:00:00Z"),
            method = EstimationMethod.HISTORICAL,
        )
        repository.save(singleton)

        val found = repository.findLatest(listOf("AAPL"), 252)
        found.shouldNotBeNull()
        found.labels shouldBe listOf("AAPL")
        found.values shouldBe listOf(1.0)
    }

    test("findLatest is invariant under caller's label order (hash matches sorted form)") {
        val matrix = CorrelationMatrix(
            labels = listOf("AAPL", "GOOG", "MSFT"),
            values = listOf(1.0, 0.7, 0.65, 0.7, 1.0, 0.8, 0.65, 0.8, 1.0),
            windowDays = 252,
            asOfDate = Instant.parse("2026-01-15T10:00:00Z"),
            method = EstimationMethod.HISTORICAL,
        )
        repository.save(matrix)

        val byCallerOrder = repository.findLatest(listOf("MSFT", "AAPL", "GOOG"), 252)
        byCallerOrder.shouldNotBeNull()
        byCallerOrder.labels shouldBe listOf("AAPL", "GOOG", "MSFT")
    }

    test("findByTimeRange includes matrices at the exact lower boundary") {
        val boundary = Instant.parse("2026-01-15T10:00:00Z")
        repository.save(
            CorrelationMatrix(
                labels = listOf("AAPL", "MSFT"),
                values = listOf(1.0, 0.5, 0.5, 1.0),
                windowDays = 252,
                asOfDate = boundary,
                method = EstimationMethod.HISTORICAL,
            )
        )

        val results = repository.findByTimeRange(
            listOf("AAPL", "MSFT"),
            252,
            boundary,
            boundary.plusSeconds(86_400),
        )

        results shouldHaveSize 1
    }

    test("findByTimeRange includes matrices at the exact upper boundary") {
        val boundary = Instant.parse("2026-01-20T10:00:00Z")
        repository.save(
            CorrelationMatrix(
                labels = listOf("AAPL", "MSFT"),
                values = listOf(1.0, 0.5, 0.5, 1.0),
                windowDays = 252,
                asOfDate = boundary,
                method = EstimationMethod.HISTORICAL,
            )
        )

        val results = repository.findByTimeRange(
            listOf("AAPL", "MSFT"),
            252,
            boundary.minusSeconds(86_400),
            boundary,
        )

        results shouldHaveSize 1
    }

    test("findByTimeRange does not match a different windowDays") {
        repository.save(
            CorrelationMatrix(
                labels = listOf("AAPL", "MSFT"),
                values = listOf(1.0, 0.5, 0.5, 1.0),
                windowDays = 60,
                asOfDate = Instant.parse("2026-01-15T10:00:00Z"),
                method = EstimationMethod.HISTORICAL,
            )
        )

        val results = repository.findByTimeRange(
            listOf("AAPL", "MSFT"),
            252,
            Instant.parse("2026-01-10T00:00:00Z"),
            Instant.parse("2026-01-20T00:00:00Z"),
        )

        results shouldHaveSize 0
    }

    test("an identity matrix round-trips correctly (off-diagonal correlations are zero)") {
        val identity = CorrelationMatrix(
            labels = listOf("A", "B", "C"),
            values = listOf(
                1.0, 0.0, 0.0,
                0.0, 1.0, 0.0,
                0.0, 0.0, 1.0,
            ),
            windowDays = 252,
            asOfDate = Instant.parse("2026-01-15T10:00:00Z"),
            method = EstimationMethod.HISTORICAL,
        )
        repository.save(identity)

        val found = repository.findLatest(listOf("A", "B", "C"), 252)
        found.shouldNotBeNull()
        found.values shouldBe identity.values
    }

    test("a perfectly correlated matrix round-trips (off-diagonal == 1.0)") {
        val perfect = CorrelationMatrix(
            labels = listOf("X", "Y"),
            values = listOf(1.0, 1.0, 1.0, 1.0),
            windowDays = 252,
            asOfDate = Instant.parse("2026-01-15T10:00:00Z"),
            method = EstimationMethod.HISTORICAL,
        )
        repository.save(perfect)

        val found = repository.findLatest(listOf("X", "Y"), 252)
        found.shouldNotBeNull()
        found.values shouldBe listOf(1.0, 1.0, 1.0, 1.0)
    }

    test("a perfectly anti-correlated matrix round-trips (off-diagonal == -1.0)") {
        val antiCorr = CorrelationMatrix(
            labels = listOf("X", "Y"),
            values = listOf(1.0, -1.0, -1.0, 1.0),
            windowDays = 252,
            asOfDate = Instant.parse("2026-01-15T10:00:00Z"),
            method = EstimationMethod.HISTORICAL,
        )
        repository.save(antiCorr)

        val found = repository.findLatest(listOf("X", "Y"), 252)
        found.shouldNotBeNull()
        found.values shouldBe listOf(1.0, -1.0, -1.0, 1.0)
    }
})

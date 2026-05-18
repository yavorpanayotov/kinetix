package com.kinetix.volatility.persistence

import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.VolPoint
import com.kinetix.common.model.VolSurface
import com.kinetix.common.model.VolatilitySource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.math.BigDecimal
import java.time.Instant

/**
 * Boundary-value coverage for [ExposedVolSurfaceRepository]: findAtOrBefore
 * semantics, exact time-range boundaries, multi-instrument isolation, and
 * mixed VolatilitySource. Complements [ExposedVolSurfaceRepositoryIntegrationTest]
 * (happy path).
 */
class VolSurfaceRepositoryEdgeCasesIntegrationTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val repository: VolSurfaceRepository = ExposedVolSurfaceRepository()

    fun surface(
        instrumentId: InstrumentId,
        asOf: Instant,
        source: VolatilitySource = VolatilitySource.BLOOMBERG,
    ) = VolSurface(
        instrumentId = instrumentId,
        asOf = asOf,
        points = listOf(
            VolPoint(BigDecimal("100"), 30, BigDecimal("0.25")),
            VolPoint(BigDecimal("100"), 90, BigDecimal("0.28")),
        ),
        source = source,
    )

    beforeEach {
        newSuspendedTransaction {
            VolPointTable.deleteAll()
            VolSurfaceTable.deleteAll()
        }
    }

    test("findAtOrBefore returns the surface at the exact requested instant") {
        val exact = Instant.parse("2026-01-15T10:00:00Z")
        repository.save(surface(InstrumentId("AAPL"), exact))

        val found = repository.findAtOrBefore(InstrumentId("AAPL"), exact)
        found.shouldNotBeNull()
        found.asOf shouldBe exact
    }

    test("findAtOrBefore returns the most recent surface at or before the cutoff") {
        repository.save(surface(InstrumentId("AAPL"), Instant.parse("2026-01-10T10:00:00Z")))
        repository.save(surface(InstrumentId("AAPL"), Instant.parse("2026-01-15T10:00:00Z")))
        repository.save(surface(InstrumentId("AAPL"), Instant.parse("2026-01-20T10:00:00Z")))

        val found = repository.findAtOrBefore(
            InstrumentId("AAPL"),
            Instant.parse("2026-01-17T00:00:00Z"),
        )
        found.shouldNotBeNull()
        found.asOf shouldBe Instant.parse("2026-01-15T10:00:00Z")
    }

    test("findAtOrBefore returns null when no surface exists before the cutoff") {
        repository.save(surface(InstrumentId("AAPL"), Instant.parse("2026-02-01T10:00:00Z")))

        repository.findAtOrBefore(
            InstrumentId("AAPL"),
            Instant.parse("2026-01-01T00:00:00Z"),
        ).shouldBeNull()
    }

    test("findByTimeRange includes surfaces at the exact lower and upper bounds") {
        val lower = Instant.parse("2026-01-10T10:00:00Z")
        val upper = Instant.parse("2026-01-20T10:00:00Z")
        repository.save(surface(InstrumentId("AAPL"), lower))
        repository.save(surface(InstrumentId("AAPL"), upper))

        val results = repository.findByTimeRange(InstrumentId("AAPL"), lower, upper)
        results shouldHaveSize 2
    }

    test("findLatest isolates instruments — saving AAPL does not return for MSFT") {
        repository.save(surface(InstrumentId("AAPL"), Instant.parse("2026-01-15T10:00:00Z")))

        repository.findLatest(InstrumentId("MSFT")).shouldBeNull()
    }

    test("a surface's data source is preserved across save and findLatest") {
        repository.save(
            surface(
                InstrumentId("AAPL"),
                Instant.parse("2026-01-15T10:00:00Z"),
                source = VolatilitySource.REUTERS,
            )
        )

        val found = repository.findLatest(InstrumentId("AAPL"))
        found.shouldNotBeNull()
        found.source shouldBe VolatilitySource.REUTERS
    }

    test("findByTimeRange orders results ascending by asOf") {
        val dates = listOf(
            Instant.parse("2026-01-20T10:00:00Z"),
            Instant.parse("2026-01-10T10:00:00Z"),
            Instant.parse("2026-01-15T10:00:00Z"),
        )
        dates.forEach { repository.save(surface(InstrumentId("AAPL"), it)) }

        val results = repository.findByTimeRange(
            InstrumentId("AAPL"),
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-31T00:00:00Z"),
        )

        results shouldHaveSize 3
        results.map { it.asOf } shouldBe dates.sorted()
    }
})

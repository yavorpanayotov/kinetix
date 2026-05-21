package com.kinetix.rates.persistence

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.CurvePoint
import com.kinetix.common.model.ForwardCurve
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.RateSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant

private val EURUSD = InstrumentId("EURUSD")
private val NOW = Instant.parse("2026-01-15T10:00:00Z")

private fun forwardCurve(
    instrumentId: InstrumentId = EURUSD,
    assetClass: AssetClass = AssetClass.FX,
    points: List<CurvePoint> = listOf(
        CurvePoint("1M", 1.0855),
        CurvePoint("3M", 1.0870),
    ),
    asOfDate: Instant = NOW,
    source: RateSource = RateSource.REUTERS,
) = ForwardCurve(
    instrumentId = instrumentId,
    assetClass = assetClass,
    points = points,
    asOfDate = asOfDate,
    source = source,
)

class ExposedForwardCurveRepositoryIntegrationTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val repository: ForwardCurveRepository = ExposedForwardCurveRepository()

    beforeEach {
        newSuspendedTransaction {
            ForwardCurvePointTable.deleteAll()
            ForwardCurveTable.deleteAll()
        }
    }

    test("save and findLatest returns the forward curve") {
        repository.save(forwardCurve())

        val found = repository.findLatest(EURUSD)
        found.shouldNotBeNull()
        found.instrumentId shouldBe EURUSD
        found.assetClass shouldBe AssetClass.FX
        found.source shouldBe RateSource.REUTERS
        found.points shouldHaveSize 2
        found.points[0].tenor shouldBe "1M"
        found.points[0].value shouldBe 1.0855
    }

    test("findLatest returns null for unknown instrument") {
        repository.findLatest(InstrumentId("UNKNOWN")).shouldBeNull()
    }

    test("findLatest returns the most recent curve") {
        repository.save(forwardCurve(asOfDate = Instant.parse("2026-01-10T10:00:00Z")))
        repository.save(forwardCurve(asOfDate = Instant.parse("2026-01-15T10:00:00Z")))

        val found = repository.findLatest(EURUSD)
        found.shouldNotBeNull()
        found.asOfDate shouldBe Instant.parse("2026-01-15T10:00:00Z")
    }

    test("findByTimeRange returns curves within range") {
        repository.save(forwardCurve(asOfDate = Instant.parse("2026-01-10T10:00:00Z")))
        repository.save(forwardCurve(asOfDate = Instant.parse("2026-01-15T10:00:00Z")))
        repository.save(forwardCurve(asOfDate = Instant.parse("2026-01-20T10:00:00Z")))

        val results = repository.findByTimeRange(
            EURUSD,
            Instant.parse("2026-01-12T00:00:00Z"),
            Instant.parse("2026-01-18T00:00:00Z"),
        )
        results shouldHaveSize 1
    }

    test("findByTimeRange returns empty list for no matches") {
        val results = repository.findByTimeRange(
            EURUSD,
            Instant.parse("2027-01-01T00:00:00Z"),
            Instant.parse("2027-12-31T00:00:00Z"),
        )
        results shouldHaveSize 0
    }
})

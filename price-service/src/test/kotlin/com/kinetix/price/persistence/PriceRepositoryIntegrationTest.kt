package com.kinetix.price.persistence

import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.PricePoint
import com.kinetix.common.model.PriceSource
import com.kinetix.common.model.Money
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

private val USD = Currency.getInstance("USD")

private fun point(
    instrumentId: String = "AAPL",
    priceAmount: BigDecimal = BigDecimal("150.00"),
    currency: Currency = USD,
    timestamp: Instant = Instant.parse("2025-01-15T10:00:00Z"),
    source: PriceSource = PriceSource.EXCHANGE,
) = PricePoint(
    instrumentId = InstrumentId(instrumentId),
    price = Money(priceAmount, currency),
    timestamp = timestamp,
    source = source,
)

class PriceRepositoryIntegrationTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val repository: PriceRepository = ExposedPriceRepository()

    beforeEach {
        newSuspendedTransaction { PriceTable.deleteAll() }
        DatabaseTestSetup.refreshDailyClosePrices()
    }

    test("save and retrieve latest price point") {
        val p = point()
        repository.save(p)

        val found = repository.findLatest(InstrumentId("AAPL"))
        found.shouldNotBeNull()
        found.instrumentId shouldBe InstrumentId("AAPL")
        found.price.amount.compareTo(BigDecimal("150.00")) shouldBe 0
        found.price.currency shouldBe USD
        found.timestamp shouldBe Instant.parse("2025-01-15T10:00:00Z")
        found.source shouldBe PriceSource.EXCHANGE
    }

    test("findLatest returns null for unknown instrument") {
        repository.findLatest(InstrumentId("UNKNOWN")).shouldBeNull()
    }

    test("findLatest returns most recent price point") {
        repository.save(point(timestamp = Instant.parse("2025-01-15T10:00:00Z"), priceAmount = BigDecimal("150.00")))
        repository.save(point(timestamp = Instant.parse("2025-01-15T11:00:00Z"), priceAmount = BigDecimal("155.00")))
        repository.save(point(timestamp = Instant.parse("2025-01-15T09:00:00Z"), priceAmount = BigDecimal("148.00")))

        val found = repository.findLatest(InstrumentId("AAPL"))
        found.shouldNotBeNull()
        found.price.amount.compareTo(BigDecimal("155.00")) shouldBe 0
        found.timestamp shouldBe Instant.parse("2025-01-15T11:00:00Z")
    }

    test("findByInstrumentId returns points in time range ordered by timestamp descending") {
        repository.save(point(timestamp = Instant.parse("2025-01-15T08:00:00Z"), priceAmount = BigDecimal("145.00")))
        repository.save(point(timestamp = Instant.parse("2025-01-15T10:00:00Z"), priceAmount = BigDecimal("150.00")))
        repository.save(point(timestamp = Instant.parse("2025-01-15T12:00:00Z"), priceAmount = BigDecimal("155.00")))
        repository.save(point(timestamp = Instant.parse("2025-01-15T14:00:00Z"), priceAmount = BigDecimal("160.00")))

        val results = repository.findByInstrumentId(
            InstrumentId("AAPL"),
            from = Instant.parse("2025-01-15T09:00:00Z"),
            to = Instant.parse("2025-01-15T13:00:00Z"),
        )
        results shouldHaveSize 2
        results[0].price.amount.compareTo(BigDecimal("155.00")) shouldBe 0
        results[1].price.amount.compareTo(BigDecimal("150.00")) shouldBe 0
    }

    test("findByInstrumentId returns empty list for no matches") {
        repository.save(point(instrumentId = "AAPL"))

        val results = repository.findByInstrumentId(
            InstrumentId("MSFT"),
            from = Instant.parse("2025-01-15T00:00:00Z"),
            to = Instant.parse("2025-01-15T23:59:59Z"),
        )
        results shouldHaveSize 0
    }

    test("save preserves BigDecimal precision") {
        val p = point(priceAmount = BigDecimal("98765.432109876543"))
        repository.save(p)

        val found = repository.findLatest(InstrumentId("AAPL"))
        found.shouldNotBeNull()
        found.price.amount.compareTo(BigDecimal("98765.432109876543")) shouldBe 0
    }

    test("findDailyCloseByInstrumentId returns one price per calendar day with the latest timestamp") {
        // Day 1: 3 prices — expect the 16:00 one (latest)
        repository.save(point(timestamp = Instant.parse("2025-01-15T09:00:00Z"), priceAmount = BigDecimal("148.00")))
        repository.save(point(timestamp = Instant.parse("2025-01-15T12:00:00Z"), priceAmount = BigDecimal("150.00")))
        repository.save(point(timestamp = Instant.parse("2025-01-15T16:00:00Z"), priceAmount = BigDecimal("152.00")))
        // Day 2: 2 prices — expect the 15:00 one (latest)
        repository.save(point(timestamp = Instant.parse("2025-01-16T10:00:00Z"), priceAmount = BigDecimal("153.00")))
        repository.save(point(timestamp = Instant.parse("2025-01-16T15:00:00Z"), priceAmount = BigDecimal("155.00")))
        DatabaseTestSetup.refreshDailyClosePrices()

        val results = repository.findDailyCloseByInstrumentId(
            InstrumentId("AAPL"),
            from = Instant.parse("2025-01-15T00:00:00Z"),
            to = Instant.parse("2025-01-16T23:59:59Z"),
        )

        results shouldHaveSize 2
        // Descending order: day 2 first, then day 1
        results[0].price.amount.compareTo(BigDecimal("155.00")) shouldBe 0
        results[0].timestamp shouldBe Instant.parse("2025-01-16T15:00:00Z")
        results[1].price.amount.compareTo(BigDecimal("152.00")) shouldBe 0
        results[1].timestamp shouldBe Instant.parse("2025-01-15T16:00:00Z")
    }

    test("findDailyCloseByInstrumentId returns results in descending order") {
        repository.save(point(timestamp = Instant.parse("2025-01-13T10:00:00Z"), priceAmount = BigDecimal("140.00")))
        repository.save(point(timestamp = Instant.parse("2025-01-14T10:00:00Z"), priceAmount = BigDecimal("145.00")))
        repository.save(point(timestamp = Instant.parse("2025-01-15T10:00:00Z"), priceAmount = BigDecimal("150.00")))
        DatabaseTestSetup.refreshDailyClosePrices()

        val results = repository.findDailyCloseByInstrumentId(
            InstrumentId("AAPL"),
            from = Instant.parse("2025-01-13T00:00:00Z"),
            to = Instant.parse("2025-01-15T23:59:59Z"),
        )

        results shouldHaveSize 3
        results[0].timestamp shouldBe Instant.parse("2025-01-15T10:00:00Z")
        results[1].timestamp shouldBe Instant.parse("2025-01-14T10:00:00Z")
        results[2].timestamp shouldBe Instant.parse("2025-01-13T10:00:00Z")
    }

    test("findDailyCloseByInstrumentId returns empty list for no matches") {
        repository.save(point(instrumentId = "AAPL"))
        DatabaseTestSetup.refreshDailyClosePrices()

        val results = repository.findDailyCloseByInstrumentId(
            InstrumentId("MSFT"),
            from = Instant.parse("2025-01-15T00:00:00Z"),
            to = Instant.parse("2025-01-15T23:59:59Z"),
        )
        results shouldHaveSize 0
    }

    test("findDailyCloseByInstrumentId respects time range boundaries") {
        repository.save(point(timestamp = Instant.parse("2025-01-14T10:00:00Z"), priceAmount = BigDecimal("140.00"))) // outside
        repository.save(point(timestamp = Instant.parse("2025-01-15T10:00:00Z"), priceAmount = BigDecimal("150.00"))) // inside
        repository.save(point(timestamp = Instant.parse("2025-01-16T10:00:00Z"), priceAmount = BigDecimal("160.00"))) // outside
        DatabaseTestSetup.refreshDailyClosePrices()

        val results = repository.findDailyCloseByInstrumentId(
            InstrumentId("AAPL"),
            from = Instant.parse("2025-01-15T00:00:00Z"),
            to = Instant.parse("2025-01-15T23:59:59Z"),
        )
        results shouldHaveSize 1
        results[0].price.amount.compareTo(BigDecimal("150.00")) shouldBe 0
    }

    test("findDailyCloseByInstrumentId correctly separates prices at UTC midnight boundary") {
        // 23:55 UTC on Jan 15 should belong to Jan 15
        repository.save(point(timestamp = Instant.parse("2025-01-15T23:55:00Z"), priceAmount = BigDecimal("151.00")))
        // 00:05 UTC on Jan 16 should belong to Jan 16
        repository.save(point(timestamp = Instant.parse("2025-01-16T00:05:00Z"), priceAmount = BigDecimal("152.00")))
        DatabaseTestSetup.refreshDailyClosePrices()

        val results = repository.findDailyCloseByInstrumentId(
            InstrumentId("AAPL"),
            from = Instant.parse("2025-01-15T00:00:00Z"),
            to = Instant.parse("2025-01-16T23:59:59Z"),
        )

        results shouldHaveSize 2
        // Descending: Jan 16 first, then Jan 15
        results[0].price.amount.compareTo(BigDecimal("152.00")) shouldBe 0
        results[0].timestamp shouldBe Instant.parse("2025-01-16T00:05:00Z")
        results[1].price.amount.compareTo(BigDecimal("151.00")) shouldBe 0
        results[1].timestamp shouldBe Instant.parse("2025-01-15T23:55:00Z")
    }

    test("findDailyCloseByInstrumentId handles price at exactly UTC midnight") {
        // Exactly midnight belongs to the new day (date('2025-01-16T00:00:00Z' AT TIME ZONE 'UTC') = 2025-01-16)
        repository.save(point(timestamp = Instant.parse("2025-01-15T23:59:59Z"), priceAmount = BigDecimal("150.00")))
        repository.save(point(timestamp = Instant.parse("2025-01-16T00:00:00Z"), priceAmount = BigDecimal("151.00")))
        DatabaseTestSetup.refreshDailyClosePrices()

        val results = repository.findDailyCloseByInstrumentId(
            InstrumentId("AAPL"),
            from = Instant.parse("2025-01-15T00:00:00Z"),
            to = Instant.parse("2025-01-16T23:59:59Z"),
        )

        results shouldHaveSize 2
        results[0].timestamp shouldBe Instant.parse("2025-01-16T00:00:00Z")
        results[1].timestamp shouldBe Instant.parse("2025-01-15T23:59:59Z")
    }

    test("findDailyCloseByInstrumentId returns single price when only one exists in range") {
        repository.save(point(timestamp = Instant.parse("2025-01-15T14:30:00Z"), priceAmount = BigDecimal("150.00")))
        DatabaseTestSetup.refreshDailyClosePrices()

        val results = repository.findDailyCloseByInstrumentId(
            InstrumentId("AAPL"),
            from = Instant.parse("2025-01-15T00:00:00Z"),
            to = Instant.parse("2025-01-15T23:59:59Z"),
        )

        results shouldHaveSize 1
        results[0].price.amount.compareTo(BigDecimal("150.00")) shouldBe 0
        results[0].timestamp shouldBe Instant.parse("2025-01-15T14:30:00Z")
    }

    test("findByInstrumentId time range boundary is inclusive on both ends") {
        val exactFrom = Instant.parse("2025-01-15T10:00:00Z")
        val exactTo = Instant.parse("2025-01-15T12:00:00Z")
        repository.save(point(timestamp = exactFrom, priceAmount = BigDecimal("150.00")))
        repository.save(point(timestamp = exactTo, priceAmount = BigDecimal("155.00")))

        val results = repository.findByInstrumentId(
            InstrumentId("AAPL"),
            from = exactFrom,
            to = exactTo,
        )
        results shouldHaveSize 2
    }

    test("save and retrieve with all price sources") {
        PriceSource.entries.forEachIndexed { idx, src ->
            repository.save(
                point(
                    instrumentId = "INST-$idx",
                    timestamp = Instant.parse("2025-01-15T10:00:00Z"),
                    source = src,
                )
            )
            val found = repository.findLatest(InstrumentId("INST-$idx"))
            found.shouldNotBeNull()
            found.source shouldBe src
        }
    }

    test("findStaleInstruments returns instruments older than the threshold relative to platform max") {
        val anchor = Instant.parse("2026-02-22T10:00:00Z")
        // FRESH-1 and FRESH-2 are both at the platform max.
        repository.save(point(instrumentId = "FRESH-1", timestamp = anchor))
        repository.save(point(instrumentId = "FRESH-2", timestamp = anchor))
        // STALE-30 is 30h behind — over the 24h threshold.
        repository.save(point(instrumentId = "STALE-30", timestamp = anchor.minusSeconds(30 * 3600)))
        // BORDERLINE is 12h behind — under the 24h threshold.
        repository.save(point(instrumentId = "BORDERLINE", timestamp = anchor.minusSeconds(12 * 3600)))

        val stale = repository.findStaleInstruments(thresholdHours = 24)
        stale.map { it.instrumentId } shouldBe listOf("STALE-30")
        stale.first().ageHours shouldBe 30L
        stale.first().status shouldBe "STALE"
    }

    test("findStaleInstruments returns empty list when nothing exceeds the threshold") {
        val anchor = Instant.parse("2026-02-22T10:00:00Z")
        repository.save(point(instrumentId = "INST-A", timestamp = anchor))
        repository.save(point(instrumentId = "INST-B", timestamp = anchor.minusSeconds(2 * 3600)))

        repository.findStaleInstruments(thresholdHours = 24) shouldHaveSize 0
    }
})

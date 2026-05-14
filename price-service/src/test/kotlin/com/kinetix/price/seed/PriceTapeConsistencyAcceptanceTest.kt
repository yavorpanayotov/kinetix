package com.kinetix.price.seed

import com.kinetix.common.demo.DemoTape
import com.kinetix.common.demo.RegimeCalendar
import com.kinetix.common.model.InstrumentId
import com.kinetix.price.persistence.DatabaseTestSetup
import com.kinetix.price.persistence.ExposedPriceRepository
import com.kinetix.price.persistence.PriceTable
import io.kotest.core.spec.style.FunSpec
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Acceptance test for PR 1 item 2 in `docs/plans/demo-follow-up.md`:
 * "`price-service` prices match tape to 1e-6".
 *
 * After `DevDataSeeder.seed()` has run against a real Postgres database, the
 * persisted price at every daily-close-aligned timestamp must equal
 * `tape.priceOn(symbol, day)` to within 1e-6 — well inside the 10-decimal
 * persistence scale the seeder uses, and well outside any rounding noise that
 * could mask a regression to a non-tape source. If the seeder ever drifts back
 * to a local random walk (or any other independent path), this test fails for
 * every symbol on every day.
 *
 * The seeder splits the 252-day tape window between two writers:
 *   - days `[DAILY_CLOSE_START_DAY, 252)` — written by `seedDailyCloses`
 *   - days `[0, DAILY_CLOSE_START_DAY)` — written by `seedIntradayInterpolated`,
 *     which at integer day boundaries collapses to the tape close exactly
 * Both are reconciled here.
 */
class PriceTapeConsistencyAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val repository = ExposedPriceRepository(db)

    beforeEach {
        newSuspendedTransaction(db = db) { PriceTable.deleteAll() }
    }

    test("every seeded daily-aligned close matches the tape price for the same day to within 1e-6") {
        val tape = DemoTape()
        val seeder = DevDataSeeder(repository, tape)
        seeder.seed()

        val tolerance = 1e-6
        val mismatches = mutableListOf<String>()

        // Verify every instrument the seeder is configured to write — both the
        // per-config instruments and the IDX-SPX benchmark.
        val symbolsToCheck = buildSet {
            DevDataSeeder.INSTRUMENTS.keys.forEach { add(it.value) }
            add("IDX-SPX")
        }

        for (symbol in symbolsToCheck) {
            val staleOffset = DevDataSeeder.STALE_OFFSET_HOURS[symbol] ?: 0L
            val anchor = DevDataSeeder.LATEST_TIME.minus(staleOffset, ChronoUnit.HOURS)

            for (day in 0 until RegimeCalendar.DAYS) {
                val expected = tape.priceOn(symbol, day)
                val timestamp = anchor.minus(day.toLong(), ChronoUnit.DAYS)

                // Read back the exact record persisted at this daily-close-aligned
                // timestamp. A ±1 ms window pins it without colliding with adjacent
                // hourly points.
                val window = repository.findByInstrumentId(
                    instrumentId = InstrumentId(symbol),
                    from = timestamp.minusMillis(1),
                    to = timestamp.plusMillis(1),
                )
                if (window.isEmpty()) {
                    mismatches += "$symbol day=$day no persisted close at $timestamp"
                    continue
                }
                val persisted = window.first().price.amount.toDouble()
                val diff = abs(persisted - expected)
                if (diff > tolerance) {
                    mismatches += "$symbol day=$day persisted=$persisted tape=$expected diff=$diff"
                }
            }
        }

        if (mismatches.isNotEmpty()) {
            error(
                "Persisted daily closes diverge from tape for ${mismatches.size} (symbol, day) " +
                    "pairs (tolerance=$tolerance). First 10: ${mismatches.take(10)}"
            )
        }
    }

    test("seeder is deterministic across runs against the same tape seed") {
        // The tape is a deterministic generator; re-seeding with a fresh seeder
        // wired to a fresh tape (same seed) must produce identical daily closes.
        val firstSeeder = DevDataSeeder(repository, DemoTape())
        firstSeeder.seed()
        val firstSnapshot = snapshotDailyCloses(repository)

        newSuspendedTransaction(db = db) { PriceTable.deleteAll() }
        val secondSeeder = DevDataSeeder(repository, DemoTape())
        secondSeeder.seed()
        val secondSnapshot = snapshotDailyCloses(repository)

        // The snapshots must be identical: same (symbol, day) keys, same
        // persisted values. If the seeder ever picks up nondeterminism (e.g. via
        // a hashCode-based RNG), this test will catch it.
        val keys = firstSnapshot.keys + secondSnapshot.keys
        val divergences = keys.mapNotNull { key ->
            val a = firstSnapshot[key]
            val b = secondSnapshot[key]
            if (a == null || b == null || abs(a - b) > 0.0) "$key first=$a second=$b" else null
        }
        if (divergences.isNotEmpty()) {
            error("Seeder is nondeterministic: ${divergences.size} divergent values. First 10: ${divergences.take(10)}")
        }
    }
})

/**
 * Snapshot of every persisted daily-close price keyed by `(symbol, day)`.
 * Used to compare two runs of the seeder for byte-equivalence.
 */
private suspend fun snapshotDailyCloses(repository: ExposedPriceRepository): Map<String, Double> {
    val out = mutableMapOf<String, Double>()
    val symbols = buildSet {
        DevDataSeeder.INSTRUMENTS.keys.forEach { add(it.value) }
        add("IDX-SPX")
    }
    for (symbol in symbols) {
        val staleOffset = DevDataSeeder.STALE_OFFSET_HOURS[symbol] ?: 0L
        val anchor = DevDataSeeder.LATEST_TIME.minus(staleOffset, ChronoUnit.HOURS)
        for (day in 0 until RegimeCalendar.DAYS) {
            val timestamp = anchor.minus(day.toLong(), ChronoUnit.DAYS)
            val window = repository.findByInstrumentId(
                instrumentId = InstrumentId(symbol),
                from = timestamp.minusMillis(1),
                to = timestamp.plusMillis(1),
            )
            if (window.isNotEmpty()) {
                out["$symbol@$day"] = window.first().price.amount.toDouble()
            }
        }
    }
    return out
}

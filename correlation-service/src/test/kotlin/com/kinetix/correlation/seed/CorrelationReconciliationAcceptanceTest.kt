package com.kinetix.correlation.seed

import com.kinetix.common.demo.DemoTape
import com.kinetix.correlation.persistence.DatabaseTestSetup
import com.kinetix.correlation.persistence.ExposedCorrelationMatrixRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import kotlin.math.abs

/**
 * Acceptance test for PR 1 item 1 in `docs/plans/demo-follow-up.md`:
 * "Correlation matrix is derived from tape, not constants; reconciliation test passes."
 *
 * For every pair of labels in the seeded universe the persisted matrix must agree
 * with the realised Pearson correlation of tape log-returns over the same 252-day
 * window. We use a 0.05 absolute tolerance — wider than zero to absorb any future
 * shrinkage or de-noising the seeder might add, but tight enough to fail loudly if
 * the seeder regresses to hand-coded constants.
 *
 * Note on terminology: the plan's wording mentions `empiricalCovariance` but the
 * seeded value is a *correlation* (bounded to `[-1, 1]`); covariance has units of
 * return² and a 0.05 tolerance against it would be meaningless. We compare
 * correlation-to-correlation, which is the mathematically consistent reconciliation.
 */
class CorrelationReconciliationAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val correlationRepo = ExposedCorrelationMatrixRepository(db)

    beforeEach {
        newSuspendedTransaction(db = db) {
            exec("TRUNCATE TABLE correlation_matrices RESTART IDENTITY CASCADE")
        }
    }

    test("seeded correlation matrix reconciles to tape realised correlation for every pair") {
        val tape = DemoTape()
        val seeder = DevDataSeeder(correlationRepo, tape)

        seeder.seed()

        val persisted = correlationRepo.findLatest(DevDataSeeder.LABELS, DevDataSeeder.WINDOW_DAYS)
            ?: error("seeder did not persist a matrix")

        // Repository stores labels sorted; build a label -> row index lookup so we
        // can read the row-major values list regardless of original ordering.
        val labels = persisted.labels
        val n = labels.size
        labels.size shouldBe DevDataSeeder.LABELS.size
        persisted.values.size shouldBe n * n
        persisted.windowDays shouldBe DevDataSeeder.WINDOW_DAYS

        val tolerance = 0.05
        val mismatches = mutableListOf<String>()
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val seeded = persisted.values[i * n + j]
                val empirical = tape.realisedCorrelation(
                    a = labels[i],
                    b = labels[j],
                    endDay = 0,
                    window = DevDataSeeder.WINDOW_DAYS,
                )
                val diff = abs(seeded - empirical)
                if (diff > tolerance) {
                    mismatches += "${labels[i]}:${labels[j]} seeded=$seeded empirical=$empirical diff=$diff"
                }
            }
        }

        if (mismatches.isNotEmpty()) {
            error(
                "Seeded correlations diverge from tape realised correlation for " +
                    "${mismatches.size}/${n * (n - 1) / 2} pairs (tolerance=$tolerance). " +
                    "First 10: ${mismatches.take(10)}"
            )
        }
    }

    test("diagonal of seeded matrix is exactly 1.0") {
        val seeder = DevDataSeeder(correlationRepo)
        seeder.seed()

        val persisted = correlationRepo.findLatest(DevDataSeeder.LABELS, DevDataSeeder.WINDOW_DAYS)
            ?: error("seeder did not persist a matrix")
        val n = persisted.labels.size
        for (i in 0 until n) {
            persisted.values[i * n + i] shouldBe 1.0
        }
    }

    test("seeded matrix is symmetric") {
        val seeder = DevDataSeeder(correlationRepo)
        seeder.seed()

        val persisted = correlationRepo.findLatest(DevDataSeeder.LABELS, DevDataSeeder.WINDOW_DAYS)
            ?: error("seeder did not persist a matrix")
        val n = persisted.labels.size
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                persisted.values[i * n + j] shouldBe persisted.values[j * n + i]
            }
        }
    }
})

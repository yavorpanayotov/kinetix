package com.kinetix.correlation.validation

import com.kinetix.common.model.CorrelationMatrix
import com.kinetix.common.model.EstimationMethod
import com.kinetix.correlation.feed.CorrelationFeedSimulator
import com.kinetix.correlation.persistence.CorrelationMatrixRepository
import com.kinetix.correlation.seed.DevDataSeeder
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import java.time.Instant
import kotlin.random.Random

/**
 * Unit tests for [CorrelationPsdValidator]. Covers the four scenarios mandated by the
 * testing-overhaul follow-up plan (item 4.1): a known-PSD matrix, a matrix with a
 * negative eigenvalue, a near-PSD matrix within the default numerical tolerance, and
 * every shipped seed/feed correlation matrix.
 */
class CorrelationPsdValidatorTest : FunSpec({

    test("identity matrix is PSD with all eigenvalues equal to one") {
        val identity = Array(4) { i -> DoubleArray(4) { j -> if (i == j) 1.0 else 0.0 } }

        val result = CorrelationPsdValidator.validate(identity)

        result.isPsd() shouldBe true
        result.smallestEigenvalue shouldBe (1.0 plusOrMinus 1e-12)
        result.eigenvalues.size shouldBe 4
        result.eigenvalues.all { it in (1.0 - 1e-12)..(1.0 + 1e-12) } shouldBe true
    }

    test("matrix with a negative eigenvalue of -0.5 fails PSD validation") {
        // Symmetric 2x2 [[0.25, 0.75], [0.75, 0.25]] has eigenvalues 1.0 and -0.5.
        val matrix = arrayOf(
            doubleArrayOf(0.25, 0.75),
            doubleArrayOf(0.75, 0.25),
        )

        val result = CorrelationPsdValidator.validate(matrix)

        result.isPsd() shouldBe false
        result.smallestEigenvalue shouldBe (-0.5 plusOrMinus 1e-12)
    }

    test("near-PSD matrix with smallest eigenvalue just inside 1e-7 passes at the default tolerance") {
        // Symmetric 2x2 [[a, b], [b, a]] has eigenvalues a+b and a-b.
        // a = 0.5 - 2.5e-8, b = 0.5  ⇒  smallest eigenvalue = a - b = -2.5e-8, which sits
        // within ±1e-7 (well inside the default 1e-6 tolerance) per the plan's wording.
        val a = 0.5 - 2.5e-8
        val b = 0.5
        val matrix = arrayOf(
            doubleArrayOf(a, b),
            doubleArrayOf(b, a),
        )

        val result = CorrelationPsdValidator.validate(matrix)

        result.smallestEigenvalue shouldBe (-2.5e-8 plusOrMinus 1e-12)
        result.isPsd() shouldBe true               // default tolerance 1e-6 accepts it
        result.isPsd(tolerance = 1e-9) shouldBe false  // tighter tolerance rejects it
    }

    test("the shipped seed correlation matrix is PSD") {
        val seed = seedMatrix()

        val result = CorrelationPsdValidator.validate(seed)

        withClue("Seed matrix must be PSD (smallest eigenvalue=${result.smallestEigenvalue})") {
            result.isPsd() shouldBe true
        }
    }

    test("matrices produced by the feed simulator across multiple ticks are PSD") {
        val seed = seedMatrix()
        val simulator = CorrelationFeedSimulator(seedMatrix = seed, random = Random(seed = 42L))

        val baseTime = Instant.parse("2026-02-22T10:00:00Z")
        repeat(5) { tickIndex ->
            val tick = simulator.tick(baseTime.plusSeconds((tickIndex + 1) * 60L))
            val result = CorrelationPsdValidator.validate(tick)
            withClue("Feed tick $tickIndex must be PSD (smallest eigenvalue=${result.smallestEigenvalue})") {
                result.isPsd() shouldBe true
            }
        }
    }
})

private fun seedMatrix(): CorrelationMatrix {
    val seeder = DevDataSeeder(correlationMatrixRepository = mockk<CorrelationMatrixRepository>(relaxed = true))
    return CorrelationMatrix(
        labels = DevDataSeeder.LABELS,
        values = seeder.correlationValues(),
        windowDays = DevDataSeeder.WINDOW_DAYS,
        asOfDate = DevDataSeeder.AS_OF,
        method = EstimationMethod.HISTORICAL,
    )
}

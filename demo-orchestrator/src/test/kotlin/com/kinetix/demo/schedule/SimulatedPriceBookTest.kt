package com.kinetix.demo.schedule

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.util.Random

/**
 * Unit tests for [SimulatedPriceBook].
 *
 * The book is required to ship with realistic indicative spots for every
 * instrument in the canonical demo seed (so `FIXED_INCOME` no longer prices
 * at the placeholder $0.05) and to drift each price by a small Gaussian
 * random walk on every call so live demos look alive. With a zero walk
 * standard deviation the first call must return exactly the seed price —
 * that is the contract used elsewhere (acceptance tests, fixtures) that
 * need deterministic, drift-free pricing.
 */
class SimulatedPriceBookTest : FunSpec({

    test("seed prices match the canonical table when walkStdDev is zero") {
        val book = SimulatedPriceBook(
            random = Random(0L),
            walkStdDev = 0.0,
        )

        // Spot-check a price from every asset class so we know the entire
        // table is wired through — equities, ETFs, FX, govvies, and listed
        // derivatives.
        book.priceFor("AAPL", "EQUITY") shouldBe BigDecimal("185.00")
        book.priceFor("MSFT", "EQUITY") shouldBe BigDecimal("420.00")
        book.priceFor("NVDA", "EQUITY") shouldBe BigDecimal("880.00")
        book.priceFor("GOOGL", "EQUITY") shouldBe BigDecimal("155.00")
        book.priceFor("AMZN", "EQUITY") shouldBe BigDecimal("225.00")
        book.priceFor("TSLA", "EQUITY") shouldBe BigDecimal("245.00")
        book.priceFor("META", "EQUITY") shouldBe BigDecimal("590.00")
        book.priceFor("JNJ", "EQUITY") shouldBe BigDecimal("155.00")
        book.priceFor("KO", "EQUITY") shouldBe BigDecimal("70.00")
        book.priceFor("PG", "EQUITY") shouldBe BigDecimal("165.00")
        book.priceFor("EEM", "EQUITY") shouldBe BigDecimal("50.00")
        book.priceFor("VWO", "EQUITY") shouldBe BigDecimal("50.00")
        book.priceFor("IEMG", "EQUITY") shouldBe BigDecimal("58.00")
        book.priceFor("BABA", "EQUITY") shouldBe BigDecimal("110.00")
        book.priceFor("EURUSD", "FX") shouldBe BigDecimal("1.085")
        book.priceFor("GBPUSD", "FX") shouldBe BigDecimal("1.27")
        book.priceFor("USDJPY", "FX") shouldBe BigDecimal("150.0")
        book.priceFor("UST-2Y", "FIXED_INCOME") shouldBe BigDecimal("99.20")
        book.priceFor("UST-5Y", "FIXED_INCOME") shouldBe BigDecimal("98.70")
        book.priceFor("UST-10Y", "FIXED_INCOME") shouldBe BigDecimal("98.50")
        book.priceFor("UST-30Y", "FIXED_INCOME") shouldBe BigDecimal("96.80")
        book.priceFor("SPX-OPT-5000C", "DERIVATIVE") shouldBe BigDecimal("180.0")
        book.priceFor("ES-FUT-MAR", "DERIVATIVE") shouldBe BigDecimal("5060.0")
        book.priceFor("VIX-OPT-20C", "DERIVATIVE") shouldBe BigDecimal("2.50")
    }

    test("FIXED_INCOME prices are near par, not the legacy $0.05 placeholder") {
        // Regression guard for kx-6ln. Bonds quoted in clean-price points,
        // so anything in the high-90s is correct; $0.05 indicates the bug.
        val book = SimulatedPriceBook(
            random = Random(0L),
            walkStdDev = 0.0,
        )

        listOf("UST-2Y", "UST-5Y", "UST-10Y", "UST-30Y").forEach { id ->
            val price = book.priceFor(id, "FIXED_INCOME")
            (price > BigDecimal("90.00")) shouldBe true
            (price < BigDecimal("105.00")) shouldBe true
        }
    }

    test("successive calls drift the price by a bounded fraction") {
        // Random walk: ±0.3% per call by default. Seeded RNG, so the
        // sequence is deterministic — we assert (a) every call differs
        // from the previous one and (b) the drift stays well inside a
        // conservative 5% envelope across 20 ticks.
        val book = SimulatedPriceBook(random = Random(123L))
        val seed = BigDecimal("185.00")
        val maxDriftFraction = BigDecimal("0.05") // 5% loose envelope

        val prices = (1..20).map { book.priceFor("AAPL", "EQUITY") }
        prices shouldHaveAtLeastSize 2

        // At least one consecutive pair must differ — the walk is alive.
        val anyDifferent = prices.zipWithNext().any { (a, b) -> a != b }
        anyDifferent shouldBe true

        // Every observed price stays inside ±5% of the seed across 20 calls.
        // 20 calls × 0.3% stddev → ~3-sigma envelope of ~1.8% < 5%.
        val lowerBound = seed.multiply(BigDecimal.ONE - maxDriftFraction)
        val upperBound = seed.multiply(BigDecimal.ONE + maxDriftFraction)
        prices.forEach { price ->
            (price >= lowerBound) shouldBe true
            (price <= upperBound) shouldBe true
        }
    }

    test("seeded RNGs reproduce the same sequence of prices") {
        // Determinism is load-bearing for tests and demos. Same seed, same
        // walk stddev → identical sequences.
        val a = SimulatedPriceBook(random = Random(42L), walkStdDev = 0.003)
        val b = SimulatedPriceBook(random = Random(42L), walkStdDev = 0.003)

        repeat(10) {
            a.priceFor("AAPL", "EQUITY") shouldBe b.priceFor("AAPL", "EQUITY")
        }
    }

    test("unknown instrument falls back to the asset-class default") {
        // The SimulatedPriceBook delegates unknown instruments to a
        // DefaultPriceBook-like fallback keyed by asset class.
        val book = SimulatedPriceBook(
            random = Random(0L),
            walkStdDev = 0.0,
        )

        // None of these instruments are in the seed table.
        book.priceFor("UNKNOWN-EQUITY-XYZ", "EQUITY") shouldBe BigDecimal("100.00")
        book.priceFor("UNKNOWN-FX-XYZ", "FX") shouldBe BigDecimal("1.10")
        book.priceFor("UNKNOWN-BOND-XYZ", "FIXED_INCOME") shouldBe BigDecimal("100.00")
        book.priceFor("UNKNOWN-COMMODITY-XYZ", "COMMODITY") shouldBe BigDecimal("75.00")
        book.priceFor("UNKNOWN-DERIV-XYZ", "DERIVATIVE") shouldBe BigDecimal("50.00")
        // Unknown asset class → conservative $100 fallback.
        book.priceFor("UNKNOWN", "MYSTERY_CLASS") shouldBe BigDecimal("100.00")
    }

    test("implements the PriceBook interface so it is swappable with DefaultPriceBook") {
        val book: PriceBook = SimulatedPriceBook(random = Random(0L), walkStdDev = 0.0)

        // Same interface — the simulated book can stand in wherever the
        // default did. The signature includes both instrumentId and
        // assetClass so the simulated book can use either dimension.
        book.priceFor("AAPL", "EQUITY") shouldBe BigDecimal("185.00")
    }
})

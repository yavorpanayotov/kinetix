package com.kinetix.demo.profile

/**
 * Static catalogue mapping each of the 8 demo books seeded by
 * `risk-orchestrator`'s `DevDataSeeder` to a [DemoBookProfile].
 *
 * Notional caps are conservative on purpose — a demo book that gets blown up
 * to 50× VaR in two hours looks broken, not realistic.
 *
 * Instrument ids are currently behavioural placeholders. Later checkboxes
 * that actually post trades will reconcile them with reference-data rows.
 */
object DemoBookProfiles {

    private val byBookId: Map<String, DemoBookProfile> = listOf(
        DemoBookProfile(
            bookId = "equity-growth",
            tradeProbability = 0.35,
            instrumentIds = listOf("AAPL", "MSFT", "NVDA", "META"),
            notionalRangeUsd = 25_000L..500_000L,
            assetClass = "EQUITY",
        ),
        DemoBookProfile(
            bookId = "tech-momentum",
            tradeProbability = 0.6,
            instrumentIds = listOf("AAPL", "MSFT", "GOOGL", "AMZN", "TSLA"),
            notionalRangeUsd = 10_000L..200_000L,
            assetClass = "EQUITY",
        ),
        DemoBookProfile(
            bookId = "emerging-markets",
            tradeProbability = 0.25,
            instrumentIds = listOf("EEM", "VWO", "IEMG", "BABA"),
            notionalRangeUsd = 50_000L..500_000L,
            assetClass = "EQUITY",
        ),
        DemoBookProfile(
            bookId = "fixed-income",
            tradeProbability = 0.2,
            instrumentIds = listOf("UST-2Y", "UST-5Y", "UST-10Y", "UST-30Y"),
            notionalRangeUsd = 100_000L..2_000_000L,
            assetClass = "RATES",
        ),
        DemoBookProfile(
            bookId = "multi-asset",
            tradeProbability = 0.3,
            instrumentIds = listOf("AAPL", "MSFT", "UST-5Y", "UST-10Y"),
            notionalRangeUsd = 50_000L..750_000L,
            assetClass = "MULTI",
        ),
        DemoBookProfile(
            bookId = "macro-hedge",
            tradeProbability = 0.15,
            instrumentIds = listOf("EURUSD", "GBPUSD", "USDJPY", "UST-10Y"),
            notionalRangeUsd = 250_000L..5_000_000L,
            assetClass = "FX",
        ),
        DemoBookProfile(
            bookId = "balanced-income",
            tradeProbability = 0.2,
            instrumentIds = listOf("JNJ", "KO", "PG", "UST-5Y", "UST-10Y"),
            notionalRangeUsd = 50_000L..750_000L,
            assetClass = "MULTI",
        ),
        DemoBookProfile(
            bookId = "derivatives-book",
            tradeProbability = 0.4,
            instrumentIds = listOf("SPX-OPT-5000C", "ES-FUT-MAR", "VIX-OPT-20C"),
            notionalRangeUsd = 50_000L..1_500_000L,
            assetClass = "DERIV",
        ),
    ).associateBy { it.bookId }

    /** All seeded book profiles, in stable insertion order. */
    fun all(): List<DemoBookProfile> = byBookId.values.toList()

    /** The profile for [bookId], or `null` if the book is not part of the demo seed. */
    fun forBook(bookId: String): DemoBookProfile? = byBookId[bookId]
}

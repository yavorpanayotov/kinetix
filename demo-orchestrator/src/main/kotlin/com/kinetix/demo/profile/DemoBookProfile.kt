package com.kinetix.demo.profile

/**
 * Behavioural template describing how the [SimulatedTraderJob] should generate
 * trades for a single demo book.
 *
 * - [tradeProbability] is the per-cadence-tick chance (0..1) that this book books
 *   any trades. A book at 0.6 is "high frequency" (tech-momentum); 0.15 is
 *   "low frequency" (macro-hedge).
 * - [instrumentIds] is the menu the trader picks from. Later checkboxes will
 *   reconcile these against reference data.
 * - [notionalRangeUsd] caps the notional size in whole USD. Long is plenty of
 *   precision — demo trades are sized for plausibility, not penny accuracy.
 * - [assetClass] is a free-form tag (EQUITY / RATES / FX / DERIV / MULTI) used
 *   for reporting and downstream routing decisions.
 */
data class DemoBookProfile(
    val bookId: String,
    val tradeProbability: Double,
    val instrumentIds: List<String>,
    val notionalRangeUsd: LongRange,
    val assetClass: String,
) {
    init {
        require(tradeProbability in 0.0..1.0) {
            "tradeProbability must be in [0, 1], was $tradeProbability for book '$bookId'"
        }
    }
}

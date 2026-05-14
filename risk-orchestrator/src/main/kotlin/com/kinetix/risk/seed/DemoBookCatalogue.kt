package com.kinetix.risk.seed

import com.kinetix.common.demo.BlackScholes
import com.kinetix.common.model.AssetClass

/**
 * Deterministic per-book demo positions consumed by [PnLAttributionDeriver].
 *
 * Re-uses the same book identifiers and underliers that the position-service
 * seeder establishes, so the demo P&L attribution rows tie back to instruments
 * the user can actually see in the trade blotter.
 *
 * Quantities and option strikes are sized so that:
 *  - book-level daily P&L is in the high-five to six-figure range on calm days
 *  - the stress windows drive materially worse P&L (the whole point of the demo)
 *  - options books carry non-trivial gamma / vega / theta exposures so the
 *    Greek attribution columns are visibly populated rather than all-zero
 */
object DemoBookCatalogue {

    /** All books seeded by risk-orchestrator's demo path. */
    val BOOKS: Map<String, List<DemoBookPosition>> = linkedMapOf(
        "equity-growth" to listOf(
            cash("AAPL", AssetClass.EQUITY, 8_000.0),
            cash("MSFT", AssetClass.EQUITY, 3_000.0),
            cash("GOOGL", AssetClass.EQUITY, 4_000.0),
            cash("JPM", AssetClass.EQUITY, 2_000.0),
            cash("META", AssetClass.EQUITY, 800.0),
        ),
        "tech-momentum" to listOf(
            cash("NVDA", AssetClass.EQUITY, 1_500.0),
            cash("TSLA", AssetClass.EQUITY, 2_500.0),
            cash("AMD", AssetClass.EQUITY, 5_000.0),
            // Long call on NVDA, modest size — adds gamma/vega/theta to the book.
            option("NVDA-C-950-20260620", quantity = 200.0,
                underlier = "NVDA", strike = 950.0, yearsToExpiry = 0.6, type = BlackScholes.OptionType.CALL),
        ),
        "emerging-markets" to listOf(
            cash("BABA", AssetClass.EQUITY, 12_000.0),
            cash("EURUSD", AssetClass.FX, 2_500_000.0),
            cash("USDJPY", AssetClass.FX, -2_500_000.0),
        ),
        "fixed-income" to listOf(
            // Long government bond exposure: tape's rates sensitivity drives the P&L,
            // matching the "rates-driven book" demo story.
            cash("US10Y", AssetClass.FIXED_INCOME, 50_000.0),
            cash("US2Y", AssetClass.FIXED_INCOME, 30_000.0),
            cash("US30Y", AssetClass.FIXED_INCOME, 20_000.0),
        ),
        "multi-asset" to listOf(
            cash("AAPL", AssetClass.EQUITY, 4_000.0),
            cash("GC", AssetClass.COMMODITY, 50.0),
            cash("US10Y", AssetClass.FIXED_INCOME, 25_000.0),
            // Long SPX put: the multi-asset book hedges equity downside.
            option("SPX-PUT-4500", quantity = 300.0,
                underlier = "IDX-SPX", strike = 4_500.0, yearsToExpiry = 0.5, type = BlackScholes.OptionType.PUT),
        ),
        "macro-hedge" to listOf(
            cash("CL", AssetClass.COMMODITY, 800.0),
            cash("GC", AssetClass.COMMODITY, 80.0),
            cash("EURUSD", AssetClass.FX, 1_500_000.0),
        ),
        "balanced-income" to listOf(
            cash("JPM", AssetClass.EQUITY, 1_500.0),
            cash("US30Y", AssetClass.FIXED_INCOME, 15_000.0),
            cash("KO", AssetClass.EQUITY, 2_500.0),
        ),
        "derivatives-book" to listOf(
            // Big options book: long upside calls, long downside puts.
            option("SPX-CALL-5000", quantity = 600.0,
                underlier = "IDX-SPX", strike = 5_000.0, yearsToExpiry = 0.5, type = BlackScholes.OptionType.CALL),
            option("SPX-PUT-4800", quantity = -400.0,
                underlier = "IDX-SPX", strike = 4_800.0, yearsToExpiry = 0.5, type = BlackScholes.OptionType.PUT),
            option("NVDA-C-950-20260620", quantity = 350.0,
                underlier = "NVDA", strike = 950.0, yearsToExpiry = 0.6, type = BlackScholes.OptionType.CALL),
        ),
    )

    private fun cash(
        instrumentId: String,
        assetClass: AssetClass,
        quantity: Double,
    ): DemoBookPosition = DemoBookPosition(
        instrumentId = instrumentId,
        assetClass = assetClass,
        quantity = quantity,
    )

    private fun option(
        instrumentId: String,
        quantity: Double,
        underlier: String,
        strike: Double,
        yearsToExpiry: Double,
        type: BlackScholes.OptionType,
    ): DemoBookPosition = DemoBookPosition(
        instrumentId = instrumentId,
        assetClass = AssetClass.DERIVATIVE,
        quantity = quantity,
        optionSpec = DemoBookPosition.OptionSpec(
            underlier = underlier,
            strike = strike,
            yearsToExpiryFromAsOf = yearsToExpiry,
            type = type,
        ),
    )
}

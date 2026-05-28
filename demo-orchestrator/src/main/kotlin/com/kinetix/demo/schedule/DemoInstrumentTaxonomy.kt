package com.kinetix.demo.schedule

/**
 * Per-instrument asset-class / instrument-type classifier consulted by the
 * [SimulatedTraderJob] before posting a trade to position-service.
 *
 * Why this exists — kx-trader-review P0 #3.
 *
 * The demo book profiles in `DemoBookProfiles.kt` ship with a *book-level*
 * `assetClass` tag (e.g. `balanced-income` is `EQUITY`, `macro-hedge` is
 * `FX`). For books with a single asset class that tag is right — every
 * trade inherits it. But several books mix asset classes:
 *
 *  - `multi-asset` (EQUITY) trades `AAPL`, `MSFT`, **`UST-5Y`, `UST-10Y`**.
 *  - `balanced-income` (EQUITY) trades `JNJ`, `KO`, `PG`, **`UST-5Y`,
 *    `UST-10Y`**.
 *  - `macro-hedge` (FX) trades `EURUSD`, `GBPUSD`, `USDJPY`, **`UST-10Y`**.
 *
 * The trader-review walkthrough on the live demo observed Treasuries
 * rendering as "Cash Equity" on the Trades blotter — root cause: the
 * SimulatedTraderJob inherited the book's EQUITY tag for every trade,
 * regardless of the instrument. This classifier owns the per-instrument
 * taxonomy so the trade carries the right `(assetClass, instrumentType)`
 * pair on the wire.
 *
 * Unknown instrument ids fall back to the book-level [defaultAssetClass]
 * (which is forwarded through `instrumentTypeFor` in [SimulatedTraderJob]).
 * The classifier holds only the instruments where the per-book tag would
 * be wrong — adding a new equity does not require touching this file.
 *
 * Behaviour stays in sync with the canonical reference-data master
 * (`reference-data-service`'s `DevDataSeeder.INSTRUMENTS`): every entry
 * here mirrors the `instrumentType.assetClass()` of the instrument's
 * canonical reference-data row.
 */
object DemoInstrumentTaxonomy {

    /**
     * Result of classifying an instrument id. `assetClass` and
     * `instrumentType` are the values sent to position-service on the
     * [com.kinetix.demo.client.dtos.StrategyTradeRequest].
     */
    data class Classification(val assetClass: String, val instrumentType: String)

    // Treasury identifiers traded by multiple demo books — these used to
    // be mis-classified as EQUITY/CASH_EQUITY because they sit inside
    // books tagged "EQUITY" (multi-asset, balanced-income, macro-hedge).
    private val TREASURY_IDS: Set<String> = setOf("UST-2Y", "UST-5Y", "UST-10Y", "UST-30Y")

    // FX-pair identifiers. Books like balanced-income or multi-asset that
    // are tagged EQUITY but list an FX pair would mis-classify it as
    // CASH_EQUITY without this override.
    private val FX_SPOT_IDS: Set<String> = setOf(
        "EURUSD", "GBPUSD", "USDJPY", "AUDUSD", "USDCAD", "USDCHF",
        "EURGBP", "NZDUSD",
    )

    /**
     * Returns the `(assetClass, instrumentType)` pair that should be sent
     * to position-service when booking a trade for [instrumentId].
     *
     * If [instrumentId] is not in the per-instrument override table, the
     * classifier returns null so the caller can fall back to its existing
     * book-level mapping. This preserves backward compatibility with the
     * pre-fix path for instruments that are correctly tagged by their
     * book.
     */
    fun classify(instrumentId: String): Classification? = when (instrumentId) {
        in TREASURY_IDS -> Classification(
            assetClass = "FIXED_INCOME",
            instrumentType = "GOVERNMENT_BOND",
        )
        in FX_SPOT_IDS -> Classification(
            assetClass = "FX",
            instrumentType = "FX_SPOT",
        )
        else -> null
    }
}

package com.kinetix.common.demo

import com.kinetix.common.model.AssetClass

/**
 * The instrument universe used by Phase 0 cross-service demo synthesis.
 *
 * Sector + factor metadata for each known instrument. The shape of this list is the
 * single source-of-truth seen by every per-service seeder; adding a new tradeable
 * symbol is done here once.
 *
 * Annual vols are sized so that, after Student-t marginals (df=5) and GARCH
 * clustering, realised daily-return std maps roughly to the per-asset-class
 * historical norms seen in 2019-2024.
 */
object DemoTapeUniverse {

    val SPECS: List<InstrumentTapeSpec> = listOf(
        // ── Tech ──
        InstrumentTapeSpec("AAPL", AssetClass.EQUITY, Sector.TECH, "USD", 187.10, 0.24, 1.10, 0.85),
        InstrumentTapeSpec("MSFT", AssetClass.EQUITY, Sector.TECH, "USD", 422.30, 0.22, 1.05, 0.85),
        InstrumentTapeSpec("GOOGL", AssetClass.EQUITY, Sector.TECH, "USD", 176.50, 0.26, 1.10, 0.90),
        InstrumentTapeSpec("AMZN", AssetClass.EQUITY, Sector.TECH, "USD", 207.80, 0.28, 1.15, 0.85),
        InstrumentTapeSpec("META", AssetClass.EQUITY, Sector.TECH, "USD", 498.20, 0.34, 1.30, 1.10),
        InstrumentTapeSpec("NVDA", AssetClass.EQUITY, Sector.TECH, "USD", 875.00, 0.45, 1.50, 1.30),
        InstrumentTapeSpec("TSLA", AssetClass.EQUITY, Sector.TECH, "USD", 244.50, 0.55, 1.50, 1.40),
        InstrumentTapeSpec("AMD", AssetClass.EQUITY, Sector.TECH, "USD", 158.40, 0.42, 1.40, 1.20),
        InstrumentTapeSpec("INTC", AssetClass.EQUITY, Sector.TECH, "USD", 22.80, 0.32, 1.10, 1.00),
        InstrumentTapeSpec("CRM", AssetClass.EQUITY, Sector.TECH, "USD", 298.50, 0.30, 1.15, 1.00),
        InstrumentTapeSpec("ORCL", AssetClass.EQUITY, Sector.TECH, "USD", 172.30, 0.22, 0.95, 0.85),
        InstrumentTapeSpec("ADBE", AssetClass.EQUITY, Sector.TECH, "USD", 462.80, 0.30, 1.15, 1.00),
        InstrumentTapeSpec("BABA", AssetClass.EQUITY, Sector.TECH, "USD", 82.40, 0.45, 1.10, 1.20),

        // ── Financials ──
        InstrumentTapeSpec("JPM", AssetClass.EQUITY, Sector.FINANCIALS, "USD", 206.50, 0.24, 1.10, 0.95, ratesSensitivity = 0.30),
        InstrumentTapeSpec("BAC", AssetClass.EQUITY, Sector.FINANCIALS, "USD", 38.20, 0.26, 1.15, 1.00, ratesSensitivity = 0.35),
        InstrumentTapeSpec("GS", AssetClass.EQUITY, Sector.FINANCIALS, "USD", 482.50, 0.28, 1.20, 1.05, ratesSensitivity = 0.30),
        InstrumentTapeSpec("MS", AssetClass.EQUITY, Sector.FINANCIALS, "USD", 98.60, 0.28, 1.15, 1.05, ratesSensitivity = 0.30),

        // ── Consumer ──
        InstrumentTapeSpec("DIS", AssetClass.EQUITY, Sector.CONSUMER, "USD", 108.30, 0.26, 1.05, 0.85),
        InstrumentTapeSpec("KO", AssetClass.EQUITY, Sector.CONSUMER, "USD", 61.20, 0.16, 0.65, 0.55),
        InstrumentTapeSpec("WMT", AssetClass.EQUITY, Sector.CONSUMER, "USD", 168.50, 0.16, 0.65, 0.55),

        // ── Healthcare ──
        InstrumentTapeSpec("JNJ", AssetClass.EQUITY, Sector.HEALTHCARE, "USD", 155.80, 0.16, 0.65, 0.50),
        InstrumentTapeSpec("PFE", AssetClass.EQUITY, Sector.HEALTHCARE, "USD", 27.40, 0.22, 0.80, 0.85),
        InstrumentTapeSpec("UNH", AssetClass.EQUITY, Sector.HEALTHCARE, "USD", 528.30, 0.22, 0.85, 0.85),

        // ── Energy ──
        InstrumentTapeSpec("XOM", AssetClass.EQUITY, Sector.ENERGY, "USD", 112.40, 0.28, 0.85, 1.30),
        InstrumentTapeSpec("CVX", AssetClass.EQUITY, Sector.ENERGY, "USD", 158.70, 0.28, 0.85, 1.30),

        // ── Equity index ──
        InstrumentTapeSpec("IDX-SPX", AssetClass.EQUITY, Sector.OTHER, "USD", 5000.00, 0.18, 1.0, 0.0, idiosyncraticVolMultiplier = 0.0, priceScale = 2),

        // ── FX (low equity beta, dollar/rates driven) ──
        InstrumentTapeSpec("EURUSD", AssetClass.FX, Sector.OTHER, "USD", 1.0830, 0.08, 0.05, 0.0, dollarSensitivity = -1.0, priceScale = 4),
        InstrumentTapeSpec("GBPUSD", AssetClass.FX, Sector.OTHER, "USD", 1.2550, 0.09, 0.10, 0.0, dollarSensitivity = -1.0, priceScale = 4),
        InstrumentTapeSpec("USDJPY", AssetClass.FX, Sector.OTHER, "USD", 149.20, 0.10, -0.05, 0.0, dollarSensitivity = 1.0, ratesSensitivity = 0.30, priceScale = 4),
        InstrumentTapeSpec("AUDUSD", AssetClass.FX, Sector.OTHER, "USD", 0.6520, 0.10, 0.20, 0.0, dollarSensitivity = -1.0, priceScale = 4),
        InstrumentTapeSpec("USDCAD", AssetClass.FX, Sector.OTHER, "CAD", 1.3580, 0.08, -0.05, 0.0, dollarSensitivity = 1.0, priceScale = 4),
        InstrumentTapeSpec("USDCHF", AssetClass.FX, Sector.OTHER, "CHF", 0.8820, 0.08, -0.10, 0.0, dollarSensitivity = 0.7, priceScale = 4),
        InstrumentTapeSpec("EURGBP", AssetClass.FX, Sector.OTHER, "GBP", 0.8580, 0.06, 0.05, 0.0, priceScale = 4),
        InstrumentTapeSpec("NZDUSD", AssetClass.FX, Sector.OTHER, "USD", 0.6080, 0.11, 0.20, 0.0, dollarSensitivity = -1.0, priceScale = 4),

        // ── FX forwards (track spot + small carry differential) ──
        InstrumentTapeSpec("EURUSD-6M", AssetClass.FX, Sector.OTHER, "USD", 1.0860, 0.08, 0.05, 0.0, dollarSensitivity = -1.0, priceScale = 4),
        InstrumentTapeSpec("GBPUSD-3M", AssetClass.FX, Sector.OTHER, "USD", 1.2750, 0.09, 0.10, 0.0, dollarSensitivity = -1.0, priceScale = 4),
        InstrumentTapeSpec("USDJPY-3M", AssetClass.FX, Sector.OTHER, "JPY", 148.80, 0.10, -0.05, 0.0, dollarSensitivity = 1.0, ratesSensitivity = 0.30, priceScale = 4),

        // ── Government bonds (price moves inversely to rates) ──
        InstrumentTapeSpec("US2Y", AssetClass.FIXED_INCOME, Sector.OTHER, "USD", 99.30, 0.012, 0.0, 0.0, ratesSensitivity = -1.8),
        InstrumentTapeSpec("US5Y", AssetClass.FIXED_INCOME, Sector.OTHER, "USD", 98.50, 0.025, 0.0, 0.0, ratesSensitivity = -4.2),
        InstrumentTapeSpec("US10Y", AssetClass.FIXED_INCOME, Sector.OTHER, "USD", 96.85, 0.045, 0.0, 0.0, ratesSensitivity = -7.5),
        InstrumentTapeSpec("US30Y", AssetClass.FIXED_INCOME, Sector.OTHER, "USD", 92.80, 0.075, 0.0, 0.0, ratesSensitivity = -16.0),
        InstrumentTapeSpec("DE2Y", AssetClass.FIXED_INCOME, Sector.OTHER, "EUR", 99.60, 0.012, 0.0, 0.0, ratesSensitivity = -1.8),
        InstrumentTapeSpec("DE10Y", AssetClass.FIXED_INCOME, Sector.OTHER, "EUR", 97.50, 0.045, 0.0, 0.0, ratesSensitivity = -7.5),
        InstrumentTapeSpec("UK10Y", AssetClass.FIXED_INCOME, Sector.OTHER, "GBP", 95.80, 0.045, 0.0, 0.0, ratesSensitivity = -7.5),
        InstrumentTapeSpec("JP10Y", AssetClass.FIXED_INCOME, Sector.OTHER, "JPY", 99.20, 0.030, 0.0, 0.0, ratesSensitivity = -7.5),

        // ── Corporate bonds (rates + small credit spread move) ──
        InstrumentTapeSpec("JPM-BOND-2031", AssetClass.FIXED_INCOME, Sector.OTHER, "USD", 101.20, 0.05, 0.10, 0.0, ratesSensitivity = -5.0),
        InstrumentTapeSpec("AAPL-BOND-2030", AssetClass.FIXED_INCOME, Sector.OTHER, "USD", 100.80, 0.04, 0.05, 0.0, ratesSensitivity = -4.5),
        InstrumentTapeSpec("GS-BOND-2029", AssetClass.FIXED_INCOME, Sector.OTHER, "USD", 102.20, 0.06, 0.15, 0.0, ratesSensitivity = -4.0),
        InstrumentTapeSpec("MSFT-BOND-2032", AssetClass.FIXED_INCOME, Sector.OTHER, "USD", 99.50, 0.04, 0.05, 0.0, ratesSensitivity = -5.5),

        // ── IRS — driven by rates, near-zero standalone vol ──
        InstrumentTapeSpec("USD-SOFR-5Y", AssetClass.FIXED_INCOME, Sector.OTHER, "USD", 99.70, 0.025, 0.0, 0.0, ratesSensitivity = -4.0),
        InstrumentTapeSpec("USD-SOFR-10Y", AssetClass.FIXED_INCOME, Sector.OTHER, "USD", 99.40, 0.045, 0.0, 0.0, ratesSensitivity = -7.5),
        InstrumentTapeSpec("EUR-ESTR-5Y", AssetClass.FIXED_INCOME, Sector.OTHER, "EUR", 99.50, 0.025, 0.0, 0.0, ratesSensitivity = -4.0),

        // ── Commodities ──
        InstrumentTapeSpec("GC", AssetClass.COMMODITY, Sector.OTHER, "USD", 2038.20, 0.18, -0.10, 0.0, dollarSensitivity = -0.50),
        InstrumentTapeSpec("SI", AssetClass.COMMODITY, Sector.OTHER, "USD", 22.80, 0.30, 0.20, 0.0, dollarSensitivity = -0.40),
        InstrumentTapeSpec("CL", AssetClass.COMMODITY, Sector.OTHER, "USD", 75.80, 0.32, 0.30, 0.0, dollarSensitivity = -0.60),
        InstrumentTapeSpec("WTI-AUG26", AssetClass.COMMODITY, Sector.OTHER, "USD", 74.50, 0.30, 0.30, 0.0, dollarSensitivity = -0.55),
        InstrumentTapeSpec("NG", AssetClass.COMMODITY, Sector.OTHER, "USD", 2.85, 0.55, 0.10, 0.0),
        InstrumentTapeSpec("HG", AssetClass.COMMODITY, Sector.OTHER, "USD", 4.15, 0.24, 0.20, 0.0, dollarSensitivity = -0.40),
        InstrumentTapeSpec("PL", AssetClass.COMMODITY, Sector.OTHER, "USD", 980.00, 0.22, 0.10, 0.0, dollarSensitivity = -0.40),
        InstrumentTapeSpec("ZC", AssetClass.COMMODITY, Sector.OTHER, "USD", 4.52, 0.30, 0.05, 0.0),
        InstrumentTapeSpec("GC-C-2200-DEC26", AssetClass.COMMODITY, Sector.OTHER, "USD", 42.30, 0.40, -0.10, 0.0, dollarSensitivity = -0.50),
        InstrumentTapeSpec("CL-P-70-DEC26", AssetClass.COMMODITY, Sector.OTHER, "USD", 3.80, 0.45, 0.30, 0.0, dollarSensitivity = -0.55),

        // ── Equity options (treated as standalone for tape; production paths derive Greeks) ──
        InstrumentTapeSpec("AAPL-C-200-20260620", AssetClass.DERIVATIVE, Sector.TECH, "USD", 8.50, 0.65, 1.10, 0.85, idiosyncraticVolMultiplier = 1.5),
        InstrumentTapeSpec("AAPL-P-180-20260620", AssetClass.DERIVATIVE, Sector.TECH, "USD", 6.20, 0.65, 1.10, 0.85, idiosyncraticVolMultiplier = 1.5),
        InstrumentTapeSpec("MSFT-C-450-20260620", AssetClass.DERIVATIVE, Sector.TECH, "USD", 10.80, 0.60, 1.05, 0.85, idiosyncraticVolMultiplier = 1.5),
        InstrumentTapeSpec("MSFT-P-400-20260620", AssetClass.DERIVATIVE, Sector.TECH, "USD", 9.50, 0.60, 1.05, 0.85, idiosyncraticVolMultiplier = 1.5),
        InstrumentTapeSpec("NVDA-C-950-20260620", AssetClass.DERIVATIVE, Sector.TECH, "USD", 28.50, 0.85, 1.50, 1.30, idiosyncraticVolMultiplier = 1.7),
        InstrumentTapeSpec("NVDA-P-800-20260620", AssetClass.DERIVATIVE, Sector.TECH, "USD", 35.20, 0.85, 1.50, 1.30, idiosyncraticVolMultiplier = 1.7),
        InstrumentTapeSpec("TSLA-C-280-20260620", AssetClass.DERIVATIVE, Sector.TECH, "USD", 12.40, 0.95, 1.50, 1.40, idiosyncraticVolMultiplier = 1.7),
        InstrumentTapeSpec("TSLA-P-220-20260620", AssetClass.DERIVATIVE, Sector.TECH, "USD", 13.20, 0.95, 1.50, 1.40, idiosyncraticVolMultiplier = 1.7),
        InstrumentTapeSpec("GOOGL-C-190-20260620", AssetClass.DERIVATIVE, Sector.TECH, "USD", 8.30, 0.65, 1.10, 0.90, idiosyncraticVolMultiplier = 1.5),
        InstrumentTapeSpec("GOOGL-P-160-20260620", AssetClass.DERIVATIVE, Sector.TECH, "USD", 6.50, 0.65, 1.10, 0.90, idiosyncraticVolMultiplier = 1.5),
        InstrumentTapeSpec("AMZN-C-220-20260620", AssetClass.DERIVATIVE, Sector.TECH, "USD", 10.20, 0.70, 1.15, 0.85, idiosyncraticVolMultiplier = 1.5),
        InstrumentTapeSpec("AMZN-P-190-20260620", AssetClass.DERIVATIVE, Sector.TECH, "USD", 8.60, 0.70, 1.15, 0.85, idiosyncraticVolMultiplier = 1.5),
        InstrumentTapeSpec("SPX-PUT-4500", AssetClass.DERIVATIVE, Sector.OTHER, "USD", 32.50, 0.60, 1.0, 0.0, idiosyncraticVolMultiplier = 2.0),
        InstrumentTapeSpec("SPX-PUT-4800", AssetClass.DERIVATIVE, Sector.OTHER, "USD", 52.40, 0.60, 1.0, 0.0, idiosyncraticVolMultiplier = 2.0),
        InstrumentTapeSpec("SPX-CALL-5000", AssetClass.DERIVATIVE, Sector.OTHER, "USD", 39.50, 0.60, 1.0, 0.0, idiosyncraticVolMultiplier = 2.0),
        InstrumentTapeSpec("SPX-CALL-5200", AssetClass.DERIVATIVE, Sector.OTHER, "USD", 25.60, 0.60, 1.0, 0.0, idiosyncraticVolMultiplier = 2.0),
        InstrumentTapeSpec("VIX-PUT-15", AssetClass.DERIVATIVE, Sector.OTHER, "USD", 4.10, 1.20, -3.0, 0.0, idiosyncraticVolMultiplier = 2.5),
        InstrumentTapeSpec("USDJPY-C-155-SEP26", AssetClass.DERIVATIVE, Sector.OTHER, "USD", 3.20, 0.30, 0.0, 0.0, dollarSensitivity = 2.0, ratesSensitivity = 0.40),
        InstrumentTapeSpec("EURUSD-P-1.08-SEP26", AssetClass.DERIVATIVE, Sector.OTHER, "USD", 2.40, 0.25, 0.0, 0.0, dollarSensitivity = 2.0),

        // ── Equity / index futures ──
        InstrumentTapeSpec("SPX-SEP26", AssetClass.DERIVATIVE, Sector.OTHER, "USD", 4980.00, 0.18, 1.0, 0.0),
        InstrumentTapeSpec("NDX-SEP26", AssetClass.DERIVATIVE, Sector.TECH, "USD", 17800.00, 0.22, 1.10, 0.95),
        InstrumentTapeSpec("RTY-SEP26", AssetClass.DERIVATIVE, Sector.OTHER, "USD", 2080.00, 0.24, 1.10, 0.30),
    )

    val BY_SYMBOL: Map<String, InstrumentTapeSpec> = SPECS.associateBy { it.symbol }

    fun specOrNull(symbol: String): InstrumentTapeSpec? = BY_SYMBOL[symbol]
    fun spec(symbol: String): InstrumentTapeSpec =
        BY_SYMBOL[symbol] ?: error("Instrument $symbol is not in the demo tape universe")
}

package com.kinetix.position.seed

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Side
import com.kinetix.common.model.TradeId
import com.kinetix.position.model.LimitDefinition
import com.kinetix.position.model.LimitLevel
import com.kinetix.position.model.LimitType
import com.kinetix.position.service.BookTradeCommand
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Currency

/**
 * Stress scenario (Phase 2 Gap 2, demo-review v2).
 *
 * Three books, ~100 concentrated positions in total:
 *
 *   stress-momentum (healthy) — 30 long-only momentum positions, gross
 *       notional under the $30M book limit.
 *   stress-credit   (healthy) — 30 mixed credit + bond hedges, gross
 *       notional under the $50M book limit.
 *   stress-vol      (BREACHED) — 40 concentrated positions in a handful
 *       of vol names; gross notional ~$60M against a $35M book limit AND
 *       a single-name concentration ~45% against a 30% limit. Demos the
 *       scripted limit-excession workflow.
 */
object StressScenario {

    const val MOMENTUM_BOOK: String = "stress-momentum"
    const val CREDIT_BOOK: String = "stress-credit"
    const val VOL_BOOK: String = "stress-vol"

    private val USD: Currency = Currency.getInstance("USD")
    private val BASE_TIME: Instant = Instant.parse("2026-02-21T14:00:00Z")

    data class StressInstrument(
        val book: String,
        val id: InstrumentId,
        val assetClass: AssetClass,
        val instrumentType: String,
        val side: Side,
        val typicalPrice: BigDecimal,
        val typicalQty: BigDecimal,
    )

    val INSTRUMENTS: List<StressInstrument> by lazy { buildInstruments() }

    val TRADES: List<BookTradeCommand> by lazy { buildTrades() }

    val LIMIT_DEFINITIONS: List<LimitDefinition> by lazy { buildLimits() }

    private fun buildInstruments(): List<StressInstrument> {
        val out = ArrayList<StressInstrument>(100)

        // ── stress-momentum: 30 long names, ~$25M gross (under $30M limit) ──
        val momentumNames = listOf(
            // (id, assetClass, instrType, price, qty)
            Five("NVDA", AssetClass.EQUITY, "CASH_EQUITY", "885.00", 600),
            Five("META", AssetClass.EQUITY, "CASH_EQUITY", "502.30", 800),
            Five("MSFT", AssetClass.EQUITY, "CASH_EQUITY", "421.50", 700),
            Five("AAPL", AssetClass.EQUITY, "CASH_EQUITY", "185.50", 1500),
            Five("AMZN", AssetClass.EQUITY, "CASH_EQUITY", "206.00", 1200),
            Five("GOOGL", AssetClass.EQUITY, "CASH_EQUITY", "176.80", 1300),
            Five("AMD", AssetClass.EQUITY, "CASH_EQUITY", "164.20", 1800),
            Five("TSLA", AssetClass.EQUITY, "CASH_EQUITY", "249.50", 900),
            Five("CRM", AssetClass.EQUITY, "CASH_EQUITY", "305.80", 600),
            Five("ADBE", AssetClass.EQUITY, "CASH_EQUITY", "470.50", 500),
            Five("ORCL", AssetClass.EQUITY, "CASH_EQUITY", "176.50", 1500),
            Five("INTC", AssetClass.EQUITY, "CASH_EQUITY", "24.10", 12000),
            Five("UBER", AssetClass.EQUITY, "CASH_EQUITY", "72.40", 4500),
            Five("SNOW", AssetClass.EQUITY, "CASH_EQUITY", "152.30", 1600),
            Five("NET", AssetClass.EQUITY, "CASH_EQUITY", "98.20", 2400),
            Five("ZS", AssetClass.EQUITY, "CASH_EQUITY", "210.80", 1200),
            Five("DDOG", AssetClass.EQUITY, "CASH_EQUITY", "115.40", 2200),
            Five("CRWD", AssetClass.EQUITY, "CASH_EQUITY", "295.10", 800),
            Five("PLTR", AssetClass.EQUITY, "CASH_EQUITY", "22.40", 12000),
            Five("AVGO", AssetClass.EQUITY, "CASH_EQUITY", "1340.00", 200),
            Five("QCOM", AssetClass.EQUITY, "CASH_EQUITY", "168.20", 1100),
            Five("MU", AssetClass.EQUITY, "CASH_EQUITY", "98.60", 2000),
            Five("AMAT", AssetClass.EQUITY, "CASH_EQUITY", "215.80", 800),
            Five("LRCX", AssetClass.EQUITY, "CASH_EQUITY", "905.20", 200),
            Five("KLAC", AssetClass.EQUITY, "CASH_EQUITY", "705.30", 240),
            Five("PANW", AssetClass.EQUITY, "CASH_EQUITY", "320.40", 600),
            Five("MDB", AssetClass.EQUITY, "CASH_EQUITY", "320.10", 500),
            Five("SHOP", AssetClass.EQUITY, "CASH_EQUITY", "82.40", 3200),
            Five("NOW", AssetClass.EQUITY, "CASH_EQUITY", "740.10", 280),
            Five("INTU", AssetClass.EQUITY, "CASH_EQUITY", "630.50", 300),
        )
        for (spec in momentumNames) {
            out += StressInstrument(
                book = MOMENTUM_BOOK,
                id = InstrumentId(spec.id),
                assetClass = spec.assetClass,
                instrumentType = spec.instrumentType,
                side = Side.BUY,
                typicalPrice = BigDecimal(spec.price),
                typicalQty = BigDecimal(spec.qty),
            )
        }

        // ── stress-credit: 30 mixed credit + bond hedges, ~$40M gross under $50M ──
        val creditNames = listOf(
            Five("US10Y", AssetClass.FIXED_INCOME, "GOVERNMENT_BOND", "96.50", 8000),
            Five("US30Y", AssetClass.FIXED_INCOME, "GOVERNMENT_BOND", "92.10", 6000),
            Five("US5Y", AssetClass.FIXED_INCOME, "GOVERNMENT_BOND", "98.80", 10000),
            Five("US2Y", AssetClass.FIXED_INCOME, "GOVERNMENT_BOND", "99.25", 12000),
            Five("DE10Y", AssetClass.FIXED_INCOME, "GOVERNMENT_BOND", "97.80", 5000),
            Five("UK10Y", AssetClass.FIXED_INCOME, "GOVERNMENT_BOND", "96.30", 4000),
            Five("JP10Y", AssetClass.FIXED_INCOME, "GOVERNMENT_BOND", "99.40", 4000),
            Five("JPM-BOND-2031", AssetClass.FIXED_INCOME, "CORPORATE_BOND", "101.50", 5000),
            Five("GS-BOND-2029", AssetClass.FIXED_INCOME, "CORPORATE_BOND", "103.10", 4000),
            Five("AAPL-BOND-2030", AssetClass.FIXED_INCOME, "CORPORATE_BOND", "101.50", 4000),
            Five("MSFT-BOND-2032", AssetClass.FIXED_INCOME, "CORPORATE_BOND", "100.20", 5000),
            Five("USD-SOFR-5Y", AssetClass.FIXED_INCOME, "INTEREST_RATE_SWAP", "99.80", 3000),
            Five("USD-SOFR-10Y", AssetClass.FIXED_INCOME, "INTEREST_RATE_SWAP", "99.70", 3000),
            Five("EUR-ESTR-5Y", AssetClass.FIXED_INCOME, "INTEREST_RATE_SWAP", "99.75", 2500),
            Five("HYG", AssetClass.EQUITY, "CASH_EQUITY", "78.40", 5000),
            Five("LQD", AssetClass.EQUITY, "CASH_EQUITY", "108.20", 4000),
            Five("EMB", AssetClass.EQUITY, "CASH_EQUITY", "89.10", 4500),
            Five("TLT", AssetClass.EQUITY, "CASH_EQUITY", "92.40", 5000),
            Five("IEF", AssetClass.EQUITY, "CASH_EQUITY", "94.20", 4500),
            // FX kept only where price×qty stays USD-denominated; USDJPY/USDCHF
            // omitted on purpose to keep credit-book gross within $50M limit.
            Five("EURUSD", AssetClass.FX, "FX_SPOT", "1.0842", 2000000),
            Five("GBPUSD", AssetClass.FX, "FX_SPOT", "1.2600", 1500000),
            Five("AGG", AssetClass.EQUITY, "CASH_EQUITY", "98.60", 4500),
            Five("BND", AssetClass.EQUITY, "CASH_EQUITY", "74.20", 5500),
            Five("JPM", AssetClass.EQUITY, "CASH_EQUITY", "209.00", 1500),
            Five("BAC", AssetClass.EQUITY, "CASH_EQUITY", "39.80", 5000),
            Five("WFC", AssetClass.EQUITY, "CASH_EQUITY", "59.20", 3500),
            Five("C", AssetClass.EQUITY, "CASH_EQUITY", "67.80", 3000),
            Five("MS", AssetClass.EQUITY, "CASH_EQUITY", "101.40", 1500),
            Five("GS", AssetClass.EQUITY, "CASH_EQUITY", "495.30", 300),
            Five("AAPL", AssetClass.EQUITY, "CASH_EQUITY", "185.50", 1500),
        )
        for ((idx, spec) in creditNames.withIndex()) {
            // Alternate sides to give the book hedged character
            val side = if (idx % 3 == 0) Side.SELL else Side.BUY
            out += StressInstrument(
                book = CREDIT_BOOK,
                id = InstrumentId(spec.id),
                assetClass = spec.assetClass,
                instrumentType = spec.instrumentType,
                side = side,
                typicalPrice = BigDecimal(spec.price),
                typicalQty = BigDecimal(spec.qty),
            )
        }

        // ── stress-vol: 40 positions, concentrated, BREACHED ──
        // Heavy NVDA leg (~$27M) drives single-name concentration breach AND
        // overall book notional well above the $35M limit.
        val volHeavy = listOf(
            Five("NVDA", AssetClass.EQUITY, "CASH_EQUITY", "885.00", 30000), // ~$26.55M
            Five("NVDA-C-950-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "28.50", 25000), // ~$0.71M
            Five("NVDA-P-800-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "35.20", 15000), // ~$0.53M
            Five("MSFT", AssetClass.EQUITY, "CASH_EQUITY", "421.50", 25000), // ~$10.54M
            Five("MSFT-C-450-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "10.80", 18000), // ~$0.19M
            Five("MSFT-P-400-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "9.50", 18000),
            Five("META-C-540-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "12.40", 12000),
            Five("META-P-460-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "8.60", 10000),
            Five("SPX-CALL-5200", AssetClass.DERIVATIVE, "EQUITY_OPTION", "22.30", 9000),
            Five("SPX-PUT-4500", AssetClass.DERIVATIVE, "EQUITY_OPTION", "32.50", 8000),
            Five("SPX-PUT-4800", AssetClass.DERIVATIVE, "EQUITY_OPTION", "55.40", 6000),
            Five("SPX-CALL-5000", AssetClass.DERIVATIVE, "EQUITY_OPTION", "41.50", 6500),
            Five("VIX-PUT-15", AssetClass.DERIVATIVE, "EQUITY_OPTION", "3.75", 25000),
            Five("VIX-CALL-25", AssetClass.DERIVATIVE, "EQUITY_OPTION", "4.20", 25000),
            Five("AAPL-C-200-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "8.50", 14000),
            Five("AAPL-P-180-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "6.20", 14000),
            Five("AMZN-C-220-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "8.60", 12000),
            Five("AMZN-P-190-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "9.20", 10000),
            Five("GOOGL-C-190-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "6.80", 12000),
            Five("GOOGL-P-160-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "6.50", 12000),
            Five("TSLA-C-280-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "12.40", 8000),
            Five("TSLA-P-220-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "13.20", 8000),
            Five("AMD-C-180-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "9.20", 8000),
            Five("AMD-P-150-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "7.40", 8000),
            Five("CRM-C-320-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "11.30", 5000),
            Five("CRM-P-290-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "9.80", 5000),
            Five("ADBE-C-490-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "14.20", 3500),
            Five("ADBE-P-450-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "12.40", 3500),
            Five("NDX-SEP26", AssetClass.DERIVATIVE, "EQUITY_FUTURE", "18100.00", 80),
            Five("SPX-SEP26", AssetClass.DERIVATIVE, "EQUITY_FUTURE", "5020.00", 240),
            Five("RTY-SEP26", AssetClass.DERIVATIVE, "EQUITY_FUTURE", "2120.00", 200),
            Five("ES-MAR26", AssetClass.DERIVATIVE, "EQUITY_FUTURE", "5040.00", 180),
            Five("NQ-MAR26", AssetClass.DERIVATIVE, "EQUITY_FUTURE", "18050.00", 70),
            Five("VX-MAR26", AssetClass.DERIVATIVE, "EQUITY_FUTURE", "16.20", 4000),
            Five("AAPL-WEEKLY-C-190", AssetClass.DERIVATIVE, "EQUITY_OPTION", "3.40", 18000),
            Five("AAPL-WEEKLY-P-180", AssetClass.DERIVATIVE, "EQUITY_OPTION", "2.60", 18000),
            Five("MSFT-WEEKLY-C-425", AssetClass.DERIVATIVE, "EQUITY_OPTION", "5.80", 10000),
            Five("MSFT-WEEKLY-P-415", AssetClass.DERIVATIVE, "EQUITY_OPTION", "4.40", 10000),
            Five("NVDA-WEEKLY-C-900", AssetClass.DERIVATIVE, "EQUITY_OPTION", "15.20", 9000),
            Five("NVDA-WEEKLY-P-870", AssetClass.DERIVATIVE, "EQUITY_OPTION", "13.80", 9000),
        )
        for ((idx, spec) in volHeavy.withIndex()) {
            // Mix of buys and sells — the book is a vol strategy, not a directional one
            val side = if (idx % 2 == 0) Side.BUY else Side.SELL
            out += StressInstrument(
                book = VOL_BOOK,
                id = InstrumentId(spec.id),
                assetClass = spec.assetClass,
                instrumentType = spec.instrumentType,
                side = side,
                typicalPrice = BigDecimal(spec.price),
                typicalQty = BigDecimal(spec.qty),
            )
        }

        return out
    }

    private fun buildTrades(): List<BookTradeCommand> {
        return INSTRUMENTS.mapIndexed { idx, spec ->
            val tradedAt = BASE_TIME.minus((idx % 19).toLong(), ChronoUnit.DAYS)
            val bookAbbrev = spec.book.substringAfter("stress-").take(4)
            val instrAbbrev = spec.id.value.lowercase().replace("-", "").take(12)
            BookTradeCommand(
                tradeId = TradeId("seed-strs-$bookAbbrev-$instrAbbrev-${idx.toString().padStart(3, '0')}"),
                bookId = BookId(spec.book),
                instrumentId = spec.id,
                assetClass = spec.assetClass,
                side = spec.side,
                quantity = spec.typicalQty,
                price = Money(spec.typicalPrice, USD),
                tradedAt = tradedAt,
                instrumentType = spec.instrumentType,
            )
        }
    }

    private fun buildLimits(): List<LimitDefinition> = listOf(
        // Healthy momentum book — $30M notional limit.
        LimitDefinition(
            id = "seed-lim-stress-mom-notional",
            level = LimitLevel.BOOK,
            entityId = MOMENTUM_BOOK,
            limitType = LimitType.NOTIONAL,
            limitValue = BigDecimal("30000000"),
            intradayLimit = BigDecimal("35000000"),
            overnightLimit = BigDecimal("28000000"),
            active = true,
        ),
        // Healthy credit book — $50M notional limit.
        LimitDefinition(
            id = "seed-lim-stress-credit-notional",
            level = LimitLevel.BOOK,
            entityId = CREDIT_BOOK,
            limitType = LimitType.NOTIONAL,
            limitValue = BigDecimal("50000000"),
            intradayLimit = BigDecimal("55000000"),
            overnightLimit = BigDecimal("48000000"),
            active = true,
        ),
        // Vol book — BREACHED. Book notional limit deliberately below
        // expected gross; single-name concentration limit below the
        // NVDA share so the demo always opens with a hard breach.
        LimitDefinition(
            id = "seed-lim-stress-vol-notional",
            level = LimitLevel.BOOK,
            entityId = VOL_BOOK,
            limitType = LimitType.NOTIONAL,
            limitValue = BigDecimal("35000000"),
            intradayLimit = BigDecimal("40000000"),
            overnightLimit = BigDecimal("32000000"),
            active = true,
        ),
        LimitDefinition(
            id = "seed-lim-stress-vol-conc",
            level = LimitLevel.BOOK,
            entityId = VOL_BOOK,
            limitType = LimitType.CONCENTRATION,
            limitValue = BigDecimal("0.30"),
            intradayLimit = null,
            overnightLimit = null,
            active = true,
        ),
    )

    private data class Five(
        val id: String,
        val assetClass: AssetClass,
        val instrumentType: String,
        val price: String,
        val qty: Int,
    )
}

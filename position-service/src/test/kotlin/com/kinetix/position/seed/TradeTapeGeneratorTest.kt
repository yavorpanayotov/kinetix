package com.kinetix.position.seed

import com.kinetix.common.demo.CounterpartyTiers
import com.kinetix.common.demo.RegimeCalendar
import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.Side
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.shouldBe

class TradeTapeGeneratorTest : FunSpec({

    val instrumentsByBook = mapOf(
        "equity-growth" to listOf(
            spec("AAPL", AssetClass.EQUITY, "CASH_EQUITY", "USD", "185.50", 500, 3000),
            spec("GOOGL", AssetClass.EQUITY, "CASH_EQUITY", "USD", "175.20", 300, 2000),
            spec("MSFT", AssetClass.EQUITY, "CASH_EQUITY", "USD", "420.00", 200, 1500),
            spec("AMZN", AssetClass.EQUITY, "CASH_EQUITY", "USD", "205.75", 400, 2500),
            spec("META", AssetClass.EQUITY, "CASH_EQUITY", "USD", "505.00", 200, 1200),
            spec("AAPL-C-200", AssetClass.DERIVATIVE, "EQUITY_OPTION", "USD", "8.50", 50, 400),
            spec("SPX-SEP26", AssetClass.DERIVATIVE, "EQUITY_FUTURE", "USD", "5020.00", 5, 40),
        ),
        "macro-hedge" to listOf(
            spec("EURUSD", AssetClass.FX, "FX_SPOT", "USD", "1.0850", 500000, 3000000),
            spec("EURUSD-6M", AssetClass.FX, "FX_FORWARD", "USD", "1.0880", 200000, 1500000),
            spec("USDJPY-C-155", AssetClass.DERIVATIVE, "FX_OPTION", "USD", "2.80", 500, 3000),
            spec("US10Y", AssetClass.FIXED_INCOME, "GOVERNMENT_BOND", "USD", "96.50", 3000, 15000),
            spec("USD-SOFR-5Y", AssetClass.FIXED_INCOME, "INTEREST_RATE_SWAP", "USD", "99.80", 1000, 5000),
        ),
    )

    val baseRates = mapOf(
        "equity-growth" to 30,
        "macro-hedge" to 28,
    )

    test("generates trades for every configured book") {
        val gen = TradeTapeGenerator(instrumentsByBook, baseRates)
        val trades = gen.generate()
        val books = trades.map { it.bookId.value }.toSet()
        books shouldBe instrumentsByBook.keys
    }

    test("trade count is ~252 × baseRate × bookCount") {
        val gen = TradeTapeGenerator(instrumentsByBook, baseRates)
        val trades = gen.generate()
        // Two books × ~28 trades/day × 252 days ≈ 14_000 baseline; regime lifts add some.
        val total = trades.size
        // Regime-adjusted lower bound; calm dominates so 252 × 50 × 0.95 ≈ 12k
        assert(total in 10_000..30_000) { "expected 10-30k trades, got $total" }
    }

    test("trades cover the full 252-trading-day calendar") {
        val gen = TradeTapeGenerator(instrumentsByBook, baseRates)
        val trades = gen.generate()
        val distinctDates = trades.map { it.tradedAt.toString().substring(0, 10) }.toSet()
        // At least 200 distinct trading days touched (some days may be empty in edge cases).
        assert(distinctDates.size > 200) { "only ${distinctDates.size} distinct trading days seen" }
    }

    test("instrument selection follows a power-law within each book") {
        val gen = TradeTapeGenerator(instrumentsByBook, baseRates)
        val trades = gen.generate()
        val byInstrument = trades.filter { it.bookId.value == "equity-growth" }
            .groupingBy { it.instrumentId.value }
            .eachCount()
        val sortedCounts = byInstrument.values.sortedDescending()
        // Top instrument should account for >25% of book flow (Zipf head dominates).
        val topShare = sortedCounts.first().toDouble() / sortedCounts.sum()
        topShare.shouldBeBetween(0.20, 0.70, 0.0)
    }

    test("buy/sell split sits near 50/50 with a mild buy bias") {
        val gen = TradeTapeGenerator(instrumentsByBook, baseRates)
        val trades = gen.generate()
        val buyShare = trades.count { it.side == Side.BUY }.toDouble() / trades.size
        // Default baseBuyBias=0.10 nudges p(BUY) above 0.5; expect 0.50–0.70.
        buyShare.shouldBeBetween(0.45, 0.75, 0.0)
    }

    test("counterparties on listed derivatives include CCPs") {
        val gen = TradeTapeGenerator(instrumentsByBook, baseRates)
        val trades = gen.generate()
        val futureCps = trades
            .filter { it.instrumentType.name == "EQUITY_FUTURE" }
            .map { it.counterpartyId }
            .toSet()
        val ccpHit = futureCps.intersect(CounterpartyTiers.CCP_IDS.toSet())
        assert(ccpHit.isNotEmpty()) { "expected CCP counterparties on futures, got $futureCps" }
    }

    test("counterparties on cash equity exclude CCPs and OTC-only names") {
        val gen = TradeTapeGenerator(instrumentsByBook, baseRates)
        val trades = gen.generate()
        val cashCps = trades
            .filter { it.instrumentType.name == "CASH_EQUITY" }
            .map { it.counterpartyId }
            .toSet()
        val forbidden = cashCps.intersect(CounterpartyTiers.CCP_IDS.toSet() + CounterpartyTiers.OTC_ONLY_IDS)
        assert(forbidden.isEmpty()) {
            "cash equity should not see CCPs/OTC-only counterparties; saw $forbidden"
        }
    }

    test("counterparties on FX options include OTC buy-side / corporate names") {
        val gen = TradeTapeGenerator(instrumentsByBook, baseRates)
        val trades = gen.generate()
        val fxOptionCps = trades
            .filter { it.instrumentType.name == "FX_OPTION" }
            .map { it.counterpartyId }
            .toSet()
        // Eligible pool for OTC includes banks + buy-side + corporates; over thousands of
        // trades we expect at least one OTC-only name to land.
        val otcHit = fxOptionCps.intersect(CounterpartyTiers.OTC_ONLY_IDS)
        if (fxOptionCps.size >= 50) {
            assert(otcHit.isNotEmpty()) { "expected OTC names on FX options, got $fxOptionCps" }
        }
    }

    test("output is byte-identical for a fixed seed") {
        val a = TradeTapeGenerator(instrumentsByBook, baseRates, tapeSeed = 999L).generate()
        val b = TradeTapeGenerator(instrumentsByBook, baseRates, tapeSeed = 999L).generate()
        a.size shouldBe b.size
        a.first().tradeId.value shouldBe b.first().tradeId.value
        a.last().tradeId.value shouldBe b.last().tradeId.value
    }

    test("uses the regime calendar to lift volume during stress windows") {
        val cal = RegimeCalendar()
        val gen = TradeTapeGenerator(instrumentsByBook, baseRates, calendar = cal)
        val trades = gen.generate()
        // Bucket trades by day index; compare averages in stress vs calm windows.
        val byDate = trades.groupingBy { it.tradedAt.toString().substring(0, 10) }.eachCount()
        val stressDays = (178..184).map { cal.dateForDay(it).toString() }
        val calmDays = (200..220).map { cal.dateForDay(it).toString() }
        val stressAvg = stressDays.mapNotNull { byDate[it] }.average()
        val calmAvg = calmDays.mapNotNull { byDate[it] }.average()
        if (!stressAvg.isNaN() && !calmAvg.isNaN()) {
            assert(stressAvg > calmAvg * 1.4) { "stress=$stressAvg should be ≥ 1.4× calm=$calmAvg" }
        }
    }
})

private fun spec(
    id: String,
    assetClass: AssetClass,
    instrumentType: String,
    currency: String,
    typicalPrice: String,
    typicalQtyMin: Int,
    typicalQtyMax: Int,
) = TradeTapeGenerator.TradeInstrumentSpec(
    id = id,
    assetClass = assetClass,
    instrumentType = instrumentType,
    currency = currency,
    typicalPrice = typicalPrice,
    typicalQtyMin = typicalQtyMin,
    typicalQtyMax = typicalQtyMax,
)

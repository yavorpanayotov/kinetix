package com.kinetix.position.seed

import com.kinetix.common.demo.SeedProfile
import com.kinetix.common.model.*
import com.kinetix.position.fix.ExecutionCostAnalysis
import com.kinetix.position.fix.ExecutionCostMetrics
import com.kinetix.position.fix.ExecutionCostRepository
import com.kinetix.position.model.LimitDefinition
import com.kinetix.position.model.LimitLevel
import com.kinetix.position.model.LimitType
import com.kinetix.position.persistence.LimitDefinitionRepository
import com.kinetix.position.persistence.PositionRepository
import com.kinetix.position.persistence.TradeEventRepository
import com.kinetix.position.service.BookTradeCommand
import com.kinetix.position.service.TradeBookingService
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Currency

class DevDataSeeder(
    private val tradeBookingService: TradeBookingService,
    private val positionRepository: PositionRepository,
    private val limitDefinitionRepo: LimitDefinitionRepository? = null,
    private val executionCostRepo: ExecutionCostRepository? = null,
    private val tradeEventRepository: TradeEventRepository? = null,
    private val tapeTradesEnabled: Boolean = true,
) {
    private val log = LoggerFactory.getLogger(DevDataSeeder::class.java)

    suspend fun seed(profile: SeedProfile) {
        require(profile.implemented) { "Scenario '${profile.id}' is not implemented" }
        log.info("Seeding position-service with profile={}", profile.id)
        when (profile) {
            is SeedProfile.EquityLS -> seedEquityLongShort()
            else -> seed()
        }
    }

    /**
     * Phase 2 Gap 2 — equity long/short scenario.
     *
     * Establishes 800 distinct positions in a single book ("equity-ls"),
     * factor-neutral by gross notional and tilted across 8 sectors
     * (4 net long, 4 net short). See [EquityLongShortScenario].
     */
    private suspend fun seedEquityLongShort() {
        val existing = positionRepository.findDistinctBookIds()
        if (existing.isEmpty()) {
            val trades = EquityLongShortScenario.TRADES
            log.info("Seeding equity-ls scenario: {} trades, {} distinct instruments",
                trades.size, trades.map { it.instrumentId.value }.distinct().size)
            for (trade in trades) {
                tradeBookingService.handle(trade)
            }
        } else {
            log.info("Seed data already present ({} books), skipping equity-ls trades", existing.size)
        }

        if (executionCostRepo != null) {
            val existingCosts = executionCostRepo.findByBookId("equity-growth")
            if (existingCosts.none { it.orderId.startsWith("seed-exec-") }) {
                log.info("Seeding {} execution cost analyses", EXECUTION_COSTS.size)
                for (cost in EXECUTION_COSTS) {
                    executionCostRepo.save(cost)
                }
            }
        }

        log.info("equity-ls scenario seeding complete")
    }

    suspend fun seed() {
        val existing = positionRepository.findDistinctBookIds()
        if (existing.isEmpty()) {
            log.info("Seeding dev data: {} trades across {} books", TRADES.size, TRADES.map { it.bookId }.distinct().size)

            for (trade in TRADES) {
                tradeBookingService.handle(trade)
            }

            // Update positions with realistic market prices (trades only set averageCost)
            for ((key, marketPrice) in MARKET_PRICES) {
                val position = positionRepository.findByKey(key.first, key.second) ?: continue
                positionRepository.save(position.markToMarket(marketPrice))
            }

            if (limitDefinitionRepo != null) {
                val existingLimits = limitDefinitionRepo.findAll()
                if (existingLimits.none { it.id.startsWith("seed-lim-") }) {
                    log.info("Seeding {} limit definitions", LIMIT_DEFINITIONS.size)
                    for (limit in LIMIT_DEFINITIONS) {
                        limitDefinitionRepo.save(limit)
                    }
                }
            }

            // Phase 1 Gap 3 — 252-day tape trades, bulk-inserted to fill the blotter
            // and counterparty exposure history without paying the per-trade booking
            // cost. Skips position table updates: the live position set comes from
            // CORE_TRADES above.
            if (tapeTradesEnabled && tradeEventRepository != null) {
                seedTapeTrades(tradeEventRepository)
            }
        } else {
            log.info("Seed data already present ({} books), skipping trades/prices/limits", existing.size)
        }

        // Execution cost seeding runs independently — supports warm restart
        // where trades exist but execution costs were not yet seeded
        if (executionCostRepo != null) {
            val existingCosts = executionCostRepo.findByBookId("equity-growth")
            if (existingCosts.none { it.orderId.startsWith("seed-exec-") }) {
                log.info("Seeding {} execution cost analyses", EXECUTION_COSTS.size)
                for (cost in EXECUTION_COSTS) {
                    executionCostRepo.save(cost)
                }
            }
        }

        log.info("Dev data seeding complete")
    }

    private suspend fun seedTapeTrades(repo: TradeEventRepository) {
        val generator = TradeTapeGenerator(
            instrumentsByBook = INSTRUMENTS_BY_BOOK_FOR_TAPE,
            baseTradesPerBookPerDay = TradeTapeGenerator.DEFAULT_BASE_RATES,
        )
        val trades = generator.generate()
        log.info("Bulk-inserting {} tape trades across {} books", trades.size, INSTRUMENTS_BY_BOOK_FOR_TAPE.size)
        repo.bulkInsertForSeed(trades)
        log.info("Tape trade bulk insert complete")
    }

    companion object {
        private val USD = Currency.getInstance("USD")
        private val EUR = Currency.getInstance("EUR")
        private val BASE_TIME = Instant.parse("2026-02-21T14:00:00Z")

        /**
         * Per-book instrument catalogue in the shape consumed by the trade tape
         * generator and the demo replay sweeper. Built lazily from BOOK_INSTRUMENTS
         * so both Phase 1 Gap 3 (seed tape) and Gap 7 (intraday replay) operate on
         * the same instrument universe.
         */
        val INSTRUMENTS_BY_BOOK_FOR_TAPE: Map<String, List<TradeTapeGenerator.TradeInstrumentSpec>> by lazy {
            BOOK_INSTRUMENTS.mapValues { (_, specs) ->
                specs.map { spec ->
                    TradeTapeGenerator.TradeInstrumentSpec(
                        id = spec.id,
                        assetClass = spec.assetClass,
                        instrumentType = spec.instrumentType,
                        currency = spec.currency,
                        typicalPrice = spec.typicalPrice,
                        typicalQtyMin = spec.typicalQtyMin,
                        typicalQtyMax = spec.typicalQtyMax,
                        priceScale = if (spec.currency in setOf("USD", "EUR", "GBP", "CAD", "CHF", "JPY")) {
                            if (spec.assetClass == AssetClass.FX) 4 else 2
                        } else 2,
                    )
                }
            }
        }

        private fun usd(amount: String) = Money(BigDecimal(amount), USD)
        private fun eur(amount: String) = Money(BigDecimal(amount), EUR)
        private fun day(n: Long): Instant = BASE_TIME.plus(n, ChronoUnit.DAYS)

        // Book → Desk mapping (desks seeded in reference-data-service):
        //   equity-growth     → desk: equity-growth      (div: equities)
        //   tech-momentum     → desk: tech-momentum      (div: equities)
        //   emerging-markets  → desk: emerging-markets    (div: equities)
        //   fixed-income      → desk: rates-trading       (div: fixed-income-rates)
        //   multi-asset       → desk: multi-asset-strategies (div: multi-asset)
        //   macro-hedge       → desk: macro-hedge         (div: multi-asset)
        //   balanced-income   → desk: balanced-income     (div: multi-asset)
        //   derivatives-book  → desk: derivatives-trading (div: multi-asset)

        // ── Per-book instrument catalogue (reused by generator) ──────────────────
        private data class InstrumentSpec(
            val id: String,
            val assetClass: AssetClass,
            val instrumentType: String,
            val currency: String,
            val typicalPrice: String,
            val typicalQtyMin: Int,
            val typicalQtyMax: Int,
        )

        private val BOOK_INSTRUMENTS: Map<String, List<InstrumentSpec>> = mapOf(
            // ── equity-growth (24 instruments) ──
            "equity-growth" to listOf(
                InstrumentSpec("AAPL",  AssetClass.EQUITY, "CASH_EQUITY", "USD", "185.50",  500, 3000),
                InstrumentSpec("GOOGL", AssetClass.EQUITY, "CASH_EQUITY", "USD", "175.20",  300, 2000),
                InstrumentSpec("MSFT",  AssetClass.EQUITY, "CASH_EQUITY", "USD", "420.00",  200, 1500),
                InstrumentSpec("AMZN",  AssetClass.EQUITY, "CASH_EQUITY", "USD", "205.75",  400, 2500),
                InstrumentSpec("META",  AssetClass.EQUITY, "CASH_EQUITY", "USD", "505.00",  200, 1200),
                InstrumentSpec("JPM",   AssetClass.EQUITY, "CASH_EQUITY", "USD", "209.00",  300, 1500),
                InstrumentSpec("BAC",   AssetClass.EQUITY, "CASH_EQUITY", "USD", "39.80",   500, 3000),
                InstrumentSpec("DIS",   AssetClass.EQUITY, "CASH_EQUITY", "USD", "112.50",  200, 1500),
                InstrumentSpec("KO",    AssetClass.EQUITY, "CASH_EQUITY", "USD", "62.80",   300, 2000),
                InstrumentSpec("WMT",   AssetClass.EQUITY, "CASH_EQUITY", "USD", "172.30",  150, 1000),
                InstrumentSpec("JNJ",   AssetClass.EQUITY, "CASH_EQUITY", "USD", "158.40",  150, 1000),
                InstrumentSpec("UNH",   AssetClass.EQUITY, "CASH_EQUITY", "USD", "540.60",  50,  400),
                InstrumentSpec("CRM",   AssetClass.EQUITY, "CASH_EQUITY", "USD", "305.80",  100, 800),
                InstrumentSpec("ADBE",  AssetClass.EQUITY, "CASH_EQUITY", "USD", "470.50",  80,  600),
                InstrumentSpec("ORCL",  AssetClass.EQUITY, "CASH_EQUITY", "USD", "176.50",  200, 1200),
                InstrumentSpec("PFE",   AssetClass.EQUITY, "CASH_EQUITY", "USD", "28.60",   500, 4000),
                InstrumentSpec("XOM",   AssetClass.EQUITY, "CASH_EQUITY", "USD", "116.80",  200, 1200),
                InstrumentSpec("CVX",   AssetClass.EQUITY, "CASH_EQUITY", "USD", "163.20",  150, 1000),
                InstrumentSpec("AAPL-C-200-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "USD", "8.50", 50, 400),
                InstrumentSpec("AAPL-P-180-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "USD", "6.20", 50, 400),
                InstrumentSpec("MSFT-C-450-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "USD", "10.80", 30, 250),
                InstrumentSpec("MSFT-P-400-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "USD", "9.50",  30, 250),
                InstrumentSpec("SPX-PUT-4500", AssetClass.DERIVATIVE, "EQUITY_OPTION", "USD", "32.50", 30, 200),
                InstrumentSpec("SPX-SEP26",    AssetClass.DERIVATIVE, "EQUITY_FUTURE", "USD", "5020.00", 5, 40),
            ),
            // ── tech-momentum (22 instruments) ──
            "tech-momentum" to listOf(
                InstrumentSpec("NVDA",  AssetClass.EQUITY, "CASH_EQUITY", "USD", "885.00",  100,  800),
                InstrumentSpec("META",  AssetClass.EQUITY, "CASH_EQUITY", "USD", "502.30",  200, 1500),
                InstrumentSpec("MSFT",  AssetClass.EQUITY, "CASH_EQUITY", "USD", "421.50",  150, 1200),
                InstrumentSpec("GOOGL", AssetClass.EQUITY, "CASH_EQUITY", "USD", "176.80",  300, 2000),
                InstrumentSpec("AMZN",  AssetClass.EQUITY, "CASH_EQUITY", "USD", "206.00",  300, 1800),
                InstrumentSpec("TSLA",  AssetClass.EQUITY, "CASH_EQUITY", "USD", "249.50",  200, 1200),
                InstrumentSpec("AMD",   AssetClass.EQUITY, "CASH_EQUITY", "USD", "164.20",  200, 1500),
                InstrumentSpec("INTC",  AssetClass.EQUITY, "CASH_EQUITY", "USD", "24.10",   500, 4000),
                InstrumentSpec("CRM",   AssetClass.EQUITY, "CASH_EQUITY", "USD", "305.80",  100, 800),
                InstrumentSpec("ADBE",  AssetClass.EQUITY, "CASH_EQUITY", "USD", "470.50",  80,  600),
                InstrumentSpec("ORCL",  AssetClass.EQUITY, "CASH_EQUITY", "USD", "176.50",  200, 1200),
                InstrumentSpec("GS",    AssetClass.EQUITY, "CASH_EQUITY", "USD", "495.30",  50,  400),
                InstrumentSpec("NVDA-C-950-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "USD", "28.50", 50, 300),
                InstrumentSpec("NVDA-P-800-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "USD", "35.20", 50, 300),
                InstrumentSpec("TSLA-C-280-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "USD", "12.40", 50, 300),
                InstrumentSpec("TSLA-P-220-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "USD", "13.20", 50, 300),
                InstrumentSpec("MSFT-C-450-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "USD", "10.80", 30, 250),
                InstrumentSpec("MSFT-P-400-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "USD", "9.50",  30, 250),
                InstrumentSpec("NDX-SEP26",     AssetClass.DERIVATIVE, "EQUITY_FUTURE", "USD", "18100.00", 5, 40),
                InstrumentSpec("SPX-CALL-5200", AssetClass.DERIVATIVE, "EQUITY_OPTION", "USD", "22.30", 30, 200),
                InstrumentSpec("SPX-PUT-4800",  AssetClass.DERIVATIVE, "EQUITY_OPTION", "USD", "55.40", 30, 200),
                InstrumentSpec("VIX-PUT-15",    AssetClass.DERIVATIVE, "EQUITY_OPTION", "USD", "3.75",  100, 800),
            ),
            // ── emerging-markets (20 instruments) ──
            "emerging-markets" to listOf(
                InstrumentSpec("BABA",   AssetClass.EQUITY,       "CASH_EQUITY",    "USD", "83.20",   500, 4000),
                InstrumentSpec("TSLA",   AssetClass.EQUITY,       "CASH_EQUITY",    "USD", "250.10",  200, 1500),
                InstrumentSpec("META",   AssetClass.EQUITY,       "CASH_EQUITY",    "USD", "503.00",  200, 1200),
                InstrumentSpec("JPM",    AssetClass.EQUITY,       "CASH_EQUITY",    "USD", "209.00",  300, 1500),
                InstrumentSpec("GS",     AssetClass.EQUITY,       "CASH_EQUITY",    "USD", "495.30",  50,  400),
                InstrumentSpec("EURUSD", AssetClass.FX,           "FX_SPOT",        "USD", "1.0850",  500000, 3000000),
                InstrumentSpec("GBPUSD", AssetClass.FX,           "FX_SPOT",        "USD", "1.2580",  300000, 2000000),
                InstrumentSpec("USDJPY", AssetClass.FX,           "FX_SPOT",        "USD", "150.20",  500000, 3000000),
                InstrumentSpec("AUDUSD", AssetClass.FX,           "FX_SPOT",        "USD", "0.6580",  300000, 2000000),
                InstrumentSpec("USDCAD", AssetClass.FX,           "FX_SPOT",        "USD", "1.3620",  300000, 2000000),
                InstrumentSpec("NZDUSD", AssetClass.FX,           "FX_SPOT",        "USD", "0.6140",  200000, 1500000),
                InstrumentSpec("GBPUSD-3M",           AssetClass.FX, "FX_FORWARD", "USD", "1.2800",  200000, 1500000),
                InstrumentSpec("EURUSD-6M",           AssetClass.FX, "FX_FORWARD", "USD", "1.0880",  200000, 1500000),
                InstrumentSpec("USDJPY-3M",           AssetClass.FX, "FX_FORWARD", "USD", "149.50",  200000, 1500000),
                InstrumentSpec("EURUSD-P-1.08-SEP26", AssetClass.DERIVATIVE, "FX_OPTION", "USD", "2.15", 500, 3000),
                InstrumentSpec("USDJPY-C-155-SEP26",  AssetClass.DERIVATIVE, "FX_OPTION", "USD", "2.80", 500, 3000),
                InstrumentSpec("DE10Y",  AssetClass.FIXED_INCOME, "GOVERNMENT_BOND", "EUR", "97.80",  1000, 5000),
                InstrumentSpec("JP10Y",  AssetClass.FIXED_INCOME, "GOVERNMENT_BOND", "JPY", "99.40",  1000, 5000),
                InstrumentSpec("GC",     AssetClass.COMMODITY,    "COMMODITY_FUTURE","USD", "2045.60", 10,   60),
                InstrumentSpec("WTI-AUG26", AssetClass.COMMODITY, "COMMODITY_FUTURE","USD", "75.50",   30,  200),
            ),
            // ── fixed-income (22 instruments) ──
            "fixed-income" to listOf(
                InstrumentSpec("US2Y",   AssetClass.FIXED_INCOME, "GOVERNMENT_BOND",      "USD", "99.25",  5000, 20000),
                InstrumentSpec("US5Y",   AssetClass.FIXED_INCOME, "GOVERNMENT_BOND",      "USD", "98.80",  3000, 15000),
                InstrumentSpec("US10Y",  AssetClass.FIXED_INCOME, "GOVERNMENT_BOND",      "USD", "96.50",  3000, 15000),
                InstrumentSpec("US30Y",  AssetClass.FIXED_INCOME, "GOVERNMENT_BOND",      "USD", "92.10",  2000, 10000),
                InstrumentSpec("DE2Y",   AssetClass.FIXED_INCOME, "GOVERNMENT_BOND",      "EUR", "99.75",  2000, 10000),
                InstrumentSpec("DE10Y",  AssetClass.FIXED_INCOME, "GOVERNMENT_BOND",      "EUR", "97.80",  1000, 5000),
                InstrumentSpec("UK10Y",  AssetClass.FIXED_INCOME, "GOVERNMENT_BOND",      "GBP", "96.30",  1000, 5000),
                InstrumentSpec("JP10Y",  AssetClass.FIXED_INCOME, "GOVERNMENT_BOND",      "JPY", "99.40",  1000, 5000),
                InstrumentSpec("JPM-BOND-2031",  AssetClass.FIXED_INCOME, "CORPORATE_BOND",      "USD", "101.50", 2000, 10000),
                InstrumentSpec("AAPL-BOND-2030", AssetClass.FIXED_INCOME, "CORPORATE_BOND",      "USD", "101.50", 2000, 10000),
                InstrumentSpec("GS-BOND-2029",   AssetClass.FIXED_INCOME, "CORPORATE_BOND",      "USD", "103.10", 2000, 10000),
                InstrumentSpec("MSFT-BOND-2032", AssetClass.FIXED_INCOME, "CORPORATE_BOND",      "USD", "100.20", 2000, 10000),
                InstrumentSpec("USD-SOFR-5Y",    AssetClass.FIXED_INCOME, "INTEREST_RATE_SWAP",  "USD", "99.80",  1000, 5000),
                InstrumentSpec("USD-SOFR-10Y",   AssetClass.FIXED_INCOME, "INTEREST_RATE_SWAP",  "USD", "99.70",  1000, 5000),
                InstrumentSpec("EUR-ESTR-5Y",    AssetClass.FIXED_INCOME, "INTEREST_RATE_SWAP",  "EUR", "99.75",  1000, 5000),
                InstrumentSpec("JPM",    AssetClass.EQUITY, "CASH_EQUITY", "USD", "209.00",  200, 1000),
                InstrumentSpec("BAC",    AssetClass.EQUITY, "CASH_EQUITY", "USD", "39.80",   300, 2000),
                InstrumentSpec("GS",     AssetClass.EQUITY, "CASH_EQUITY", "USD", "495.30",  50,  300),
                InstrumentSpec("MS",     AssetClass.EQUITY, "CASH_EQUITY", "USD", "101.40",  200, 1000),
                InstrumentSpec("EURUSD", AssetClass.FX,     "FX_SPOT",     "USD", "1.0842",  300000, 1500000),
                InstrumentSpec("GBPUSD", AssetClass.FX,     "FX_SPOT",     "USD", "1.2600",  200000, 1000000),
                InstrumentSpec("EURGBP", AssetClass.FX,     "FX_SPOT",     "USD", "0.8610",  200000, 1000000),
            ),
            // ── multi-asset (28 instruments) ──
            "multi-asset" to listOf(
                InstrumentSpec("AAPL",        AssetClass.EQUITY,       "CASH_EQUITY",      "USD", "186.00",  200, 1500),
                InstrumentSpec("MSFT",        AssetClass.EQUITY,       "CASH_EQUITY",      "USD", "418.50",  200, 1500),
                InstrumentSpec("NVDA",        AssetClass.EQUITY,       "CASH_EQUITY",      "USD", "888.00",   50, 400),
                InstrumentSpec("TSLA",        AssetClass.EQUITY,       "CASH_EQUITY",      "USD", "249.00",  100, 800),
                InstrumentSpec("JPM",         AssetClass.EQUITY,       "CASH_EQUITY",      "USD", "209.00",  200, 1000),
                InstrumentSpec("GS",          AssetClass.EQUITY,       "CASH_EQUITY",      "USD", "495.30",   50, 300),
                InstrumentSpec("UNH",         AssetClass.EQUITY,       "CASH_EQUITY",      "USD", "540.60",   30, 200),
                InstrumentSpec("XOM",         AssetClass.EQUITY,       "CASH_EQUITY",      "USD", "116.80",  150, 1000),
                InstrumentSpec("US10Y",       AssetClass.FIXED_INCOME, "GOVERNMENT_BOND",  "USD", "96.75",   2000, 10000),
                InstrumentSpec("US30Y",       AssetClass.FIXED_INCOME, "GOVERNMENT_BOND",  "USD", "92.30",   1000, 5000),
                InstrumentSpec("DE10Y",       AssetClass.FIXED_INCOME, "GOVERNMENT_BOND",  "EUR", "97.80",   1000, 5000),
                InstrumentSpec("JPM-BOND-2031",AssetClass.FIXED_INCOME,"CORPORATE_BOND",   "USD","101.50",   1000, 5000),
                InstrumentSpec("EURUSD",      AssetClass.FX,           "FX_SPOT",          "USD", "1.0842",  500000, 2000000),
                InstrumentSpec("GBPUSD",      AssetClass.FX,           "FX_SPOT",          "USD", "1.2600",  300000, 1500000),
                InstrumentSpec("AUDUSD",      AssetClass.FX,           "FX_SPOT",          "USD", "0.6580",  200000, 1000000),
                InstrumentSpec("USDCHF",      AssetClass.FX,           "FX_SPOT",          "USD", "0.8860",  200000, 1000000),
                InstrumentSpec("GC",          AssetClass.COMMODITY,    "COMMODITY_FUTURE",  "USD","2045.60",  10,   80),
                InstrumentSpec("CL",          AssetClass.COMMODITY,    "COMMODITY_FUTURE",  "USD", "76.80",   20, 150),
                InstrumentSpec("NG",          AssetClass.COMMODITY,    "COMMODITY_FUTURE",  "USD", "3.10",    50, 400),
                InstrumentSpec("HG",          AssetClass.COMMODITY,    "COMMODITY_FUTURE",  "USD", "4.28",    30, 200),
                InstrumentSpec("SPX-PUT-4500",AssetClass.DERIVATIVE,   "EQUITY_OPTION",     "USD", "32.50",   50,  300),
                InstrumentSpec("SPX-CALL-5000",AssetClass.DERIVATIVE,  "EQUITY_OPTION",     "USD", "41.50",   30, 200),
                InstrumentSpec("AAPL-C-200-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION","USD", "8.50",   30, 200),
                InstrumentSpec("MSFT-C-450-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION","USD", "10.80",  20, 150),
                InstrumentSpec("USD-SOFR-5Y", AssetClass.FIXED_INCOME, "INTEREST_RATE_SWAP","USD", "99.80",  500, 3000),
                InstrumentSpec("SPX-SEP26",   AssetClass.DERIVATIVE,   "EQUITY_FUTURE",     "USD","5020.00",  10,  60),
                InstrumentSpec("NDX-SEP26",   AssetClass.DERIVATIVE,   "EQUITY_FUTURE",     "USD","18100.00",  5,  30),
                InstrumentSpec("EURUSD-6M",   AssetClass.FX,           "FX_FORWARD",        "USD", "1.0880", 200000, 1000000),
            ),
            // ── macro-hedge (24 instruments) ──
            "macro-hedge" to listOf(
                InstrumentSpec("USDJPY",      AssetClass.FX,           "FX_SPOT",          "USD", "149.80",  500000, 2000000),
                InstrumentSpec("EURUSD",      AssetClass.FX,           "FX_SPOT",          "USD", "1.0850",  300000, 2000000),
                InstrumentSpec("GBPUSD",      AssetClass.FX,           "FX_SPOT",          "USD", "1.2580",  200000, 1500000),
                InstrumentSpec("USDCHF",      AssetClass.FX,           "FX_SPOT",          "USD", "0.8860",  200000, 1000000),
                InstrumentSpec("EURGBP",      AssetClass.FX,           "FX_SPOT",          "USD", "0.8610",  200000, 1000000),
                InstrumentSpec("AUDUSD",      AssetClass.FX,           "FX_SPOT",          "USD", "0.6580",  200000, 1000000),
                InstrumentSpec("USDJPY-3M",   AssetClass.FX,           "FX_FORWARD",       "USD", "149.50",  200000, 1000000),
                InstrumentSpec("EURUSD-6M",   AssetClass.FX,           "FX_FORWARD",       "USD", "1.0880",  200000, 1000000),
                InstrumentSpec("USDJPY-C-155-SEP26", AssetClass.DERIVATIVE, "FX_OPTION",   "USD", "2.80",    500, 3000),
                InstrumentSpec("GC",          AssetClass.COMMODITY,    "COMMODITY_FUTURE",  "USD","2040.00",   10,   60),
                InstrumentSpec("CL",          AssetClass.COMMODITY,    "COMMODITY_FUTURE",  "USD",  "76.80",   30,  200),
                InstrumentSpec("SI",          AssetClass.COMMODITY,    "COMMODITY_FUTURE",  "USD",  "23.10",   20,  150),
                InstrumentSpec("NG",          AssetClass.COMMODITY,    "COMMODITY_FUTURE",  "USD",  "3.10",    50,  400),
                InstrumentSpec("HG",          AssetClass.COMMODITY,    "COMMODITY_FUTURE",  "USD",  "4.28",    30,  200),
                InstrumentSpec("ZC",          AssetClass.COMMODITY,    "COMMODITY_FUTURE",  "USD",  "4.68",    30,  200),
                InstrumentSpec("GC-C-2200-DEC26",AssetClass.COMMODITY, "COMMODITY_OPTION",  "USD", "45.80",   20,  150),
                InstrumentSpec("CL-P-70-DEC26",  AssetClass.COMMODITY, "COMMODITY_OPTION",  "USD", "4.50",    30,  200),
                InstrumentSpec("US10Y",       AssetClass.FIXED_INCOME, "GOVERNMENT_BOND",   "USD",  "96.50",  1000, 5000),
                InstrumentSpec("DE10Y",       AssetClass.FIXED_INCOME, "GOVERNMENT_BOND",   "EUR",  "97.80",  1000, 5000),
                InstrumentSpec("UK10Y",       AssetClass.FIXED_INCOME, "GOVERNMENT_BOND",   "GBP",  "96.30",  1000, 5000),
                InstrumentSpec("SPX-PUT-4500",AssetClass.DERIVATIVE,   "EQUITY_OPTION",     "USD",  "31.20",   30,  200),
                InstrumentSpec("SPX-SEP26",   AssetClass.DERIVATIVE,   "EQUITY_FUTURE",     "USD","5020.00",   10,  80),
                InstrumentSpec("RTY-SEP26",   AssetClass.DERIVATIVE,   "EQUITY_FUTURE",     "USD","2120.00",   10,  60),
                InstrumentSpec("WTI-AUG26",   AssetClass.COMMODITY,    "COMMODITY_FUTURE",  "USD", "75.50",    30, 200),
            ),
            // ── balanced-income (22 instruments) ──
            "balanced-income" to listOf(
                InstrumentSpec("US10Y",  AssetClass.FIXED_INCOME, "GOVERNMENT_BOND",      "USD", "96.60",  2000, 10000),
                InstrumentSpec("US30Y",  AssetClass.FIXED_INCOME, "GOVERNMENT_BOND",      "USD", "92.30",  1000,  8000),
                InstrumentSpec("US5Y",   AssetClass.FIXED_INCOME, "GOVERNMENT_BOND",      "USD", "98.80",  2000, 10000),
                InstrumentSpec("DE10Y",  AssetClass.FIXED_INCOME, "GOVERNMENT_BOND",      "EUR", "97.90",  1000,  5000),
                InstrumentSpec("UK10Y",  AssetClass.FIXED_INCOME, "GOVERNMENT_BOND",      "GBP", "96.30",  1000,  5000),
                InstrumentSpec("JPM-BOND-2031",  AssetClass.FIXED_INCOME, "CORPORATE_BOND",      "USD","101.50", 1000, 5000),
                InstrumentSpec("AAPL-BOND-2030", AssetClass.FIXED_INCOME, "CORPORATE_BOND",      "USD","101.50", 1000, 5000),
                InstrumentSpec("GS-BOND-2029",   AssetClass.FIXED_INCOME, "CORPORATE_BOND",      "USD","103.10", 1000, 5000),
                InstrumentSpec("MSFT-BOND-2032", AssetClass.FIXED_INCOME, "CORPORATE_BOND",      "USD","100.20", 1000, 5000),
                InstrumentSpec("USD-SOFR-5Y",    AssetClass.FIXED_INCOME, "INTEREST_RATE_SWAP",  "USD", "99.80", 500, 3000),
                InstrumentSpec("USD-SOFR-10Y",   AssetClass.FIXED_INCOME, "INTEREST_RATE_SWAP",  "USD", "99.70", 500, 3000),
                InstrumentSpec("EUR-ESTR-5Y",    AssetClass.FIXED_INCOME, "INTEREST_RATE_SWAP",  "EUR", "99.75", 500, 3000),
                InstrumentSpec("JPM",    AssetClass.EQUITY, "CASH_EQUITY", "USD", "208.40",  200, 1500),
                InstrumentSpec("BAC",    AssetClass.EQUITY, "CASH_EQUITY", "USD", "39.80",   300, 2000),
                InstrumentSpec("KO",     AssetClass.EQUITY, "CASH_EQUITY", "USD", "62.80",   200, 1500),
                InstrumentSpec("JNJ",    AssetClass.EQUITY, "CASH_EQUITY", "USD", "158.40",  100, 800),
                InstrumentSpec("PFE",    AssetClass.EQUITY, "CASH_EQUITY", "USD", "28.60",   300, 2500),
                InstrumentSpec("UNH",    AssetClass.EQUITY, "CASH_EQUITY", "USD", "540.60",  30,  200),
                InstrumentSpec("XOM",    AssetClass.EQUITY, "CASH_EQUITY", "USD", "116.80",  150, 1000),
                InstrumentSpec("AAPL-C-200-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "USD", "8.50", 30, 200),
                InstrumentSpec("EURUSD", AssetClass.FX,     "FX_SPOT",     "USD", "1.0860",  300000, 1500000),
                InstrumentSpec("AAPL",   AssetClass.EQUITY, "CASH_EQUITY", "USD", "187.20",  200, 1200),
            ),
            // ── derivatives-book (28 instruments) ──
            "derivatives-book" to listOf(
                InstrumentSpec("SPX-CALL-5000",       AssetClass.DERIVATIVE, "EQUITY_OPTION", "USD", "41.50",  100,  600),
                InstrumentSpec("SPX-CALL-5200",       AssetClass.DERIVATIVE, "EQUITY_OPTION", "USD", "22.30",   50, 400),
                InstrumentSpec("SPX-PUT-4500",        AssetClass.DERIVATIVE, "EQUITY_OPTION", "USD", "33.00",   80,  500),
                InstrumentSpec("SPX-PUT-4800",        AssetClass.DERIVATIVE, "EQUITY_OPTION", "USD", "55.40",   50, 400),
                InstrumentSpec("AAPL-C-200-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "USD", "8.50",   50, 300),
                InstrumentSpec("AAPL-P-180-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "USD", "6.20",   50, 300),
                InstrumentSpec("NVDA-C-950-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "USD", "28.50",  50, 300),
                InstrumentSpec("NVDA-P-800-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "USD", "35.20",  50, 300),
                InstrumentSpec("MSFT-C-450-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "USD", "10.80",  30, 250),
                InstrumentSpec("MSFT-P-400-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "USD", "9.50",   30, 250),
                InstrumentSpec("TSLA-C-280-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "USD", "12.40",  50, 300),
                InstrumentSpec("TSLA-P-220-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "USD", "13.20",  50, 300),
                InstrumentSpec("GOOGL-C-190-20260620",AssetClass.DERIVATIVE, "EQUITY_OPTION", "USD", "6.80",   50, 300),
                InstrumentSpec("GOOGL-P-160-20260620",AssetClass.DERIVATIVE, "EQUITY_OPTION", "USD", "6.50",   50, 300),
                InstrumentSpec("AMZN-C-220-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "USD", "8.60",   50, 300),
                InstrumentSpec("AMZN-P-190-20260620", AssetClass.DERIVATIVE, "EQUITY_OPTION", "USD", "9.20",   50, 300),
                InstrumentSpec("GC-C-2200-DEC26",     AssetClass.COMMODITY,  "COMMODITY_OPTION","USD","45.80",  20, 150),
                InstrumentSpec("CL-P-70-DEC26",       AssetClass.COMMODITY,  "COMMODITY_OPTION","USD","4.50",   30, 200),
                InstrumentSpec("EURUSD-P-1.08-SEP26", AssetClass.DERIVATIVE, "FX_OPTION",     "USD", "2.15",  300, 2000),
                InstrumentSpec("USDJPY-C-155-SEP26",  AssetClass.DERIVATIVE, "FX_OPTION",     "USD", "2.80",  300, 2000),
                InstrumentSpec("VIX-PUT-15",          AssetClass.DERIVATIVE, "EQUITY_OPTION", "USD", "3.75",  200, 1500),
                InstrumentSpec("SPX-SEP26",           AssetClass.DERIVATIVE, "EQUITY_FUTURE", "USD","5020.00",  20, 150),
                InstrumentSpec("NDX-SEP26",           AssetClass.DERIVATIVE, "EQUITY_FUTURE", "USD","18100.00", 5,  30),
                InstrumentSpec("RTY-SEP26",           AssetClass.DERIVATIVE, "EQUITY_FUTURE", "USD","2120.00",  10,  60),
                InstrumentSpec("NVDA",                AssetClass.EQUITY,     "CASH_EQUITY",   "USD","888.00",  100,  600),
                InstrumentSpec("TSLA",                AssetClass.EQUITY,     "CASH_EQUITY",   "USD","249.50",  200, 1200),
                InstrumentSpec("AAPL",                AssetClass.EQUITY,     "CASH_EQUITY",   "USD","186.00",  150, 1000),
                InstrumentSpec("MSFT",                AssetClass.EQUITY,     "CASH_EQUITY",   "USD","420.00",  100,  800),
            ),
        )

        // Target count of generated trades per book
        private val GENERATED_COUNT: Map<String, Int> = mapOf(
            "equity-growth"    to 150,
            "tech-momentum"    to 120,
            "emerging-markets" to 100,
            "fixed-income"     to 70,
            "multi-asset"      to 140,
            "macro-hedge"      to 120,
            "balanced-income"  to 80,
            "derivatives-book" to 150,
        )

        private fun buildGeneratedTrades(): List<BookTradeCommand> {
            // LCG: Knuth/Newlib parameters — deterministic across all JVM versions
            var lcgState = 0x5DEECE66DL
            fun lcgNext(): Long {
                lcgState = lcgState * 6364136223846793005L + 1442695040888963407L
                return lcgState
            }
            fun nextInt(bound: Int): Int = ((lcgNext() ushr 17) % bound).toInt().let {
                if (it < 0) it + bound else it
            }
            fun nextBoolean(trueProbability: Int): Boolean = nextInt(100) < trueProbability

            // Intraday bucket → seconds offset from midnight UTC
            // 09:30-10:30 ET = 14:30-15:30 UTC → 52200..56400 s
            // 11:00-13:00 ET = 16:00-18:00 UTC → 57600..64800 s
            // 13:00-15:00 ET = 18:00-20:00 UTC → 64800..72000 s
            // 15:00-16:00 ET = 20:00-21:00 UTC → 72000..75600 s
            // European hours  08:00-11:00 UTC   → 28800..39600 s
            fun intradaySeconds(isEuropean: Boolean): Long {
                return if (isEuropean) {
                    // 08:00-11:00 UTC (10800 s window)
                    28800L + nextInt(10800)
                } else {
                    val bucket = nextInt(100)
                    when {
                        bucket < 35 -> 52200L + nextInt(3600)   // 14:30-15:30 UTC
                        bucket < 50 -> 57600L + nextInt(7200)   // 16:00-18:00 UTC
                        bucket < 65 -> 64800L + nextInt(7200)   // 18:00-20:00 UTC
                        else        -> 72000L + nextInt(3600)   // 20:00-21:00 UTC
                    }
                }
            }

            // Day index 0..19 maps to day offset -19..0 from BASE_TIME
            fun tradedAt(dayIdx: Int, isEuropean: Boolean = false): Instant {
                val dayOffset = (dayIdx - 19).toLong()
                // BASE_TIME is 14:00 UTC; strip to start of that day and add intraday seconds
                val dayStart = BASE_TIME.plus(dayOffset, ChronoUnit.DAYS)
                    .truncatedTo(ChronoUnit.DAYS)
                return dayStart.plusSeconds(intradaySeconds(isEuropean))
            }

            val result = mutableListOf<BookTradeCommand>()

            // Track sequence numbers per (book, instrument) for ID generation
            val seqCounters = mutableMapOf<Pair<String, String>, Int>()
            fun nextSeq(book: String, instr: String): Int {
                val key = book to instr
                val seq = (seqCounters[key] ?: 0) + 1
                seqCounters[key] = seq
                return seq
            }

            val fxAndMacroBooks = setOf("macro-hedge", "emerging-markets", "multi-asset")

            for ((bookId, count) in GENERATED_COUNT) {
                val instruments = BOOK_INSTRUMENTS[bookId] ?: continue
                val isFxOrMacro = bookId in fxAndMacroBooks

                var i = 0
                while (i < count) {
                    val instrSpec = instruments[nextInt(instruments.size)]
                    val dayIdx = nextInt(20)
                    val isEuropean = isFxOrMacro && nextBoolean(30)
                    val at = tradedAt(dayIdx, isEuropean)

                    val isBuy = nextBoolean(70)
                    val side = if (isBuy) Side.BUY else Side.SELL
                    val qtyRange = instrSpec.typicalQtyMax - instrSpec.typicalQtyMin
                    val qty = BigDecimal(instrSpec.typicalQtyMin + nextInt(qtyRange + 1))
                    val price = if (instrSpec.currency == "EUR") eur(instrSpec.typicalPrice) else usd(instrSpec.typicalPrice)
                    val seq = nextSeq(bookId, instrSpec.id)
                    val bookAbbrev = bookId.replace("-", "").take(2)
                    val instrAbbrev = instrSpec.id.lowercase().replace("-", "").take(10)
                    val tradeId = "seed-gen-$bookAbbrev-$instrAbbrev-${seq.toString().padStart(3, '0')}"

                    result += BookTradeCommand(
                        tradeId = TradeId(tradeId),
                        bookId = BookId(bookId),
                        instrumentId = InstrumentId(instrSpec.id),
                        assetClass = instrSpec.assetClass,
                        side = side,
                        quantity = qty,
                        price = price,
                        tradedAt = at,
                        instrumentType = instrSpec.instrumentType,
                    )
                    i++
                }
            }

            // ── Amend/cancel triplets (~15, ~5% of ~300 generated) ────────────
            // Each triplet: original + cancel (opposite side, 2 min later) + amend (same side, 3 min later, ±5% qty)
            data class TripletSpec(
                val bookId: String,
                val instrId: String,
                val assetClass: AssetClass,
                val instrType: String,
                val currency: String,
                val priceStr: String,
                val qty: Int,
                val side: Side,
                val baseTime: Instant,
            )

            val tripletSpecs = listOf(
                TripletSpec("equity-growth",    "AAPL",  AssetClass.EQUITY, "CASH_EQUITY", "USD", "185.50",  1000, Side.BUY,  BASE_TIME.plus(-18, ChronoUnit.DAYS).plusSeconds(53000)),
                TripletSpec("tech-momentum",    "NVDA",  AssetClass.EQUITY, "CASH_EQUITY", "USD", "885.00",   200, Side.BUY,  BASE_TIME.plus(-15, ChronoUnit.DAYS).plusSeconds(55000)),
                TripletSpec("emerging-markets", "BABA",  AssetClass.EQUITY, "CASH_EQUITY", "USD",  "83.20",  2000, Side.BUY,  BASE_TIME.plus(-12, ChronoUnit.DAYS).plusSeconds(60000)),
                TripletSpec("fixed-income",     "US10Y", AssetClass.FIXED_INCOME, "GOVERNMENT_BOND", "USD", "96.50", 5000, Side.BUY, BASE_TIME.plus(-10, ChronoUnit.DAYS).plusSeconds(57600)),
                TripletSpec("multi-asset",      "GC",    AssetClass.COMMODITY, "COMMODITY_FUTURE", "USD", "2045.60", 20, Side.BUY, BASE_TIME.plus(-8, ChronoUnit.DAYS).plusSeconds(63000)),
                TripletSpec("macro-hedge",      "CL",    AssetClass.COMMODITY, "COMMODITY_FUTURE", "USD",  "76.80",  50, Side.SELL, BASE_TIME.plus(-6, ChronoUnit.DAYS).plusSeconds(64800)),
                TripletSpec("balanced-income",  "JPM",   AssetClass.EQUITY, "CASH_EQUITY", "USD", "208.40",  300, Side.BUY,  BASE_TIME.plus(-14, ChronoUnit.DAYS).plusSeconds(54000)),
                TripletSpec("derivatives-book", "TSLA",  AssetClass.EQUITY, "CASH_EQUITY", "USD", "249.50",  400, Side.BUY,  BASE_TIME.plus(-11, ChronoUnit.DAYS).plusSeconds(59400)),
                TripletSpec("equity-growth",    "MSFT",  AssetClass.EQUITY, "CASH_EQUITY", "USD", "420.00",  500, Side.SELL, BASE_TIME.plus(-5, ChronoUnit.DAYS).plusSeconds(70000)),
                TripletSpec("tech-momentum",    "META",  AssetClass.EQUITY, "CASH_EQUITY", "USD", "502.30",  300, Side.BUY,  BASE_TIME.plus(-3, ChronoUnit.DAYS).plusSeconds(52500)),
                TripletSpec("macro-hedge",      "GC",    AssetClass.COMMODITY, "COMMODITY_FUTURE", "USD", "2040.00", 10, Side.BUY, BASE_TIME.plus(-16, ChronoUnit.DAYS).plusSeconds(58000)),
                TripletSpec("multi-asset",      "AAPL",  AssetClass.EQUITY, "CASH_EQUITY", "USD", "186.00",  250, Side.BUY,  BASE_TIME.plus(-9, ChronoUnit.DAYS).plusSeconds(65000)),
                TripletSpec("balanced-income",  "US30Y", AssetClass.FIXED_INCOME, "GOVERNMENT_BOND", "USD", "92.30", 3000, Side.BUY, BASE_TIME.plus(-7, ChronoUnit.DAYS).plusSeconds(56400)),
                TripletSpec("derivatives-book", "SPX-CALL-5000", AssetClass.DERIVATIVE, "EQUITY_OPTION", "USD", "41.50", 100, Side.BUY, BASE_TIME.plus(-4, ChronoUnit.DAYS).plusSeconds(53800)),
                TripletSpec("emerging-markets", "GBPUSD", AssetClass.FX, "FX_SPOT", "USD", "1.2580", 500000, Side.BUY, BASE_TIME.plus(-13, ChronoUnit.DAYS).plusSeconds(34200)),
            )

            tripletSpecs.forEachIndexed { idx, spec ->
                val n = idx + 1
                val bookAbbrev = spec.bookId.replace("-", "").take(2)
                val instrAbbrev = spec.instrId.lowercase().replace("-", "").take(10)
                val baseId = "seed-gen-ac-$bookAbbrev-$instrAbbrev-${n.toString().padStart(2, '0')}"
                val price = if (spec.currency == "EUR") eur(spec.priceStr) else usd(spec.priceStr)
                val amendQty = BigDecimal((spec.qty * 105 / 100))

                result += BookTradeCommand(
                    tradeId = TradeId(baseId),
                    bookId = BookId(spec.bookId),
                    instrumentId = InstrumentId(spec.instrId),
                    assetClass = spec.assetClass,
                    side = spec.side,
                    quantity = BigDecimal(spec.qty),
                    price = price,
                    tradedAt = spec.baseTime,
                    instrumentType = spec.instrType,
                )
                val cancelSide = if (spec.side == Side.BUY) Side.SELL else Side.BUY
                result += BookTradeCommand(
                    tradeId = TradeId("$baseId-cancel"),
                    bookId = BookId(spec.bookId),
                    instrumentId = InstrumentId(spec.instrId),
                    assetClass = spec.assetClass,
                    side = cancelSide,
                    quantity = BigDecimal(spec.qty),
                    price = price,
                    tradedAt = spec.baseTime.plusSeconds(120),
                    instrumentType = spec.instrType,
                )
                result += BookTradeCommand(
                    tradeId = TradeId("$baseId-amend"),
                    bookId = BookId(spec.bookId),
                    instrumentId = InstrumentId(spec.instrId),
                    assetClass = spec.assetClass,
                    side = spec.side,
                    quantity = amendQty,
                    price = price,
                    tradedAt = spec.baseTime.plusSeconds(180),
                    instrumentType = spec.instrType,
                )
            }

            // ── Day-trade round trips (2) ──────────────────────────────────────
            // Trade 1: equity-growth AAPL — buy morning, sell afternoon
            val dayTradeDay = BASE_TIME.plus(-2, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.DAYS)
            result += BookTradeCommand(
                tradeId = TradeId("seed-gen-dt-aapl-morn"),
                bookId = BookId("equity-growth"),
                instrumentId = InstrumentId("AAPL"),
                assetClass = AssetClass.EQUITY,
                side = Side.BUY,
                quantity = BigDecimal("2000"),
                price = usd("185.50"),
                tradedAt = dayTradeDay.plusSeconds(53400),   // 14:50 UTC = 09:50 ET
                instrumentType = "CASH_EQUITY",
            )
            result += BookTradeCommand(
                tradeId = TradeId("seed-gen-dt-aapl-aftn"),
                bookId = BookId("equity-growth"),
                instrumentId = InstrumentId("AAPL"),
                assetClass = AssetClass.EQUITY,
                side = Side.SELL,
                quantity = BigDecimal("2000"),
                price = usd("186.80"),
                tradedAt = dayTradeDay.plusSeconds(72600),   // 20:10 UTC = 15:10 ET
                instrumentType = "CASH_EQUITY",
            )
            // Trade 2: tech-momentum NVDA — buy morning, sell afternoon
            result += BookTradeCommand(
                tradeId = TradeId("seed-gen-dt-nvda-morn"),
                bookId = BookId("tech-momentum"),
                instrumentId = InstrumentId("NVDA"),
                assetClass = AssetClass.EQUITY,
                side = Side.BUY,
                quantity = BigDecimal("300"),
                price = usd("885.00"),
                tradedAt = dayTradeDay.plusSeconds(54600),   // 15:10 UTC = 10:10 ET
                instrumentType = "CASH_EQUITY",
            )
            result += BookTradeCommand(
                tradeId = TradeId("seed-gen-dt-nvda-aftn"),
                bookId = BookId("tech-momentum"),
                instrumentId = InstrumentId("NVDA"),
                assetClass = AssetClass.EQUITY,
                side = Side.SELL,
                quantity = BigDecimal("300"),
                price = usd("888.50"),
                tradedAt = dayTradeDay.plusSeconds(73200),   // 20:20 UTC = 15:20 ET
                instrumentType = "CASH_EQUITY",
            )

            // ── 2s10s flattener (rates curve trade) ───────────────────────────
            val flatDay = BASE_TIME.plus(-7, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.DAYS)
                .plusSeconds(57000)  // 15:50 UTC = 10:50 ET
            result += BookTradeCommand(
                tradeId = TradeId("seed-gen-fi-us2y-flat"),
                bookId = BookId("fixed-income"),
                instrumentId = InstrumentId("US2Y"),
                assetClass = AssetClass.FIXED_INCOME,
                side = Side.BUY,
                quantity = BigDecimal("20000"),
                price = usd("99.25"),
                tradedAt = flatDay,
                instrumentType = "GOVERNMENT_BOND",
            )
            result += BookTradeCommand(
                tradeId = TradeId("seed-gen-fi-us10y-flat"),
                bookId = BookId("fixed-income"),
                instrumentId = InstrumentId("US10Y"),
                assetClass = AssetClass.FIXED_INCOME,
                side = Side.SELL,
                quantity = BigDecimal("10000"),
                price = usd("96.50"),
                tradedAt = flatDay.plusSeconds(60),
                instrumentType = "GOVERNMENT_BOND",
            )

            return result
        }

        val CORE_TRADES: List<BookTradeCommand> = listOf(
            // ── equity-growth book: 5 equity trades ──
            BookTradeCommand(
                tradeId = TradeId("seed-eq-aapl-001"),
                bookId = BookId("equity-growth"),
                instrumentId = InstrumentId("AAPL"),
                assetClass = AssetClass.EQUITY,
                side = Side.BUY,
                quantity = BigDecimal("25000"),
                price = usd("185.50"),
                tradedAt = BASE_TIME,
                instrumentType = "CASH_EQUITY",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-eq-googl-001"),
                bookId = BookId("equity-growth"),
                instrumentId = InstrumentId("GOOGL"),
                assetClass = AssetClass.EQUITY,
                side = Side.BUY,
                quantity = BigDecimal("15000"),
                price = usd("175.20"),
                tradedAt = BASE_TIME,
                instrumentType = "CASH_EQUITY",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-eq-msft-001"),
                bookId = BookId("equity-growth"),
                instrumentId = InstrumentId("MSFT"),
                assetClass = AssetClass.EQUITY,
                side = Side.BUY,
                quantity = BigDecimal("8000"),
                price = usd("420.00"),
                tradedAt = BASE_TIME,
                instrumentType = "CASH_EQUITY",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-eq-amzn-001"),
                bookId = BookId("equity-growth"),
                instrumentId = InstrumentId("AMZN"),
                assetClass = AssetClass.EQUITY,
                side = Side.BUY,
                quantity = BigDecimal("12000"),
                price = usd("205.75"),
                tradedAt = BASE_TIME,
                instrumentType = "CASH_EQUITY",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-eq-tsla-001"),
                bookId = BookId("equity-growth"),
                instrumentId = InstrumentId("TSLA"),
                assetClass = AssetClass.EQUITY,
                side = Side.BUY,
                quantity = BigDecimal("10000"),
                price = usd("248.30"),
                tradedAt = BASE_TIME,
                instrumentType = "CASH_EQUITY",
            ),

            // ── multi-asset book: 6 trades across asset classes ──
            BookTradeCommand(
                tradeId = TradeId("seed-ma-aapl-001"),
                bookId = BookId("multi-asset"),
                instrumentId = InstrumentId("AAPL"),
                assetClass = AssetClass.EQUITY,
                side = Side.BUY,
                quantity = BigDecimal("5000"),
                price = usd("186.00"),
                tradedAt = BASE_TIME,
                instrumentType = "CASH_EQUITY",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-ma-eurusd-001"),
                bookId = BookId("multi-asset"),
                instrumentId = InstrumentId("EURUSD"),
                assetClass = AssetClass.FX,
                side = Side.BUY,
                quantity = BigDecimal("10000000"),
                price = usd("1.0842"),
                tradedAt = BASE_TIME,
                instrumentType = "FX_SPOT",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-ma-us10y-001"),
                bookId = BookId("multi-asset"),
                instrumentId = InstrumentId("US10Y"),
                assetClass = AssetClass.FIXED_INCOME,
                side = Side.BUY,
                quantity = BigDecimal("50000"),
                price = usd("96.75"),
                tradedAt = BASE_TIME,
                instrumentType = "GOVERNMENT_BOND",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-ma-gc-001"),
                bookId = BookId("multi-asset"),
                instrumentId = InstrumentId("GC"),
                assetClass = AssetClass.COMMODITY,
                side = Side.BUY,
                quantity = BigDecimal("100"),
                price = usd("2045.60"),
                tradedAt = BASE_TIME,
                instrumentType = "COMMODITY_FUTURE",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-ma-spx-put-001"),
                bookId = BookId("multi-asset"),
                instrumentId = InstrumentId("SPX-PUT-4500"),
                assetClass = AssetClass.DERIVATIVE,
                side = Side.BUY,
                quantity = BigDecimal("500"),
                price = usd("32.50"),
                tradedAt = BASE_TIME,
                instrumentType = "EQUITY_OPTION",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-ma-msft-001"),
                bookId = BookId("multi-asset"),
                instrumentId = InstrumentId("MSFT"),
                assetClass = AssetClass.EQUITY,
                side = Side.BUY,
                quantity = BigDecimal("7500"),
                price = usd("418.50"),
                tradedAt = BASE_TIME,
                instrumentType = "CASH_EQUITY",
            ),

            // ── fixed-income book: 3 fixed income trades ──
            BookTradeCommand(
                tradeId = TradeId("seed-fi-us2y-001"),
                bookId = BookId("fixed-income"),
                instrumentId = InstrumentId("US2Y"),
                assetClass = AssetClass.FIXED_INCOME,
                side = Side.BUY,
                quantity = BigDecimal("100000"),
                price = usd("99.25"),
                tradedAt = BASE_TIME,
                instrumentType = "GOVERNMENT_BOND",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-fi-us10y-001"),
                bookId = BookId("fixed-income"),
                instrumentId = InstrumentId("US10Y"),
                assetClass = AssetClass.FIXED_INCOME,
                side = Side.BUY,
                quantity = BigDecimal("80000"),
                price = usd("96.50"),
                tradedAt = BASE_TIME,
                instrumentType = "GOVERNMENT_BOND",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-fi-us30y-001"),
                bookId = BookId("fixed-income"),
                instrumentId = InstrumentId("US30Y"),
                assetClass = AssetClass.FIXED_INCOME,
                side = Side.BUY,
                quantity = BigDecimal("50000"),
                price = usd("92.10"),
                tradedAt = BASE_TIME,
                instrumentType = "GOVERNMENT_BOND",
            ),

            // ── emerging-markets book: 5 positions (EM equities + FX) ──
            BookTradeCommand(
                tradeId = TradeId("seed-em-baba-001"),
                bookId = BookId("emerging-markets"),
                instrumentId = InstrumentId("BABA"),
                assetClass = AssetClass.EQUITY,
                side = Side.BUY,
                quantity = BigDecimal("40000"),
                price = usd("83.20"),
                tradedAt = day(1),
                instrumentType = "CASH_EQUITY",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-em-tsla-001"),
                bookId = BookId("emerging-markets"),
                instrumentId = InstrumentId("TSLA"),
                assetClass = AssetClass.EQUITY,
                side = Side.BUY,
                quantity = BigDecimal("6000"),
                price = usd("250.10"),
                tradedAt = day(1),
                instrumentType = "CASH_EQUITY",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-em-eurusd-001"),
                bookId = BookId("emerging-markets"),
                instrumentId = InstrumentId("EURUSD"),
                assetClass = AssetClass.FX,
                side = Side.BUY,
                quantity = BigDecimal("8000000"),
                price = usd("1.0850"),
                tradedAt = day(1),
                instrumentType = "FX_SPOT",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-em-gbpusd-001"),
                bookId = BookId("emerging-markets"),
                instrumentId = InstrumentId("GBPUSD"),
                assetClass = AssetClass.FX,
                side = Side.BUY,
                quantity = BigDecimal("5000000"),
                price = usd("1.2580"),
                tradedAt = day(2),
                instrumentType = "FX_SPOT",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-em-usdjpy-001"),
                bookId = BookId("emerging-markets"),
                instrumentId = InstrumentId("USDJPY"),
                assetClass = AssetClass.FX,
                side = Side.BUY,
                quantity = BigDecimal("10000000"),
                price = usd("150.20"),
                tradedAt = day(2),
                instrumentType = "FX_SPOT",
            ),
            // Partial sell after price rise
            BookTradeCommand(
                tradeId = TradeId("seed-em-baba-002"),
                bookId = BookId("emerging-markets"),
                instrumentId = InstrumentId("BABA"),
                assetClass = AssetClass.EQUITY,
                side = Side.SELL,
                quantity = BigDecimal("10000"),
                price = usd("86.50"),
                tradedAt = day(4),
                instrumentType = "CASH_EQUITY",
            ),

            // ── macro-hedge book: 6 positions (rates, commodities, FX) ──
            BookTradeCommand(
                tradeId = TradeId("seed-mh-usdjpy-001"),
                bookId = BookId("macro-hedge"),
                instrumentId = InstrumentId("USDJPY"),
                assetClass = AssetClass.FX,
                side = Side.BUY,
                quantity = BigDecimal("15000000"),
                price = usd("149.80"),
                tradedAt = BASE_TIME,
                instrumentType = "FX_SPOT",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-mh-gc-001"),
                bookId = BookId("macro-hedge"),
                instrumentId = InstrumentId("GC"),
                assetClass = AssetClass.COMMODITY,
                side = Side.BUY,
                quantity = BigDecimal("200"),
                price = usd("2040.00"),
                tradedAt = BASE_TIME,
                instrumentType = "COMMODITY_FUTURE",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-mh-cl-001"),
                bookId = BookId("macro-hedge"),
                instrumentId = InstrumentId("CL"),
                assetClass = AssetClass.COMMODITY,
                side = Side.BUY,
                quantity = BigDecimal("500"),
                price = usd("76.80"),
                tradedAt = day(1),
                instrumentType = "COMMODITY_FUTURE",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-mh-si-001"),
                bookId = BookId("macro-hedge"),
                instrumentId = InstrumentId("SI"),
                assetClass = AssetClass.COMMODITY,
                side = Side.BUY,
                quantity = BigDecimal("200"),
                price = usd("23.10"),
                tradedAt = day(1),
                instrumentType = "COMMODITY_FUTURE",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-mh-de10y-001"),
                bookId = BookId("macro-hedge"),
                instrumentId = InstrumentId("DE10Y"),
                assetClass = AssetClass.FIXED_INCOME,
                side = Side.BUY,
                quantity = BigDecimal("30000"),
                price = eur("97.80"),
                tradedAt = day(2),
                instrumentType = "GOVERNMENT_BOND",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-mh-spx-put-001"),
                bookId = BookId("macro-hedge"),
                instrumentId = InstrumentId("SPX-PUT-4500"),
                assetClass = AssetClass.DERIVATIVE,
                side = Side.BUY,
                quantity = BigDecimal("800"),
                price = usd("31.20"),
                tradedAt = day(2),
                instrumentType = "EQUITY_OPTION",
            ),
            // Partial sell on gold after rally
            BookTradeCommand(
                tradeId = TradeId("seed-mh-gc-002"),
                bookId = BookId("macro-hedge"),
                instrumentId = InstrumentId("GC"),
                assetClass = AssetClass.COMMODITY,
                side = Side.SELL,
                quantity = BigDecimal("50"),
                price = usd("2060.50"),
                tradedAt = day(4),
                instrumentType = "COMMODITY_FUTURE",
            ),

            // ── tech-momentum book: 4 concentrated tech positions ──
            BookTradeCommand(
                tradeId = TradeId("seed-tm-nvda-001"),
                bookId = BookId("tech-momentum"),
                instrumentId = InstrumentId("NVDA"),
                assetClass = AssetClass.EQUITY,
                side = Side.BUY,
                quantity = BigDecimal("5000"),
                price = usd("885.00"),
                tradedAt = day(2),
                instrumentType = "CASH_EQUITY",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-tm-meta-001"),
                bookId = BookId("tech-momentum"),
                instrumentId = InstrumentId("META"),
                assetClass = AssetClass.EQUITY,
                side = Side.BUY,
                quantity = BigDecimal("8000"),
                price = usd("502.30"),
                tradedAt = day(2),
                instrumentType = "CASH_EQUITY",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-tm-msft-001"),
                bookId = BookId("tech-momentum"),
                instrumentId = InstrumentId("MSFT"),
                assetClass = AssetClass.EQUITY,
                side = Side.BUY,
                quantity = BigDecimal("6000"),
                price = usd("421.50"),
                tradedAt = day(2),
                instrumentType = "CASH_EQUITY",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-tm-googl-001"),
                bookId = BookId("tech-momentum"),
                instrumentId = InstrumentId("GOOGL"),
                assetClass = AssetClass.EQUITY,
                side = Side.BUY,
                quantity = BigDecimal("12000"),
                price = usd("176.80"),
                tradedAt = day(2),
                instrumentType = "CASH_EQUITY",
            ),
            // Partial sell on META after earnings
            BookTradeCommand(
                tradeId = TradeId("seed-tm-meta-002"),
                bookId = BookId("tech-momentum"),
                instrumentId = InstrumentId("META"),
                assetClass = AssetClass.EQUITY,
                side = Side.SELL,
                quantity = BigDecimal("2000"),
                price = usd("510.00"),
                tradedAt = day(6),
                instrumentType = "CASH_EQUITY",
            ),

            // ── balanced-income book: 5 positions (bonds + dividend equities) ──
            BookTradeCommand(
                tradeId = TradeId("seed-bi-us10y-001"),
                bookId = BookId("balanced-income"),
                instrumentId = InstrumentId("US10Y"),
                assetClass = AssetClass.FIXED_INCOME,
                side = Side.BUY,
                quantity = BigDecimal("60000"),
                price = usd("96.60"),
                tradedAt = day(1),
                instrumentType = "GOVERNMENT_BOND",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-bi-us30y-001"),
                bookId = BookId("balanced-income"),
                instrumentId = InstrumentId("US30Y"),
                assetClass = AssetClass.FIXED_INCOME,
                side = Side.BUY,
                quantity = BigDecimal("40000"),
                price = usd("92.30"),
                tradedAt = day(1),
                instrumentType = "GOVERNMENT_BOND",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-bi-de10y-001"),
                bookId = BookId("balanced-income"),
                instrumentId = InstrumentId("DE10Y"),
                assetClass = AssetClass.FIXED_INCOME,
                side = Side.BUY,
                quantity = BigDecimal("25000"),
                price = eur("97.90"),
                tradedAt = day(2),
                instrumentType = "GOVERNMENT_BOND",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-bi-jpm-001"),
                bookId = BookId("balanced-income"),
                instrumentId = InstrumentId("JPM"),
                assetClass = AssetClass.EQUITY,
                side = Side.BUY,
                quantity = BigDecimal("15000"),
                price = usd("208.40"),
                tradedAt = day(2),
                instrumentType = "CASH_EQUITY",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-bi-aapl-001"),
                bookId = BookId("balanced-income"),
                instrumentId = InstrumentId("AAPL"),
                assetClass = AssetClass.EQUITY,
                side = Side.BUY,
                quantity = BigDecimal("7000"),
                price = usd("187.20"),
                tradedAt = day(4),
                instrumentType = "CASH_EQUITY",
            ),
            // Sell some bonds to rebalance
            BookTradeCommand(
                tradeId = TradeId("seed-bi-us30y-002"),
                bookId = BookId("balanced-income"),
                instrumentId = InstrumentId("US30Y"),
                assetClass = AssetClass.FIXED_INCOME,
                side = Side.SELL,
                quantity = BigDecimal("10000"),
                price = usd("93.10"),
                tradedAt = day(6),
                instrumentType = "GOVERNMENT_BOND",
            ),

            // ── derivatives-book: 5 positions (options-heavy) ──
            BookTradeCommand(
                tradeId = TradeId("seed-db-spx-call-001"),
                bookId = BookId("derivatives-book"),
                instrumentId = InstrumentId("SPX-CALL-5000"),
                assetClass = AssetClass.DERIVATIVE,
                side = Side.BUY,
                quantity = BigDecimal("2000"),
                price = usd("41.50"),
                tradedAt = day(1),
                instrumentType = "EQUITY_OPTION",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-db-vix-put-001"),
                bookId = BookId("derivatives-book"),
                instrumentId = InstrumentId("VIX-PUT-15"),
                assetClass = AssetClass.DERIVATIVE,
                side = Side.BUY,
                quantity = BigDecimal("5000"),
                price = usd("3.75"),
                tradedAt = day(1),
                instrumentType = "EQUITY_OPTION",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-db-spx-put-001"),
                bookId = BookId("derivatives-book"),
                instrumentId = InstrumentId("SPX-PUT-4500"),
                assetClass = AssetClass.DERIVATIVE,
                side = Side.BUY,
                quantity = BigDecimal("1500"),
                price = usd("33.00"),
                tradedAt = day(2),
                instrumentType = "EQUITY_OPTION",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-db-nvda-001"),
                bookId = BookId("derivatives-book"),
                instrumentId = InstrumentId("NVDA"),
                assetClass = AssetClass.EQUITY,
                side = Side.BUY,
                quantity = BigDecimal("5000"),
                price = usd("888.00"),
                tradedAt = day(2),
                instrumentType = "CASH_EQUITY",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-db-tsla-001"),
                bookId = BookId("derivatives-book"),
                instrumentId = InstrumentId("TSLA"),
                assetClass = AssetClass.EQUITY,
                side = Side.BUY,
                quantity = BigDecimal("8000"),
                price = usd("249.50"),
                tradedAt = day(4),
                instrumentType = "CASH_EQUITY",
            ),
            // Sell some calls to take profit
            BookTradeCommand(
                tradeId = TradeId("seed-db-spx-call-002"),
                bookId = BookId("derivatives-book"),
                instrumentId = InstrumentId("SPX-CALL-5000"),
                assetClass = AssetClass.DERIVATIVE,
                side = Side.SELL,
                quantity = BigDecimal("800"),
                price = usd("44.20"),
                tradedAt = day(6),
                instrumentType = "EQUITY_OPTION",
            ),

            // ── fixed-income book: corporate bond + interest rate swap ──
            BookTradeCommand(
                tradeId = TradeId("seed-fi-jpm-bond-001"),
                bookId = BookId("fixed-income"),
                instrumentId = InstrumentId("JPM-BOND-2031"),
                assetClass = AssetClass.FIXED_INCOME,
                side = Side.BUY,
                quantity = BigDecimal("20000"),
                price = usd("101.50"),
                tradedAt = day(3),
                instrumentType = "CORPORATE_BOND",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-fi-sofr5y-001"),
                bookId = BookId("fixed-income"),
                instrumentId = InstrumentId("USD-SOFR-5Y"),
                assetClass = AssetClass.FIXED_INCOME,
                side = Side.BUY,
                quantity = BigDecimal("5000"),
                price = usd("99.80"),
                tradedAt = day(3),
                instrumentType = "INTEREST_RATE_SWAP",
            ),

            // ── macro-hedge book: FX forward, WTI future, gold call option ──
            BookTradeCommand(
                tradeId = TradeId("seed-mh-gbpusd3m-001"),
                bookId = BookId("macro-hedge"),
                instrumentId = InstrumentId("GBPUSD-3M"),
                assetClass = AssetClass.FX,
                side = Side.BUY,
                quantity = BigDecimal("3000000"),
                price = usd("1.2800"),
                tradedAt = day(3),
                instrumentType = "FX_FORWARD",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-mh-wti-aug26-001"),
                bookId = BookId("macro-hedge"),
                instrumentId = InstrumentId("WTI-AUG26"),
                assetClass = AssetClass.COMMODITY,
                side = Side.BUY,
                quantity = BigDecimal("300"),
                price = usd("75.50"),
                tradedAt = day(3),
                instrumentType = "COMMODITY_FUTURE",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-mh-gc-call-001"),
                bookId = BookId("macro-hedge"),
                instrumentId = InstrumentId("GC-C-2200-DEC26"),
                assetClass = AssetClass.COMMODITY,
                side = Side.BUY,
                quantity = BigDecimal("100"),
                price = usd("45.80"),
                tradedAt = day(5),
                instrumentType = "COMMODITY_OPTION",
            ),

            // ── emerging-markets book: FX option hedge ──
            BookTradeCommand(
                tradeId = TradeId("seed-em-eurusd-opt-001"),
                bookId = BookId("emerging-markets"),
                instrumentId = InstrumentId("EURUSD-P-1.08-SEP26"),
                assetClass = AssetClass.DERIVATIVE,
                side = Side.BUY,
                quantity = BigDecimal("2000"),
                price = usd("2.15"),
                tradedAt = day(3),
                instrumentType = "FX_OPTION",
            ),

            // ── equity-growth book: collar structure on AAPL ──
            BookTradeCommand(
                tradeId = TradeId("seed-eg-aapl-col-c-001"),
                bookId = BookId("equity-growth"),
                instrumentId = InstrumentId("AAPL-C-200-20260620"),
                assetClass = AssetClass.DERIVATIVE,
                side = Side.SELL,
                quantity = BigDecimal("5000"),
                price = usd("8.50"),
                tradedAt = day(5),
                instrumentType = "EQUITY_OPTION",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-eg-aapl-col-p-001"),
                bookId = BookId("equity-growth"),
                instrumentId = InstrumentId("AAPL-P-180-20260620"),
                assetClass = AssetClass.DERIVATIVE,
                side = Side.BUY,
                quantity = BigDecimal("5000"),
                price = usd("6.20"),
                tradedAt = day(5),
                instrumentType = "EQUITY_OPTION",
            ),

            // ── derivatives-book: bull call spread + put spread + NVDA collar + SPX delta hedge ──
            BookTradeCommand(
                tradeId = TradeId("seed-db-spx-cs-001"),
                bookId = BookId("derivatives-book"),
                instrumentId = InstrumentId("SPX-CALL-5200"),
                assetClass = AssetClass.DERIVATIVE,
                side = Side.SELL,
                quantity = BigDecimal("1200"),
                price = usd("22.30"),
                tradedAt = day(3),
                instrumentType = "EQUITY_OPTION",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-db-spx-ps-001"),
                bookId = BookId("derivatives-book"),
                instrumentId = InstrumentId("SPX-PUT-4800"),
                assetClass = AssetClass.DERIVATIVE,
                side = Side.BUY,
                quantity = BigDecimal("1500"),
                price = usd("55.40"),
                tradedAt = day(3),
                instrumentType = "EQUITY_OPTION",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-db-spx-ps-002"),
                bookId = BookId("derivatives-book"),
                instrumentId = InstrumentId("SPX-PUT-4500"),
                assetClass = AssetClass.DERIVATIVE,
                side = Side.SELL,
                quantity = BigDecimal("1500"),
                price = usd("28.75"),
                tradedAt = day(3),
                instrumentType = "EQUITY_OPTION",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-db-nvda-col-p-001"),
                bookId = BookId("derivatives-book"),
                instrumentId = InstrumentId("NVDA-P-800-20260620"),
                assetClass = AssetClass.DERIVATIVE,
                side = Side.BUY,
                quantity = BigDecimal("3000"),
                price = usd("35.20"),
                tradedAt = day(4),
                instrumentType = "EQUITY_OPTION",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-db-nvda-col-c-001"),
                bookId = BookId("derivatives-book"),
                instrumentId = InstrumentId("NVDA-C-950-20260620"),
                assetClass = AssetClass.DERIVATIVE,
                side = Side.SELL,
                quantity = BigDecimal("3000"),
                price = usd("28.50"),
                tradedAt = day(4),
                instrumentType = "EQUITY_OPTION",
            ),
            BookTradeCommand(
                tradeId = TradeId("seed-db-spx-fut-001"),
                bookId = BookId("derivatives-book"),
                instrumentId = InstrumentId("SPX-SEP26"),
                assetClass = AssetClass.DERIVATIVE,
                side = Side.SELL,
                quantity = BigDecimal("400"),
                price = usd("5020.00"),
                tradedAt = day(5),
                instrumentType = "EQUITY_FUTURE",
            ),

            // ── balanced-income book: EURUSD spot hedge for DE10Y EUR exposure ──
            BookTradeCommand(
                tradeId = TradeId("seed-bi-eurusd-hdg-001"),
                bookId = BookId("balanced-income"),
                instrumentId = InstrumentId("EURUSD"),
                assetClass = AssetClass.FX,
                side = Side.SELL,
                quantity = BigDecimal("2500000"),
                price = usd("1.0860"),
                tradedAt = day(3),
                instrumentType = "FX_SPOT",
            ),
        )

        val TRADES: List<BookTradeCommand> = CORE_TRADES + buildGeneratedTrades()

        val MARKET_PRICES: Map<Pair<BookId, InstrumentId>, Money> = mapOf(
            // ── equity-growth (24) ──
            Pair(BookId("equity-growth"), InstrumentId("AAPL")) to usd("189.25"),
            Pair(BookId("equity-growth"), InstrumentId("GOOGL")) to usd("178.90"),
            Pair(BookId("equity-growth"), InstrumentId("MSFT")) to usd("425.60"),
            Pair(BookId("equity-growth"), InstrumentId("AMZN")) to usd("210.30"),
            Pair(BookId("equity-growth"), InstrumentId("META")) to usd("508.40"),
            Pair(BookId("equity-growth"), InstrumentId("JPM")) to usd("211.80"),
            Pair(BookId("equity-growth"), InstrumentId("BAC")) to usd("39.80"),
            Pair(BookId("equity-growth"), InstrumentId("DIS")) to usd("112.50"),
            Pair(BookId("equity-growth"), InstrumentId("KO")) to usd("62.80"),
            Pair(BookId("equity-growth"), InstrumentId("WMT")) to usd("172.30"),
            Pair(BookId("equity-growth"), InstrumentId("JNJ")) to usd("158.40"),
            Pair(BookId("equity-growth"), InstrumentId("UNH")) to usd("540.60"),
            Pair(BookId("equity-growth"), InstrumentId("CRM")) to usd("305.80"),
            Pair(BookId("equity-growth"), InstrumentId("ADBE")) to usd("470.50"),
            Pair(BookId("equity-growth"), InstrumentId("ORCL")) to usd("176.50"),
            Pair(BookId("equity-growth"), InstrumentId("PFE")) to usd("28.60"),
            Pair(BookId("equity-growth"), InstrumentId("XOM")) to usd("116.80"),
            Pair(BookId("equity-growth"), InstrumentId("CVX")) to usd("163.20"),
            Pair(BookId("equity-growth"), InstrumentId("TSLA")) to usd("242.15"),
            Pair(BookId("equity-growth"), InstrumentId("AAPL-C-200-20260620")) to usd("7.80"),
            Pair(BookId("equity-growth"), InstrumentId("AAPL-P-180-20260620")) to usd("6.80"),
            Pair(BookId("equity-growth"), InstrumentId("MSFT-C-450-20260620")) to usd("10.80"),
            Pair(BookId("equity-growth"), InstrumentId("MSFT-P-400-20260620")) to usd("9.50"),
            Pair(BookId("equity-growth"), InstrumentId("SPX-PUT-4500")) to usd("28.75"),
            Pair(BookId("equity-growth"), InstrumentId("SPX-SEP26")) to usd("5035.00"),
            // ── tech-momentum (22) ──
            Pair(BookId("tech-momentum"), InstrumentId("NVDA")) to usd("892.50"),
            Pair(BookId("tech-momentum"), InstrumentId("META")) to usd("508.40"),
            Pair(BookId("tech-momentum"), InstrumentId("MSFT")) to usd("425.60"),
            Pair(BookId("tech-momentum"), InstrumentId("GOOGL")) to usd("178.90"),
            Pair(BookId("tech-momentum"), InstrumentId("AMZN")) to usd("210.30"),
            Pair(BookId("tech-momentum"), InstrumentId("TSLA")) to usd("242.15"),
            Pair(BookId("tech-momentum"), InstrumentId("AMD")) to usd("164.20"),
            Pair(BookId("tech-momentum"), InstrumentId("INTC")) to usd("24.10"),
            Pair(BookId("tech-momentum"), InstrumentId("CRM")) to usd("305.80"),
            Pair(BookId("tech-momentum"), InstrumentId("ADBE")) to usd("470.50"),
            Pair(BookId("tech-momentum"), InstrumentId("ORCL")) to usd("176.50"),
            Pair(BookId("tech-momentum"), InstrumentId("GS")) to usd("495.30"),
            Pair(BookId("tech-momentum"), InstrumentId("NVDA-C-950-20260620")) to usd("26.80"),
            Pair(BookId("tech-momentum"), InstrumentId("NVDA-P-800-20260620")) to usd("37.50"),
            Pair(BookId("tech-momentum"), InstrumentId("TSLA-C-280-20260620")) to usd("12.40"),
            Pair(BookId("tech-momentum"), InstrumentId("TSLA-P-220-20260620")) to usd("13.20"),
            Pair(BookId("tech-momentum"), InstrumentId("MSFT-C-450-20260620")) to usd("10.80"),
            Pair(BookId("tech-momentum"), InstrumentId("MSFT-P-400-20260620")) to usd("9.50"),
            Pair(BookId("tech-momentum"), InstrumentId("NDX-SEP26")) to usd("18100.00"),
            Pair(BookId("tech-momentum"), InstrumentId("SPX-CALL-5200")) to usd("23.50"),
            Pair(BookId("tech-momentum"), InstrumentId("SPX-PUT-4800")) to usd("58.20"),
            Pair(BookId("tech-momentum"), InstrumentId("VIX-PUT-15")) to usd("3.60"),
            // ── emerging-markets (20) ──
            Pair(BookId("emerging-markets"), InstrumentId("BABA")) to usd("86.10"),
            Pair(BookId("emerging-markets"), InstrumentId("TSLA")) to usd("242.15"),
            Pair(BookId("emerging-markets"), InstrumentId("META")) to usd("508.40"),
            Pair(BookId("emerging-markets"), InstrumentId("JPM")) to usd("211.80"),
            Pair(BookId("emerging-markets"), InstrumentId("GS")) to usd("495.30"),
            Pair(BookId("emerging-markets"), InstrumentId("EURUSD")) to usd("1.0856"),
            Pair(BookId("emerging-markets"), InstrumentId("GBPUSD")) to usd("1.2620"),
            Pair(BookId("emerging-markets"), InstrumentId("USDJPY")) to usd("150.80"),
            Pair(BookId("emerging-markets"), InstrumentId("AUDUSD")) to usd("0.6580"),
            Pair(BookId("emerging-markets"), InstrumentId("USDCAD")) to usd("1.3620"),
            Pair(BookId("emerging-markets"), InstrumentId("NZDUSD")) to usd("0.6140"),
            Pair(BookId("emerging-markets"), InstrumentId("GBPUSD-3M")) to usd("1.2800"),
            Pair(BookId("emerging-markets"), InstrumentId("EURUSD-6M")) to usd("1.0880"),
            Pair(BookId("emerging-markets"), InstrumentId("USDJPY-3M")) to usd("149.50"),
            Pair(BookId("emerging-markets"), InstrumentId("EURUSD-P-1.08-SEP26")) to usd("1.95"),
            Pair(BookId("emerging-markets"), InstrumentId("USDJPY-C-155-SEP26")) to usd("2.80"),
            Pair(BookId("emerging-markets"), InstrumentId("DE10Y")) to eur("98.20"),
            Pair(BookId("emerging-markets"), InstrumentId("JP10Y")) to usd("99.40"),
            Pair(BookId("emerging-markets"), InstrumentId("GC")) to usd("2058.40"),
            Pair(BookId("emerging-markets"), InstrumentId("WTI-AUG26")) to usd("76.20"),
            // ── fixed-income (22) ──
            Pair(BookId("fixed-income"), InstrumentId("US2Y")) to usd("99.40"),
            Pair(BookId("fixed-income"), InstrumentId("US5Y")) to usd("98.80"),
            Pair(BookId("fixed-income"), InstrumentId("US10Y")) to usd("97.10"),
            Pair(BookId("fixed-income"), InstrumentId("US30Y")) to usd("93.25"),
            Pair(BookId("fixed-income"), InstrumentId("DE2Y")) to eur("99.75"),
            Pair(BookId("fixed-income"), InstrumentId("DE10Y")) to eur("98.20"),
            Pair(BookId("fixed-income"), InstrumentId("UK10Y")) to usd("96.30"),
            Pair(BookId("fixed-income"), InstrumentId("JP10Y")) to usd("99.40"),
            Pair(BookId("fixed-income"), InstrumentId("JPM-BOND-2031")) to usd("102.30"),
            Pair(BookId("fixed-income"), InstrumentId("AAPL-BOND-2030")) to usd("101.50"),
            Pair(BookId("fixed-income"), InstrumentId("GS-BOND-2029")) to usd("103.10"),
            Pair(BookId("fixed-income"), InstrumentId("MSFT-BOND-2032")) to usd("100.20"),
            Pair(BookId("fixed-income"), InstrumentId("USD-SOFR-5Y")) to usd("99.95"),
            Pair(BookId("fixed-income"), InstrumentId("USD-SOFR-10Y")) to usd("99.70"),
            Pair(BookId("fixed-income"), InstrumentId("EUR-ESTR-5Y")) to eur("99.75"),
            Pair(BookId("fixed-income"), InstrumentId("JPM")) to usd("211.80"),
            Pair(BookId("fixed-income"), InstrumentId("BAC")) to usd("39.80"),
            Pair(BookId("fixed-income"), InstrumentId("GS")) to usd("495.30"),
            Pair(BookId("fixed-income"), InstrumentId("MS")) to usd("101.40"),
            Pair(BookId("fixed-income"), InstrumentId("EURUSD")) to usd("1.0856"),
            Pair(BookId("fixed-income"), InstrumentId("GBPUSD")) to usd("1.2620"),
            Pair(BookId("fixed-income"), InstrumentId("EURGBP")) to usd("0.8610"),
            // ── multi-asset (28) ──
            Pair(BookId("multi-asset"), InstrumentId("AAPL")) to usd("189.25"),
            Pair(BookId("multi-asset"), InstrumentId("MSFT")) to usd("425.60"),
            Pair(BookId("multi-asset"), InstrumentId("NVDA")) to usd("892.50"),
            Pair(BookId("multi-asset"), InstrumentId("TSLA")) to usd("242.15"),
            Pair(BookId("multi-asset"), InstrumentId("JPM")) to usd("211.80"),
            Pair(BookId("multi-asset"), InstrumentId("GS")) to usd("495.30"),
            Pair(BookId("multi-asset"), InstrumentId("UNH")) to usd("540.60"),
            Pair(BookId("multi-asset"), InstrumentId("XOM")) to usd("116.80"),
            Pair(BookId("multi-asset"), InstrumentId("US10Y")) to usd("97.10"),
            Pair(BookId("multi-asset"), InstrumentId("US30Y")) to usd("93.25"),
            Pair(BookId("multi-asset"), InstrumentId("DE10Y")) to eur("98.20"),
            Pair(BookId("multi-asset"), InstrumentId("JPM-BOND-2031")) to usd("102.30"),
            Pair(BookId("multi-asset"), InstrumentId("EURUSD")) to usd("1.0856"),
            Pair(BookId("multi-asset"), InstrumentId("GBPUSD")) to usd("1.2620"),
            Pair(BookId("multi-asset"), InstrumentId("AUDUSD")) to usd("0.6580"),
            Pair(BookId("multi-asset"), InstrumentId("USDCHF")) to usd("0.8860"),
            Pair(BookId("multi-asset"), InstrumentId("GC")) to usd("2058.40"),
            Pair(BookId("multi-asset"), InstrumentId("CL")) to usd("78.30"),
            Pair(BookId("multi-asset"), InstrumentId("NG")) to usd("3.10"),
            Pair(BookId("multi-asset"), InstrumentId("HG")) to usd("4.28"),
            Pair(BookId("multi-asset"), InstrumentId("SPX-PUT-4500")) to usd("28.75"),
            Pair(BookId("multi-asset"), InstrumentId("SPX-CALL-5000")) to usd("43.80"),
            Pair(BookId("multi-asset"), InstrumentId("AAPL-C-200-20260620")) to usd("7.80"),
            Pair(BookId("multi-asset"), InstrumentId("MSFT-C-450-20260620")) to usd("10.80"),
            Pair(BookId("multi-asset"), InstrumentId("USD-SOFR-5Y")) to usd("99.95"),
            Pair(BookId("multi-asset"), InstrumentId("SPX-SEP26")) to usd("5035.00"),
            Pair(BookId("multi-asset"), InstrumentId("NDX-SEP26")) to usd("18100.00"),
            Pair(BookId("multi-asset"), InstrumentId("EURUSD-6M")) to usd("1.0880"),
            // ── macro-hedge (24) ──
            Pair(BookId("macro-hedge"), InstrumentId("USDJPY")) to usd("150.80"),
            Pair(BookId("macro-hedge"), InstrumentId("EURUSD")) to usd("1.0856"),
            Pair(BookId("macro-hedge"), InstrumentId("GBPUSD")) to usd("1.2620"),
            Pair(BookId("macro-hedge"), InstrumentId("USDCHF")) to usd("0.8860"),
            Pair(BookId("macro-hedge"), InstrumentId("EURGBP")) to usd("0.8610"),
            Pair(BookId("macro-hedge"), InstrumentId("AUDUSD")) to usd("0.6580"),
            Pair(BookId("macro-hedge"), InstrumentId("USDJPY-3M")) to usd("149.50"),
            Pair(BookId("macro-hedge"), InstrumentId("EURUSD-6M")) to usd("1.0880"),
            Pair(BookId("macro-hedge"), InstrumentId("USDJPY-C-155-SEP26")) to usd("2.80"),
            Pair(BookId("macro-hedge"), InstrumentId("GBPUSD-3M")) to usd("1.2800"),
            Pair(BookId("macro-hedge"), InstrumentId("GC")) to usd("2058.40"),
            Pair(BookId("macro-hedge"), InstrumentId("CL")) to usd("78.30"),
            Pair(BookId("macro-hedge"), InstrumentId("SI")) to usd("23.65"),
            Pair(BookId("macro-hedge"), InstrumentId("NG")) to usd("3.10"),
            Pair(BookId("macro-hedge"), InstrumentId("HG")) to usd("4.28"),
            Pair(BookId("macro-hedge"), InstrumentId("ZC")) to usd("4.68"),
            Pair(BookId("macro-hedge"), InstrumentId("GC-C-2200-DEC26")) to usd("48.50"),
            Pair(BookId("macro-hedge"), InstrumentId("CL-P-70-DEC26")) to usd("4.50"),
            Pair(BookId("macro-hedge"), InstrumentId("US10Y")) to usd("97.10"),
            Pair(BookId("macro-hedge"), InstrumentId("DE10Y")) to eur("98.20"),
            Pair(BookId("macro-hedge"), InstrumentId("UK10Y")) to usd("96.30"),
            Pair(BookId("macro-hedge"), InstrumentId("SPX-PUT-4500")) to usd("28.75"),
            Pair(BookId("macro-hedge"), InstrumentId("SPX-SEP26")) to usd("5035.00"),
            Pair(BookId("macro-hedge"), InstrumentId("RTY-SEP26")) to usd("2120.00"),
            Pair(BookId("macro-hedge"), InstrumentId("WTI-AUG26")) to usd("76.20"),
            // ── balanced-income (22) ──
            Pair(BookId("balanced-income"), InstrumentId("US10Y")) to usd("97.10"),
            Pair(BookId("balanced-income"), InstrumentId("US30Y")) to usd("93.25"),
            Pair(BookId("balanced-income"), InstrumentId("US5Y")) to usd("98.80"),
            Pair(BookId("balanced-income"), InstrumentId("DE10Y")) to eur("98.20"),
            Pair(BookId("balanced-income"), InstrumentId("UK10Y")) to usd("96.30"),
            Pair(BookId("balanced-income"), InstrumentId("JPM-BOND-2031")) to usd("102.30"),
            Pair(BookId("balanced-income"), InstrumentId("AAPL-BOND-2030")) to usd("101.50"),
            Pair(BookId("balanced-income"), InstrumentId("GS-BOND-2029")) to usd("103.10"),
            Pair(BookId("balanced-income"), InstrumentId("MSFT-BOND-2032")) to usd("100.20"),
            Pair(BookId("balanced-income"), InstrumentId("USD-SOFR-5Y")) to usd("99.95"),
            Pair(BookId("balanced-income"), InstrumentId("USD-SOFR-10Y")) to usd("99.70"),
            Pair(BookId("balanced-income"), InstrumentId("EUR-ESTR-5Y")) to eur("99.75"),
            Pair(BookId("balanced-income"), InstrumentId("JPM")) to usd("211.80"),
            Pair(BookId("balanced-income"), InstrumentId("BAC")) to usd("39.80"),
            Pair(BookId("balanced-income"), InstrumentId("KO")) to usd("62.80"),
            Pair(BookId("balanced-income"), InstrumentId("JNJ")) to usd("158.40"),
            Pair(BookId("balanced-income"), InstrumentId("PFE")) to usd("28.60"),
            Pair(BookId("balanced-income"), InstrumentId("UNH")) to usd("540.60"),
            Pair(BookId("balanced-income"), InstrumentId("XOM")) to usd("116.80"),
            Pair(BookId("balanced-income"), InstrumentId("AAPL-C-200-20260620")) to usd("7.80"),
            Pair(BookId("balanced-income"), InstrumentId("EURUSD")) to usd("1.0856"),
            Pair(BookId("balanced-income"), InstrumentId("AAPL")) to usd("189.25"),
            // ── derivatives-book (28) ──
            Pair(BookId("derivatives-book"), InstrumentId("SPX-CALL-5000")) to usd("43.80"),
            Pair(BookId("derivatives-book"), InstrumentId("SPX-CALL-5200")) to usd("23.50"),
            Pair(BookId("derivatives-book"), InstrumentId("SPX-PUT-4500")) to usd("28.75"),
            Pair(BookId("derivatives-book"), InstrumentId("SPX-PUT-4800")) to usd("58.20"),
            Pair(BookId("derivatives-book"), InstrumentId("AAPL-C-200-20260620")) to usd("7.80"),
            Pair(BookId("derivatives-book"), InstrumentId("AAPL-P-180-20260620")) to usd("6.80"),
            Pair(BookId("derivatives-book"), InstrumentId("NVDA-C-950-20260620")) to usd("26.80"),
            Pair(BookId("derivatives-book"), InstrumentId("NVDA-P-800-20260620")) to usd("37.50"),
            Pair(BookId("derivatives-book"), InstrumentId("MSFT-C-450-20260620")) to usd("10.80"),
            Pair(BookId("derivatives-book"), InstrumentId("MSFT-P-400-20260620")) to usd("9.50"),
            Pair(BookId("derivatives-book"), InstrumentId("TSLA-C-280-20260620")) to usd("12.40"),
            Pair(BookId("derivatives-book"), InstrumentId("TSLA-P-220-20260620")) to usd("13.20"),
            Pair(BookId("derivatives-book"), InstrumentId("GOOGL-C-190-20260620")) to usd("6.80"),
            Pair(BookId("derivatives-book"), InstrumentId("GOOGL-P-160-20260620")) to usd("6.50"),
            Pair(BookId("derivatives-book"), InstrumentId("AMZN-C-220-20260620")) to usd("8.60"),
            Pair(BookId("derivatives-book"), InstrumentId("AMZN-P-190-20260620")) to usd("9.20"),
            Pair(BookId("derivatives-book"), InstrumentId("GC-C-2200-DEC26")) to usd("48.50"),
            Pair(BookId("derivatives-book"), InstrumentId("CL-P-70-DEC26")) to usd("4.50"),
            Pair(BookId("derivatives-book"), InstrumentId("EURUSD-P-1.08-SEP26")) to usd("1.95"),
            Pair(BookId("derivatives-book"), InstrumentId("USDJPY-C-155-SEP26")) to usd("2.80"),
            Pair(BookId("derivatives-book"), InstrumentId("VIX-PUT-15")) to usd("3.60"),
            Pair(BookId("derivatives-book"), InstrumentId("SPX-SEP26")) to usd("5035.00"),
            Pair(BookId("derivatives-book"), InstrumentId("NDX-SEP26")) to usd("18100.00"),
            Pair(BookId("derivatives-book"), InstrumentId("RTY-SEP26")) to usd("2120.00"),
            Pair(BookId("derivatives-book"), InstrumentId("NVDA")) to usd("892.50"),
            Pair(BookId("derivatives-book"), InstrumentId("TSLA")) to usd("242.15"),
            Pair(BookId("derivatives-book"), InstrumentId("AAPL")) to usd("189.25"),
            Pair(BookId("derivatives-book"), InstrumentId("MSFT")) to usd("425.60"),
        )

        private fun limit(
            id: String,
            level: LimitLevel,
            entityId: String,
            type: LimitType,
            value: String,
            intraday: String? = null,
            overnight: String? = null,
        ) = LimitDefinition(
            id = id,
            level = level,
            entityId = entityId,
            limitType = type,
            limitValue = BigDecimal(value),
            intradayLimit = intraday?.let { BigDecimal(it) },
            overnightLimit = overnight?.let { BigDecimal(it) },
            active = true,
        )

        val LIMIT_DEFINITIONS: List<LimitDefinition> = listOf(
            // ── FIRM level ──
            limit("seed-lim-firm-notional", LimitLevel.FIRM, "FIRM", LimitType.NOTIONAL, "800000000"),
            limit("seed-lim-firm-position", LimitLevel.FIRM, "FIRM", LimitType.POSITION, "50000000"),
            limit("seed-lim-firm-concentration", LimitLevel.FIRM, "FIRM", LimitType.CONCENTRATION, "0.35"),

            // ── DESK level ──
            limit("seed-lim-desk-eq-notional", LimitLevel.DESK, "equity-growth", LimitType.NOTIONAL, "150000000"),
            limit("seed-lim-desk-tech-notional", LimitLevel.DESK, "tech-momentum", LimitType.NOTIONAL, "120000000"),
            limit("seed-lim-desk-fi-notional", LimitLevel.DESK, "rates-trading", LimitType.NOTIONAL, "200000000"),
            limit("seed-lim-desk-ma-notional", LimitLevel.DESK, "multi-asset-strategies", LimitType.NOTIONAL, "180000000"),
            limit("seed-lim-desk-mh-notional", LimitLevel.DESK, "macro-hedge", LimitType.NOTIONAL, "160000000"),
            limit("seed-lim-desk-em-notional", LimitLevel.DESK, "emerging-markets", LimitType.NOTIONAL, "120000000"),
            limit("seed-lim-desk-bi-notional", LimitLevel.DESK, "balanced-income", LimitType.NOTIONAL, "150000000"),
            limit("seed-lim-desk-db-notional", LimitLevel.DESK, "derivatives-trading", LimitType.NOTIONAL, "100000000"),

            // ── BOOK level — calibrated to produce interesting limit utilisation ──
            // tech-momentum concentration: NVDA heavy, limit 32% → BREACHED
            // derivatives-book notional: limit set to trigger breach with expanded portfolio
            // macro-hedge notional: high utilisation ~88%
            limit("seed-lim-book-eq-notional", LimitLevel.BOOK, "equity-growth", LimitType.NOTIONAL, "40000000",
                intraday = "45000000", overnight = "38000000"),
            limit("seed-lim-book-tech-notional", LimitLevel.BOOK, "tech-momentum", LimitType.NOTIONAL, "30000000",
                intraday = "35000000", overnight = "28000000"),
            limit("seed-lim-book-tech-conc", LimitLevel.BOOK, "tech-momentum", LimitType.CONCENTRATION, "0.32"),
            limit("seed-lim-book-em-notional", LimitLevel.BOOK, "emerging-markets", LimitType.NOTIONAL, "60000000"),
            limit("seed-lim-book-fi-notional", LimitLevel.BOOK, "fixed-income", LimitType.NOTIONAL, "50000000"),
            limit("seed-lim-book-ma-notional", LimitLevel.BOOK, "multi-asset", LimitType.NOTIONAL, "80000000"),
            limit("seed-lim-book-mh-notional", LimitLevel.BOOK, "macro-hedge", LimitType.NOTIONAL, "120000000"),
            limit("seed-lim-book-bi-notional", LimitLevel.BOOK, "balanced-income", LimitType.NOTIONAL, "40000000"),
            limit("seed-lim-book-db-notional", LimitLevel.BOOK, "derivatives-book", LimitType.NOTIONAL, "35000000",
                intraday = "40000000", overnight = "32000000"),
            limit("seed-lim-book-db-conc", LimitLevel.BOOK, "derivatives-book", LimitType.CONCENTRATION, "0.40"),
        )

        private fun fill(dayOffset: Long, minutesAfter: Long): Instant =
            BASE_TIME.plus(dayOffset, ChronoUnit.DAYS).plus(minutesAfter, ChronoUnit.MINUTES)

        private fun execCost(
            orderId: String,
            bookId: String,
            instrumentId: String,
            completedAt: Instant,
            arrivalPrice: String,
            avgFillPrice: String,
            side: Side,
            totalQty: String,
            slippageBps: String,
            marketImpactBps: String? = null,
            timingCostBps: String? = null,
            totalCostBps: String,
        ) = ExecutionCostAnalysis(
            orderId = orderId,
            bookId = bookId,
            instrumentId = instrumentId,
            completedAt = completedAt,
            arrivalPrice = BigDecimal(arrivalPrice),
            averageFillPrice = BigDecimal(avgFillPrice),
            side = side,
            totalQty = BigDecimal(totalQty),
            metrics = ExecutionCostMetrics(
                slippageBps = BigDecimal(slippageBps),
                marketImpactBps = marketImpactBps?.let { BigDecimal(it) },
                timingCostBps = timingCostBps?.let { BigDecimal(it) },
                totalCostBps = BigDecimal(totalCostBps),
            ),
        )

        // Slippage formula: (avgFillPrice - arrivalPrice) / arrivalPrice * 10000 * sideSign
        // sideSign = +1 for BUY, -1 for SELL
        // totalCostBps = slippageBps + (marketImpactBps ?: 0) + (timingCostBps ?: 0)
        val EXECUTION_COSTS: List<ExecutionCostAnalysis> = listOf(
            // ── equity-growth: 4 of 5 trades (skip TSLA) ──
            execCost("seed-exec-eq-aapl-001", "equity-growth", "AAPL", fill(0, 3),
                arrivalPrice = "185.25", avgFillPrice = "185.50", side = Side.BUY, totalQty = "25000",
                slippageBps = "13.4953", marketImpactBps = "3.5000", timingCostBps = "1.2000", totalCostBps = "18.1953"),
            execCost("seed-exec-eq-googl-001", "equity-growth", "GOOGL", fill(0, 7),
                arrivalPrice = "175.35", avgFillPrice = "175.20", side = Side.BUY, totalQty = "15000",
                slippageBps = "-8.5530", totalCostBps = "-8.5530"),
            execCost("seed-exec-eq-msft-001", "equity-growth", "MSFT", fill(0, 12),
                arrivalPrice = "419.70", avgFillPrice = "420.00", side = Side.BUY, totalQty = "8000",
                slippageBps = "7.1480", marketImpactBps = "2.8000", totalCostBps = "9.9480"),
            execCost("seed-exec-eq-amzn-001", "equity-growth", "AMZN", fill(0, 18),
                arrivalPrice = "205.60", avgFillPrice = "205.75", side = Side.BUY, totalQty = "12000",
                slippageBps = "7.2957", totalCostBps = "7.2957"),

            // ── multi-asset: 4 of 6 trades (skip SPX-PUT, MSFT) ──
            execCost("seed-exec-ma-aapl-001", "multi-asset", "AAPL", fill(0, 5),
                arrivalPrice = "186.10", avgFillPrice = "186.00", side = Side.BUY, totalQty = "5000",
                slippageBps = "-5.3735", totalCostBps = "-5.3735"),
            execCost("seed-exec-ma-eurusd-001", "multi-asset", "EURUSD", fill(0, 1),
                arrivalPrice = "1.0840", avgFillPrice = "1.0842", side = Side.BUY, totalQty = "10000000",
                slippageBps = "1.8450", totalCostBps = "1.8450"),
            execCost("seed-exec-ma-us10y-001", "multi-asset", "US10Y", fill(0, 22),
                arrivalPrice = "96.70", avgFillPrice = "96.75", side = Side.BUY, totalQty = "50000",
                slippageBps = "5.1706", totalCostBps = "5.1706"),
            execCost("seed-exec-ma-gc-001", "multi-asset", "GC", fill(0, 35),
                arrivalPrice = "2044.80", avgFillPrice = "2045.60", side = Side.BUY, totalQty = "100",
                slippageBps = "3.9121", marketImpactBps = "1.5000", totalCostBps = "5.4121"),

            // ── fixed-income: all 3 trades (OTC bonds, tighter slippage) ──
            execCost("seed-exec-fi-us2y-001", "fixed-income", "US2Y", fill(0, 25),
                arrivalPrice = "99.22", avgFillPrice = "99.25", side = Side.BUY, totalQty = "100000",
                slippageBps = "3.0236", totalCostBps = "3.0236"),
            execCost("seed-exec-fi-us10y-001", "fixed-income", "US10Y", fill(0, 30),
                arrivalPrice = "96.46", avgFillPrice = "96.50", side = Side.BUY, totalQty = "80000",
                slippageBps = "4.1468", totalCostBps = "4.1468"),
            execCost("seed-exec-fi-us30y-001", "fixed-income", "US30Y", fill(0, 40),
                arrivalPrice = "92.06", avgFillPrice = "92.10", side = Side.BUY, totalQty = "50000",
                slippageBps = "4.3450", totalCostBps = "4.3450"),

            // ── emerging-markets: 5 of 6 trades (skip USDJPY) ──
            execCost("seed-exec-em-baba-001", "emerging-markets", "BABA", fill(1, 8),
                arrivalPrice = "83.05", avgFillPrice = "83.20", side = Side.BUY, totalQty = "40000",
                slippageBps = "18.0614", marketImpactBps = "4.5000", timingCostBps = "2.1000", totalCostBps = "24.6614"),
            execCost("seed-exec-em-tsla-001", "emerging-markets", "TSLA", fill(1, 14),
                arrivalPrice = "249.80", avgFillPrice = "250.10", side = Side.BUY, totalQty = "6000",
                slippageBps = "12.0096", totalCostBps = "12.0096"),
            execCost("seed-exec-em-eurusd-001", "emerging-markets", "EURUSD", fill(1, 2),
                arrivalPrice = "1.0848", avgFillPrice = "1.0850", side = Side.BUY, totalQty = "8000000",
                slippageBps = "1.8433", totalCostBps = "1.8433"),
            execCost("seed-exec-em-gbpusd-001", "emerging-markets", "GBPUSD", fill(2, 6),
                arrivalPrice = "1.2578", avgFillPrice = "1.2580", side = Side.BUY, totalQty = "5000000",
                slippageBps = "1.5901", totalCostBps = "1.5901"),
            execCost("seed-exec-em-baba-002", "emerging-markets", "BABA", fill(4, 11),
                arrivalPrice = "86.70", avgFillPrice = "86.50", side = Side.SELL, totalQty = "10000",
                slippageBps = "23.0681", totalCostBps = "23.0681"),

            // ── macro-hedge: 5 of 7 trades (skip DE10Y, SPX-PUT) ──
            execCost("seed-exec-mh-usdjpy-001", "macro-hedge", "USDJPY", fill(0, 4),
                arrivalPrice = "149.75", avgFillPrice = "149.80", side = Side.BUY, totalQty = "15000000",
                slippageBps = "3.3389", marketImpactBps = "2.0000", timingCostBps = "1.0000", totalCostBps = "6.3389"),
            execCost("seed-exec-mh-gc-001", "macro-hedge", "GC", fill(0, 28),
                arrivalPrice = "2039.20", avgFillPrice = "2040.00", side = Side.BUY, totalQty = "200",
                slippageBps = "3.9231", totalCostBps = "3.9231"),
            execCost("seed-exec-mh-cl-001", "macro-hedge", "CL", fill(1, 19),
                arrivalPrice = "76.72", avgFillPrice = "76.80", side = Side.BUY, totalQty = "500",
                slippageBps = "10.4275", marketImpactBps = "3.2000", timingCostBps = "1.5000", totalCostBps = "15.1275"),
            execCost("seed-exec-mh-si-001", "macro-hedge", "SI", fill(1, 33),
                arrivalPrice = "23.12", avgFillPrice = "23.10", side = Side.BUY, totalQty = "200",
                slippageBps = "-8.6505", totalCostBps = "-8.6505"),
            execCost("seed-exec-mh-gc-002", "macro-hedge", "GC", fill(4, 15),
                arrivalPrice = "2061.00", avgFillPrice = "2060.50", side = Side.SELL, totalQty = "50",
                slippageBps = "2.4260", totalCostBps = "2.4260"),

            // ── tech-momentum: 4 of 5 trades (skip GOOGL) ──
            execCost("seed-exec-tm-nvda-001", "tech-momentum", "NVDA", fill(2, 9),
                arrivalPrice = "883.50", avgFillPrice = "885.00", side = Side.BUY, totalQty = "5000",
                slippageBps = "16.9836", marketImpactBps = "5.0000", timingCostBps = "2.5000", totalCostBps = "24.4836"),
            execCost("seed-exec-tm-meta-001", "tech-momentum", "META", fill(2, 16),
                arrivalPrice = "502.00", avgFillPrice = "502.30", side = Side.BUY, totalQty = "8000",
                slippageBps = "5.9761", totalCostBps = "5.9761"),
            execCost("seed-exec-tm-msft-001", "tech-momentum", "MSFT", fill(2, 24),
                arrivalPrice = "421.20", avgFillPrice = "421.50", side = Side.BUY, totalQty = "6000",
                slippageBps = "7.1225", marketImpactBps = "2.5000", totalCostBps = "9.6225"),
            execCost("seed-exec-tm-meta-002", "tech-momentum", "META", fill(6, 7),
                arrivalPrice = "510.40", avgFillPrice = "510.00", side = Side.SELL, totalQty = "2000",
                slippageBps = "7.8370", totalCostBps = "7.8370"),

            // ── balanced-income: 4 of 6 trades (skip US30Y buy, DE10Y) ──
            execCost("seed-exec-bi-us10y-001", "balanced-income", "US10Y", fill(1, 20),
                arrivalPrice = "96.56", avgFillPrice = "96.60", side = Side.BUY, totalQty = "60000",
                slippageBps = "4.1424", totalCostBps = "4.1424"),
            execCost("seed-exec-bi-jpm-001", "balanced-income", "JPM", fill(2, 13),
                arrivalPrice = "208.20", avgFillPrice = "208.40", side = Side.BUY, totalQty = "15000",
                slippageBps = "9.6061", marketImpactBps = "3.0000", totalCostBps = "12.6061"),
            execCost("seed-exec-bi-aapl-001", "balanced-income", "AAPL", fill(4, 10),
                arrivalPrice = "187.30", avgFillPrice = "187.20", side = Side.BUY, totalQty = "7000",
                slippageBps = "-5.3390", totalCostBps = "-5.3390"),
            execCost("seed-exec-bi-us30y-002", "balanced-income", "US30Y", fill(6, 31),
                arrivalPrice = "93.15", avgFillPrice = "93.10", side = Side.SELL, totalQty = "10000",
                slippageBps = "5.3677", totalCostBps = "5.3677"),

            // ── derivatives-book: 4 of 6 trades (skip VIX-PUT, TSLA) ──
            execCost("seed-exec-db-spx-call-001", "derivatives-book", "SPX-CALL-5000", fill(1, 5),
                arrivalPrice = "41.30", avgFillPrice = "41.50", side = Side.BUY, totalQty = "2000",
                slippageBps = "48.4262", marketImpactBps = "8.0000", timingCostBps = "3.5000", totalCostBps = "59.9262"),
            execCost("seed-exec-db-spx-put-001", "derivatives-book", "SPX-PUT-4500", fill(2, 8),
                arrivalPrice = "32.80", avgFillPrice = "33.00", side = Side.BUY, totalQty = "1500",
                slippageBps = "60.9756", totalCostBps = "60.9756"),
            execCost("seed-exec-db-nvda-001", "derivatives-book", "NVDA", fill(2, 21),
                arrivalPrice = "887.00", avgFillPrice = "888.00", side = Side.BUY, totalQty = "5000",
                slippageBps = "11.2740", totalCostBps = "11.2740"),
            execCost("seed-exec-db-spx-call-002", "derivatives-book", "SPX-CALL-5000", fill(6, 12),
                arrivalPrice = "44.40", avgFillPrice = "44.20", side = Side.SELL, totalQty = "800",
                slippageBps = "45.0450", totalCostBps = "45.0450"),
        )
    }
}

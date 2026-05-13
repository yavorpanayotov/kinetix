package com.kinetix.audit.seed

import com.kinetix.audit.model.AuditEvent
import com.kinetix.audit.persistence.AuditEventRepository
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit

class DevDataSeeder(
    private val repository: AuditEventRepository,
) {
    private val log = LoggerFactory.getLogger(DevDataSeeder::class.java)

    suspend fun seed() {
        val anchor = repository.findByTradeId("seed-eq-aapl-001")
        if (anchor != null) {
            log.info("Audit seed anchor present, skipping seed")
            return
        }

        log.info("Seeding {} audit events", EVENTS.size)

        for (event in EVENTS) {
            repository.save(event)
        }

        log.info("Audit event seeding complete")
    }

    companion object {
        private val BASE_TIME = Instant.parse("2026-02-21T14:00:00Z")
        private fun day(n: Long): Instant = BASE_TIME.plus(n, ChronoUnit.DAYS)
        private fun receivedAfter(tradedAt: Instant): Instant = tradedAt.plusSeconds(1)

        private val RECEIVED_AT = receivedAfter(BASE_TIME)
        private const val TRADED_AT = "2026-02-21T14:00:00Z"

        private fun dayStr(n: Long): String = day(n).toString()
        private fun receivedAtDay(n: Long): Instant = receivedAfter(day(n))

        // Demo persona identities for realistic audit trail
        private const val TRADER_ID = "trader1"
        private const val TRADER_ROLE = "TRADER"
        private const val RISK_MGR_ID = "risk_mgr"
        private const val RISK_MGR_ROLE = "RISK_MANAGER"

        // Instrument catalogue — mirrors position-service DevDataSeeder exactly
        private data class InstrumentSpec(
            val id: String,
            val assetClass: String,
            val instrumentType: String,
            val currency: String,
            val typicalPrice: String,
            val typicalQtyMin: Int,
            val typicalQtyMax: Int,
        )

        private val BOOK_INSTRUMENTS: Map<String, List<InstrumentSpec>> = mapOf(
            // ── equity-growth (24 instruments) ──
            "equity-growth" to listOf(
                InstrumentSpec("AAPL",  "EQUITY", "CASH_EQUITY", "USD", "185.50",  500, 3000),
                InstrumentSpec("GOOGL", "EQUITY", "CASH_EQUITY", "USD", "175.20",  300, 2000),
                InstrumentSpec("MSFT",  "EQUITY", "CASH_EQUITY", "USD", "420.00",  200, 1500),
                InstrumentSpec("AMZN",  "EQUITY", "CASH_EQUITY", "USD", "205.75",  400, 2500),
                InstrumentSpec("META",  "EQUITY", "CASH_EQUITY", "USD", "505.00",  200, 1200),
                InstrumentSpec("JPM",   "EQUITY", "CASH_EQUITY", "USD", "209.00",  300, 1500),
                InstrumentSpec("BAC",   "EQUITY", "CASH_EQUITY", "USD", "39.80",   500, 3000),
                InstrumentSpec("DIS",   "EQUITY", "CASH_EQUITY", "USD", "112.50",  200, 1500),
                InstrumentSpec("KO",    "EQUITY", "CASH_EQUITY", "USD", "62.80",   300, 2000),
                InstrumentSpec("WMT",   "EQUITY", "CASH_EQUITY", "USD", "172.30",  150, 1000),
                InstrumentSpec("JNJ",   "EQUITY", "CASH_EQUITY", "USD", "158.40",  150, 1000),
                InstrumentSpec("UNH",   "EQUITY", "CASH_EQUITY", "USD", "540.60",  50,  400),
                InstrumentSpec("CRM",   "EQUITY", "CASH_EQUITY", "USD", "305.80",  100, 800),
                InstrumentSpec("ADBE",  "EQUITY", "CASH_EQUITY", "USD", "470.50",  80,  600),
                InstrumentSpec("ORCL",  "EQUITY", "CASH_EQUITY", "USD", "176.50",  200, 1200),
                InstrumentSpec("PFE",   "EQUITY", "CASH_EQUITY", "USD", "28.60",   500, 4000),
                InstrumentSpec("XOM",   "EQUITY", "CASH_EQUITY", "USD", "116.80",  200, 1200),
                InstrumentSpec("CVX",   "EQUITY", "CASH_EQUITY", "USD", "163.20",  150, 1000),
                InstrumentSpec("AAPL-C-200-20260620", "DERIVATIVE", "EQUITY_OPTION", "USD", "8.50", 50, 400),
                InstrumentSpec("AAPL-P-180-20260620", "DERIVATIVE", "EQUITY_OPTION", "USD", "6.20", 50, 400),
                InstrumentSpec("MSFT-C-450-20260620", "DERIVATIVE", "EQUITY_OPTION", "USD", "10.80", 30, 250),
                InstrumentSpec("MSFT-P-400-20260620", "DERIVATIVE", "EQUITY_OPTION", "USD", "9.50",  30, 250),
                InstrumentSpec("SPX-PUT-4500", "DERIVATIVE", "EQUITY_OPTION", "USD", "32.50", 30, 200),
                InstrumentSpec("SPX-SEP26",    "DERIVATIVE", "EQUITY_FUTURE", "USD", "5020.00", 5, 40),
            ),
            // ── tech-momentum (22 instruments) ──
            "tech-momentum" to listOf(
                InstrumentSpec("NVDA",  "EQUITY", "CASH_EQUITY", "USD", "885.00",  100,  800),
                InstrumentSpec("META",  "EQUITY", "CASH_EQUITY", "USD", "502.30",  200, 1500),
                InstrumentSpec("MSFT",  "EQUITY", "CASH_EQUITY", "USD", "421.50",  150, 1200),
                InstrumentSpec("GOOGL", "EQUITY", "CASH_EQUITY", "USD", "176.80",  300, 2000),
                InstrumentSpec("AMZN",  "EQUITY", "CASH_EQUITY", "USD", "206.00",  300, 1800),
                InstrumentSpec("TSLA",  "EQUITY", "CASH_EQUITY", "USD", "249.50",  200, 1200),
                InstrumentSpec("AMD",   "EQUITY", "CASH_EQUITY", "USD", "164.20",  200, 1500),
                InstrumentSpec("INTC",  "EQUITY", "CASH_EQUITY", "USD", "24.10",   500, 4000),
                InstrumentSpec("CRM",   "EQUITY", "CASH_EQUITY", "USD", "305.80",  100, 800),
                InstrumentSpec("ADBE",  "EQUITY", "CASH_EQUITY", "USD", "470.50",  80,  600),
                InstrumentSpec("ORCL",  "EQUITY", "CASH_EQUITY", "USD", "176.50",  200, 1200),
                InstrumentSpec("GS",    "EQUITY", "CASH_EQUITY", "USD", "495.30",  50,  400),
                InstrumentSpec("NVDA-C-950-20260620", "DERIVATIVE", "EQUITY_OPTION", "USD", "28.50", 50, 300),
                InstrumentSpec("NVDA-P-800-20260620", "DERIVATIVE", "EQUITY_OPTION", "USD", "35.20", 50, 300),
                InstrumentSpec("TSLA-C-280-20260620", "DERIVATIVE", "EQUITY_OPTION", "USD", "12.40", 50, 300),
                InstrumentSpec("TSLA-P-220-20260620", "DERIVATIVE", "EQUITY_OPTION", "USD", "13.20", 50, 300),
                InstrumentSpec("MSFT-C-450-20260620", "DERIVATIVE", "EQUITY_OPTION", "USD", "10.80", 30, 250),
                InstrumentSpec("MSFT-P-400-20260620", "DERIVATIVE", "EQUITY_OPTION", "USD", "9.50",  30, 250),
                InstrumentSpec("NDX-SEP26",     "DERIVATIVE", "EQUITY_FUTURE", "USD", "18100.00", 5, 40),
                InstrumentSpec("SPX-CALL-5200", "DERIVATIVE", "EQUITY_OPTION", "USD", "22.30", 30, 200),
                InstrumentSpec("SPX-PUT-4800",  "DERIVATIVE", "EQUITY_OPTION", "USD", "55.40", 30, 200),
                InstrumentSpec("VIX-PUT-15",    "DERIVATIVE", "EQUITY_OPTION", "USD", "3.75",  100, 800),
            ),
            // ── emerging-markets (20 instruments) ──
            "emerging-markets" to listOf(
                InstrumentSpec("BABA",   "EQUITY",       "CASH_EQUITY",    "USD", "83.20",   500, 4000),
                InstrumentSpec("TSLA",   "EQUITY",       "CASH_EQUITY",    "USD", "250.10",  200, 1500),
                InstrumentSpec("META",   "EQUITY",       "CASH_EQUITY",    "USD", "503.00",  200, 1200),
                InstrumentSpec("JPM",    "EQUITY",       "CASH_EQUITY",    "USD", "209.00",  300, 1500),
                InstrumentSpec("GS",     "EQUITY",       "CASH_EQUITY",    "USD", "495.30",  50,  400),
                InstrumentSpec("EURUSD", "FX",           "FX_SPOT",        "USD", "1.0850",  500000, 3000000),
                InstrumentSpec("GBPUSD", "FX",           "FX_SPOT",        "USD", "1.2580",  300000, 2000000),
                InstrumentSpec("USDJPY", "FX",           "FX_SPOT",        "USD", "150.20",  500000, 3000000),
                InstrumentSpec("AUDUSD", "FX",           "FX_SPOT",        "USD", "0.6580",  300000, 2000000),
                InstrumentSpec("USDCAD", "FX",           "FX_SPOT",        "USD", "1.3620",  300000, 2000000),
                InstrumentSpec("NZDUSD", "FX",           "FX_SPOT",        "USD", "0.6140",  200000, 1500000),
                InstrumentSpec("GBPUSD-3M",           "FX", "FX_FORWARD", "USD", "1.2800",  200000, 1500000),
                InstrumentSpec("EURUSD-6M",           "FX", "FX_FORWARD", "USD", "1.0880",  200000, 1500000),
                InstrumentSpec("USDJPY-3M",           "FX", "FX_FORWARD", "USD", "149.50",  200000, 1500000),
                InstrumentSpec("EURUSD-P-1.08-SEP26", "DERIVATIVE", "FX_OPTION", "USD", "2.15", 500, 3000),
                InstrumentSpec("USDJPY-C-155-SEP26",  "DERIVATIVE", "FX_OPTION", "USD", "2.80", 500, 3000),
                InstrumentSpec("DE10Y",  "FIXED_INCOME", "GOVERNMENT_BOND", "EUR", "97.80",  1000, 5000),
                InstrumentSpec("JP10Y",  "FIXED_INCOME", "GOVERNMENT_BOND", "JPY", "99.40",  1000, 5000),
                InstrumentSpec("GC",     "COMMODITY",    "COMMODITY_FUTURE","USD", "2045.60", 10,   60),
                InstrumentSpec("WTI-AUG26", "COMMODITY", "COMMODITY_FUTURE","USD", "75.50",   30,  200),
            ),
            // ── fixed-income (22 instruments) ──
            "fixed-income" to listOf(
                InstrumentSpec("US2Y",   "FIXED_INCOME", "GOVERNMENT_BOND",      "USD", "99.25",  5000, 20000),
                InstrumentSpec("US5Y",   "FIXED_INCOME", "GOVERNMENT_BOND",      "USD", "98.80",  3000, 15000),
                InstrumentSpec("US10Y",  "FIXED_INCOME", "GOVERNMENT_BOND",      "USD", "96.50",  3000, 15000),
                InstrumentSpec("US30Y",  "FIXED_INCOME", "GOVERNMENT_BOND",      "USD", "92.10",  2000, 10000),
                InstrumentSpec("DE2Y",   "FIXED_INCOME", "GOVERNMENT_BOND",      "EUR", "99.75",  2000, 10000),
                InstrumentSpec("DE10Y",  "FIXED_INCOME", "GOVERNMENT_BOND",      "EUR", "97.80",  1000, 5000),
                InstrumentSpec("UK10Y",  "FIXED_INCOME", "GOVERNMENT_BOND",      "GBP", "96.30",  1000, 5000),
                InstrumentSpec("JP10Y",  "FIXED_INCOME", "GOVERNMENT_BOND",      "JPY", "99.40",  1000, 5000),
                InstrumentSpec("JPM-BOND-2031",  "FIXED_INCOME", "CORPORATE_BOND",      "USD", "101.50", 2000, 10000),
                InstrumentSpec("AAPL-BOND-2030", "FIXED_INCOME", "CORPORATE_BOND",      "USD", "101.50", 2000, 10000),
                InstrumentSpec("GS-BOND-2029",   "FIXED_INCOME", "CORPORATE_BOND",      "USD", "103.10", 2000, 10000),
                InstrumentSpec("MSFT-BOND-2032", "FIXED_INCOME", "CORPORATE_BOND",      "USD", "100.20", 2000, 10000),
                InstrumentSpec("USD-SOFR-5Y",    "FIXED_INCOME", "INTEREST_RATE_SWAP",  "USD", "99.80",  1000, 5000),
                InstrumentSpec("USD-SOFR-10Y",   "FIXED_INCOME", "INTEREST_RATE_SWAP",  "USD", "99.70",  1000, 5000),
                InstrumentSpec("EUR-ESTR-5Y",    "FIXED_INCOME", "INTEREST_RATE_SWAP",  "EUR", "99.75",  1000, 5000),
                InstrumentSpec("JPM",    "EQUITY", "CASH_EQUITY", "USD", "209.00",  200, 1000),
                InstrumentSpec("BAC",    "EQUITY", "CASH_EQUITY", "USD", "39.80",   300, 2000),
                InstrumentSpec("GS",     "EQUITY", "CASH_EQUITY", "USD", "495.30",  50,  300),
                InstrumentSpec("MS",     "EQUITY", "CASH_EQUITY", "USD", "101.40",  200, 1000),
                InstrumentSpec("EURUSD", "FX",     "FX_SPOT",     "USD", "1.0842",  300000, 1500000),
                InstrumentSpec("GBPUSD", "FX",     "FX_SPOT",     "USD", "1.2600",  200000, 1000000),
                InstrumentSpec("EURGBP", "FX",     "FX_SPOT",     "USD", "0.8610",  200000, 1000000),
            ),
            // ── multi-asset (28 instruments) ──
            "multi-asset" to listOf(
                InstrumentSpec("AAPL",        "EQUITY",       "CASH_EQUITY",      "USD", "186.00",  200, 1500),
                InstrumentSpec("MSFT",        "EQUITY",       "CASH_EQUITY",      "USD", "418.50",  200, 1500),
                InstrumentSpec("NVDA",        "EQUITY",       "CASH_EQUITY",      "USD", "888.00",   50, 400),
                InstrumentSpec("TSLA",        "EQUITY",       "CASH_EQUITY",      "USD", "249.00",  100, 800),
                InstrumentSpec("JPM",         "EQUITY",       "CASH_EQUITY",      "USD", "209.00",  200, 1000),
                InstrumentSpec("GS",          "EQUITY",       "CASH_EQUITY",      "USD", "495.30",   50, 300),
                InstrumentSpec("UNH",         "EQUITY",       "CASH_EQUITY",      "USD", "540.60",   30, 200),
                InstrumentSpec("XOM",         "EQUITY",       "CASH_EQUITY",      "USD", "116.80",  150, 1000),
                InstrumentSpec("US10Y",       "FIXED_INCOME", "GOVERNMENT_BOND",  "USD", "96.75",   2000, 10000),
                InstrumentSpec("US30Y",       "FIXED_INCOME", "GOVERNMENT_BOND",  "USD", "92.30",   1000, 5000),
                InstrumentSpec("DE10Y",       "FIXED_INCOME", "GOVERNMENT_BOND",  "EUR", "97.80",   1000, 5000),
                InstrumentSpec("JPM-BOND-2031","FIXED_INCOME","CORPORATE_BOND",   "USD","101.50",   1000, 5000),
                InstrumentSpec("EURUSD",      "FX",           "FX_SPOT",          "USD", "1.0842",  500000, 2000000),
                InstrumentSpec("GBPUSD",      "FX",           "FX_SPOT",          "USD", "1.2600",  300000, 1500000),
                InstrumentSpec("AUDUSD",      "FX",           "FX_SPOT",          "USD", "0.6580",  200000, 1000000),
                InstrumentSpec("USDCHF",      "FX",           "FX_SPOT",          "USD", "0.8860",  200000, 1000000),
                InstrumentSpec("GC",          "COMMODITY",    "COMMODITY_FUTURE",  "USD","2045.60",  10,   80),
                InstrumentSpec("CL",          "COMMODITY",    "COMMODITY_FUTURE",  "USD", "76.80",   20, 150),
                InstrumentSpec("NG",          "COMMODITY",    "COMMODITY_FUTURE",  "USD", "3.10",    50, 400),
                InstrumentSpec("HG",          "COMMODITY",    "COMMODITY_FUTURE",  "USD", "4.28",    30, 200),
                InstrumentSpec("SPX-PUT-4500","DERIVATIVE",   "EQUITY_OPTION",     "USD", "32.50",   50,  300),
                InstrumentSpec("SPX-CALL-5000","DERIVATIVE",  "EQUITY_OPTION",     "USD", "41.50",   30, 200),
                InstrumentSpec("AAPL-C-200-20260620", "DERIVATIVE", "EQUITY_OPTION","USD", "8.50",   30, 200),
                InstrumentSpec("MSFT-C-450-20260620", "DERIVATIVE", "EQUITY_OPTION","USD", "10.80",  20, 150),
                InstrumentSpec("USD-SOFR-5Y", "FIXED_INCOME", "INTEREST_RATE_SWAP","USD", "99.80",  500, 3000),
                InstrumentSpec("SPX-SEP26",   "DERIVATIVE",   "EQUITY_FUTURE",     "USD","5020.00",  10,  60),
                InstrumentSpec("NDX-SEP26",   "DERIVATIVE",   "EQUITY_FUTURE",     "USD","18100.00",  5,  30),
                InstrumentSpec("EURUSD-6M",   "FX",           "FX_FORWARD",        "USD", "1.0880", 200000, 1000000),
            ),
            // ── macro-hedge (24 instruments) ──
            "macro-hedge" to listOf(
                InstrumentSpec("USDJPY",      "FX",           "FX_SPOT",          "USD", "149.80",  500000, 2000000),
                InstrumentSpec("EURUSD",      "FX",           "FX_SPOT",          "USD", "1.0850",  300000, 2000000),
                InstrumentSpec("GBPUSD",      "FX",           "FX_SPOT",          "USD", "1.2580",  200000, 1500000),
                InstrumentSpec("USDCHF",      "FX",           "FX_SPOT",          "USD", "0.8860",  200000, 1000000),
                InstrumentSpec("EURGBP",      "FX",           "FX_SPOT",          "USD", "0.8610",  200000, 1000000),
                InstrumentSpec("AUDUSD",      "FX",           "FX_SPOT",          "USD", "0.6580",  200000, 1000000),
                InstrumentSpec("USDJPY-3M",   "FX",           "FX_FORWARD",       "USD", "149.50",  200000, 1000000),
                InstrumentSpec("EURUSD-6M",   "FX",           "FX_FORWARD",       "USD", "1.0880",  200000, 1000000),
                InstrumentSpec("USDJPY-C-155-SEP26", "DERIVATIVE", "FX_OPTION",   "USD", "2.80",    500, 3000),
                InstrumentSpec("GC",          "COMMODITY",    "COMMODITY_FUTURE",  "USD","2040.00",   10,   60),
                InstrumentSpec("CL",          "COMMODITY",    "COMMODITY_FUTURE",  "USD",  "76.80",   30,  200),
                InstrumentSpec("SI",          "COMMODITY",    "COMMODITY_FUTURE",  "USD",  "23.10",   20,  150),
                InstrumentSpec("NG",          "COMMODITY",    "COMMODITY_FUTURE",  "USD",  "3.10",    50,  400),
                InstrumentSpec("HG",          "COMMODITY",    "COMMODITY_FUTURE",  "USD",  "4.28",    30,  200),
                InstrumentSpec("ZC",          "COMMODITY",    "COMMODITY_FUTURE",  "USD",  "4.68",    30,  200),
                InstrumentSpec("GC-C-2200-DEC26","COMMODITY", "COMMODITY_OPTION",  "USD", "45.80",   20,  150),
                InstrumentSpec("CL-P-70-DEC26",  "COMMODITY", "COMMODITY_OPTION",  "USD", "4.50",    30,  200),
                InstrumentSpec("US10Y",       "FIXED_INCOME", "GOVERNMENT_BOND",   "USD",  "96.50",  1000, 5000),
                InstrumentSpec("DE10Y",       "FIXED_INCOME", "GOVERNMENT_BOND",   "EUR",  "97.80",  1000, 5000),
                InstrumentSpec("UK10Y",       "FIXED_INCOME", "GOVERNMENT_BOND",   "GBP",  "96.30",  1000, 5000),
                InstrumentSpec("SPX-PUT-4500","DERIVATIVE",   "EQUITY_OPTION",     "USD",  "31.20",   30,  200),
                InstrumentSpec("SPX-SEP26",   "DERIVATIVE",   "EQUITY_FUTURE",     "USD","5020.00",   10,  80),
                InstrumentSpec("RTY-SEP26",   "DERIVATIVE",   "EQUITY_FUTURE",     "USD","2120.00",   10,  60),
                InstrumentSpec("WTI-AUG26",   "COMMODITY",    "COMMODITY_FUTURE",  "USD", "75.50",    30, 200),
            ),
            // ── balanced-income (22 instruments) ──
            "balanced-income" to listOf(
                InstrumentSpec("US10Y",  "FIXED_INCOME", "GOVERNMENT_BOND",      "USD", "96.60",  2000, 10000),
                InstrumentSpec("US30Y",  "FIXED_INCOME", "GOVERNMENT_BOND",      "USD", "92.30",  1000,  8000),
                InstrumentSpec("US5Y",   "FIXED_INCOME", "GOVERNMENT_BOND",      "USD", "98.80",  2000, 10000),
                InstrumentSpec("DE10Y",  "FIXED_INCOME", "GOVERNMENT_BOND",      "EUR", "97.90",  1000,  5000),
                InstrumentSpec("UK10Y",  "FIXED_INCOME", "GOVERNMENT_BOND",      "GBP", "96.30",  1000,  5000),
                InstrumentSpec("JPM-BOND-2031",  "FIXED_INCOME", "CORPORATE_BOND",      "USD","101.50", 1000, 5000),
                InstrumentSpec("AAPL-BOND-2030", "FIXED_INCOME", "CORPORATE_BOND",      "USD","101.50", 1000, 5000),
                InstrumentSpec("GS-BOND-2029",   "FIXED_INCOME", "CORPORATE_BOND",      "USD","103.10", 1000, 5000),
                InstrumentSpec("MSFT-BOND-2032", "FIXED_INCOME", "CORPORATE_BOND",      "USD","100.20", 1000, 5000),
                InstrumentSpec("USD-SOFR-5Y",    "FIXED_INCOME", "INTEREST_RATE_SWAP",  "USD", "99.80", 500, 3000),
                InstrumentSpec("USD-SOFR-10Y",   "FIXED_INCOME", "INTEREST_RATE_SWAP",  "USD", "99.70", 500, 3000),
                InstrumentSpec("EUR-ESTR-5Y",    "FIXED_INCOME", "INTEREST_RATE_SWAP",  "EUR", "99.75", 500, 3000),
                InstrumentSpec("JPM",    "EQUITY", "CASH_EQUITY", "USD", "208.40",  200, 1500),
                InstrumentSpec("BAC",    "EQUITY", "CASH_EQUITY", "USD", "39.80",   300, 2000),
                InstrumentSpec("KO",     "EQUITY", "CASH_EQUITY", "USD", "62.80",   200, 1500),
                InstrumentSpec("JNJ",    "EQUITY", "CASH_EQUITY", "USD", "158.40",  100, 800),
                InstrumentSpec("PFE",    "EQUITY", "CASH_EQUITY", "USD", "28.60",   300, 2500),
                InstrumentSpec("UNH",    "EQUITY", "CASH_EQUITY", "USD", "540.60",  30,  200),
                InstrumentSpec("XOM",    "EQUITY", "CASH_EQUITY", "USD", "116.80",  150, 1000),
                InstrumentSpec("AAPL-C-200-20260620", "DERIVATIVE", "EQUITY_OPTION", "USD", "8.50", 30, 200),
                InstrumentSpec("EURUSD", "FX",     "FX_SPOT",     "USD", "1.0860",  300000, 1500000),
                InstrumentSpec("AAPL",   "EQUITY", "CASH_EQUITY", "USD", "187.20",  200, 1200),
            ),
            // ── derivatives-book (28 instruments) ──
            "derivatives-book" to listOf(
                InstrumentSpec("SPX-CALL-5000",       "DERIVATIVE", "EQUITY_OPTION", "USD", "41.50",  100,  600),
                InstrumentSpec("SPX-CALL-5200",       "DERIVATIVE", "EQUITY_OPTION", "USD", "22.30",   50, 400),
                InstrumentSpec("SPX-PUT-4500",        "DERIVATIVE", "EQUITY_OPTION", "USD", "33.00",   80,  500),
                InstrumentSpec("SPX-PUT-4800",        "DERIVATIVE", "EQUITY_OPTION", "USD", "55.40",   50, 400),
                InstrumentSpec("AAPL-C-200-20260620", "DERIVATIVE", "EQUITY_OPTION", "USD", "8.50",   50, 300),
                InstrumentSpec("AAPL-P-180-20260620", "DERIVATIVE", "EQUITY_OPTION", "USD", "6.20",   50, 300),
                InstrumentSpec("NVDA-C-950-20260620", "DERIVATIVE", "EQUITY_OPTION", "USD", "28.50",  50, 300),
                InstrumentSpec("NVDA-P-800-20260620", "DERIVATIVE", "EQUITY_OPTION", "USD", "35.20",  50, 300),
                InstrumentSpec("MSFT-C-450-20260620", "DERIVATIVE", "EQUITY_OPTION", "USD", "10.80",  30, 250),
                InstrumentSpec("MSFT-P-400-20260620", "DERIVATIVE", "EQUITY_OPTION", "USD", "9.50",   30, 250),
                InstrumentSpec("TSLA-C-280-20260620", "DERIVATIVE", "EQUITY_OPTION", "USD", "12.40",  50, 300),
                InstrumentSpec("TSLA-P-220-20260620", "DERIVATIVE", "EQUITY_OPTION", "USD", "13.20",  50, 300),
                InstrumentSpec("GOOGL-C-190-20260620","DERIVATIVE", "EQUITY_OPTION", "USD", "6.80",   50, 300),
                InstrumentSpec("GOOGL-P-160-20260620","DERIVATIVE", "EQUITY_OPTION", "USD", "6.50",   50, 300),
                InstrumentSpec("AMZN-C-220-20260620", "DERIVATIVE", "EQUITY_OPTION", "USD", "8.60",   50, 300),
                InstrumentSpec("AMZN-P-190-20260620", "DERIVATIVE", "EQUITY_OPTION", "USD", "9.20",   50, 300),
                InstrumentSpec("GC-C-2200-DEC26",     "COMMODITY",  "COMMODITY_OPTION","USD","45.80",  20, 150),
                InstrumentSpec("CL-P-70-DEC26",       "COMMODITY",  "COMMODITY_OPTION","USD","4.50",   30, 200),
                InstrumentSpec("EURUSD-P-1.08-SEP26", "DERIVATIVE", "FX_OPTION",     "USD", "2.15",  300, 2000),
                InstrumentSpec("USDJPY-C-155-SEP26",  "DERIVATIVE", "FX_OPTION",     "USD", "2.80",  300, 2000),
                InstrumentSpec("VIX-PUT-15",          "DERIVATIVE", "EQUITY_OPTION", "USD", "3.75",  200, 1500),
                InstrumentSpec("SPX-SEP26",           "DERIVATIVE", "EQUITY_FUTURE", "USD","5020.00",  20, 150),
                InstrumentSpec("NDX-SEP26",           "DERIVATIVE", "EQUITY_FUTURE", "USD","18100.00", 5,  30),
                InstrumentSpec("RTY-SEP26",           "DERIVATIVE", "EQUITY_FUTURE", "USD","2120.00",  10,  60),
                InstrumentSpec("NVDA",                "EQUITY",     "CASH_EQUITY",   "USD","888.00",  100,  600),
                InstrumentSpec("TSLA",                "EQUITY",     "CASH_EQUITY",   "USD","249.50",  200, 1200),
                InstrumentSpec("AAPL",                "EQUITY",     "CASH_EQUITY",   "USD","186.00",  150, 1000),
                InstrumentSpec("MSFT",                "EQUITY",     "CASH_EQUITY",   "USD","420.00",  100,  800),
            ),
        )

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

        private fun personaFor(bookId: String, assetClass: String, traderToggle: Boolean): Pair<String, String> {
            return when {
                bookId == "macro-hedge" -> "risk_mgr" to "RISK_MANAGER"
                assetClass == "FX" || assetClass == "FIXED_INCOME" -> "pm1" to "PORTFOLIO_MANAGER"
                traderToggle -> "trader1" to "TRADER"
                else -> "trader2" to "TRADER"
            }
        }

        private enum class LifecycleKind { AMEND, CANCEL }

        private fun buildGeneratedAuditEvents(): List<AuditEvent> {
            // LCG identical to position-service DevDataSeeder
            var lcgState = 0x5DEECE66DL
            fun lcgNext(): Long {
                lcgState = lcgState * 6364136223846793005L + 1442695040888963407L
                return lcgState
            }
            fun nextInt(bound: Int): Int = ((lcgNext() ushr 17) % bound).toInt().let {
                if (it < 0) it + bound else it
            }
            fun nextBoolean(trueProbability: Int): Boolean = nextInt(100) < trueProbability

            fun intradaySeconds(isEuropean: Boolean): Long {
                return if (isEuropean) {
                    28800L + nextInt(10800)
                } else {
                    val bucket = nextInt(100)
                    when {
                        bucket < 35 -> 52200L + nextInt(3600)
                        bucket < 50 -> 57600L + nextInt(7200)
                        bucket < 65 -> 64800L + nextInt(7200)
                        else        -> 72000L + nextInt(3600)
                    }
                }
            }

            fun tradedAt(dayIdx: Int, isEuropean: Boolean = false): Instant {
                val dayOffset = (dayIdx - 19).toLong()
                val dayStart = BASE_TIME.plus(dayOffset, ChronoUnit.DAYS)
                    .truncatedTo(ChronoUnit.DAYS)
                return dayStart.plusSeconds(intradaySeconds(isEuropean))
            }

            val result = mutableListOf<AuditEvent>()
            val seqCounters = mutableMapOf<Pair<String, String>, Int>()
            fun nextSeq(book: String, instr: String): Int {
                val key = book to instr
                val seq = (seqCounters[key] ?: 0) + 1
                seqCounters[key] = seq
                return seq
            }

            val fxAndMacroBooks = setOf("macro-hedge", "emerging-markets", "multi-asset")
            val traderToggles = mutableMapOf<String, Boolean>()

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
                    val side = if (isBuy) "BUY" else "SELL"
                    val qtyRange = instrSpec.typicalQtyMax - instrSpec.typicalQtyMin
                    val qty = instrSpec.typicalQtyMin + nextInt(qtyRange + 1)
                    val seq = nextSeq(bookId, instrSpec.id)
                    val bookAbbrev = bookId.replace("-", "").take(2)
                    val instrAbbrev = instrSpec.id.lowercase().replace("-", "").take(10)
                    val tradeId = "seed-gen-$bookAbbrev-$instrAbbrev-${seq.toString().padStart(3, '0')}"

                    val toggle = traderToggles[bookId] ?: true
                    traderToggles[bookId] = !toggle
                    val (userId, userRole) = personaFor(bookId, instrSpec.assetClass, toggle)

                    result += AuditEvent(
                        tradeId = tradeId,
                        bookId = bookId,
                        instrumentId = instrSpec.id,
                        assetClass = instrSpec.assetClass,
                        side = side,
                        quantity = qty.toString(),
                        priceAmount = instrSpec.typicalPrice,
                        priceCurrency = instrSpec.currency,
                        tradedAt = at.toString(),
                        receivedAt = at.plusSeconds(1),
                        userId = userId,
                        userRole = userRole,
                    )
                    i++
                }
            }

            // ── Amend/cancel lifecycle events — mirrors position-service split ──
            // Phase 3 Gap 4: 8 amends + 7 cancels with proper eventType set so
            // the audit trail shows BOOKED → AMENDED / BOOKED → CANCELLED chains
            // instead of two independent BOOKED tickets that net to the result.
            data class LifecycleSpec(
                val idx: Int,
                val kind: LifecycleKind,
                val bookId: String,
                val instrId: String,
                val assetClass: String,
                val currency: String,
                val priceStr: String,
                val qty: Int,
                val side: String,
                val baseTime: Instant,
            )

            val seedSpecs = listOf(
                LifecycleSpec(1,  LifecycleKind.AMEND,  "equity-growth",    "AAPL",          "EQUITY",       "USD", "185.50",   1000, "BUY",  BASE_TIME.plus(-18, ChronoUnit.DAYS).plusSeconds(53000)),
                LifecycleSpec(2,  LifecycleKind.AMEND,  "tech-momentum",    "NVDA",          "EQUITY",       "USD", "885.00",    200, "BUY",  BASE_TIME.plus(-15, ChronoUnit.DAYS).plusSeconds(55000)),
                LifecycleSpec(3,  LifecycleKind.CANCEL, "emerging-markets", "BABA",          "EQUITY",       "USD",  "83.20",   2000, "BUY",  BASE_TIME.plus(-12, ChronoUnit.DAYS).plusSeconds(60000)),
                LifecycleSpec(4,  LifecycleKind.AMEND,  "fixed-income",     "US10Y",         "FIXED_INCOME", "USD",  "96.50",   5000, "BUY",  BASE_TIME.plus(-10, ChronoUnit.DAYS).plusSeconds(57600)),
                LifecycleSpec(5,  LifecycleKind.AMEND,  "multi-asset",      "GC",            "COMMODITY",    "USD","2045.60",     20, "BUY",  BASE_TIME.plus(-8,  ChronoUnit.DAYS).plusSeconds(63000)),
                LifecycleSpec(6,  LifecycleKind.CANCEL, "macro-hedge",      "CL",            "COMMODITY",    "USD",  "76.80",     50, "SELL", BASE_TIME.plus(-6,  ChronoUnit.DAYS).plusSeconds(64800)),
                LifecycleSpec(7,  LifecycleKind.AMEND,  "balanced-income",  "JPM",           "EQUITY",       "USD", "208.40",    300, "BUY",  BASE_TIME.plus(-14, ChronoUnit.DAYS).plusSeconds(54000)),
                LifecycleSpec(8,  LifecycleKind.CANCEL, "derivatives-book", "TSLA",          "EQUITY",       "USD", "249.50",    400, "BUY",  BASE_TIME.plus(-11, ChronoUnit.DAYS).plusSeconds(59400)),
                LifecycleSpec(9,  LifecycleKind.AMEND,  "equity-growth",    "MSFT",          "EQUITY",       "USD", "420.00",    500, "SELL", BASE_TIME.plus(-5,  ChronoUnit.DAYS).plusSeconds(70000)),
                LifecycleSpec(10, LifecycleKind.CANCEL, "tech-momentum",    "META",          "EQUITY",       "USD", "502.30",    300, "BUY",  BASE_TIME.plus(-3,  ChronoUnit.DAYS).plusSeconds(52500)),
                LifecycleSpec(11, LifecycleKind.CANCEL, "macro-hedge",      "GC",            "COMMODITY",    "USD","2040.00",     10, "BUY",  BASE_TIME.plus(-16, ChronoUnit.DAYS).plusSeconds(58000)),
                LifecycleSpec(12, LifecycleKind.AMEND,  "multi-asset",      "AAPL",          "EQUITY",       "USD", "186.00",    250, "BUY",  BASE_TIME.plus(-9,  ChronoUnit.DAYS).plusSeconds(65000)),
                LifecycleSpec(13, LifecycleKind.CANCEL, "balanced-income",  "US30Y",         "FIXED_INCOME", "USD",  "92.30",   3000, "BUY",  BASE_TIME.plus(-7,  ChronoUnit.DAYS).plusSeconds(56400)),
                LifecycleSpec(14, LifecycleKind.AMEND,  "derivatives-book", "SPX-CALL-5000", "DERIVATIVE",   "USD",  "41.50",    100, "BUY",  BASE_TIME.plus(-4,  ChronoUnit.DAYS).plusSeconds(53800)),
                LifecycleSpec(15, LifecycleKind.CANCEL, "emerging-markets", "GBPUSD",        "FX",           "USD",  "1.2580", 500000, "BUY",  BASE_TIME.plus(-13, ChronoUnit.DAYS).plusSeconds(34200)),
            )

            // Phase 3 Gap 4 expansion — additional 40 amends + 24 cancels (5 + 3
            // per book) generated deterministically from BOOK_INSTRUMENTS so that
            // the audit trail mirrors position-service trade events 1:1.
            val additionalSpecs = mutableListOf<LifecycleSpec>()
            run {
                var idx = 16
                val orderedBooks = listOf(
                    "equity-growth", "tech-momentum", "emerging-markets", "fixed-income",
                    "multi-asset", "macro-hedge", "balanced-income", "derivatives-book",
                )
                val amendsPerBook = 5
                val cancelsPerBook = 3
                fun midpointQty(spec: InstrumentSpec, bias: Int): Int {
                    val span = (spec.typicalQtyMax - spec.typicalQtyMin).coerceAtLeast(1)
                    return spec.typicalQtyMin + ((bias.toLong() and 0xFFFFFFFFL).toInt() % span)
                }
                for ((bookIdx, bookId) in orderedBooks.withIndex()) {
                    val specs = BOOK_INSTRUMENTS.getValue(bookId)
                    for (i in 0 until amendsPerBook) {
                        val instrSpec = specs[(i + bookIdx) % specs.size]
                        val dayOffset = -(1L + ((idx * 7L) % 19L))
                        val tradeTime = BASE_TIME.plus(dayOffset, ChronoUnit.DAYS)
                            .plusSeconds(40000L + ((idx * 311L) % 25000L))
                        val qty = midpointQty(instrSpec, bias = idx * 13)
                        val side = if ((idx + bookIdx) % 4 == 0) "SELL" else "BUY"
                        additionalSpecs += LifecycleSpec(
                            idx, LifecycleKind.AMEND, bookId, instrSpec.id, instrSpec.assetClass,
                            instrSpec.currency, instrSpec.typicalPrice, qty, side, tradeTime,
                        )
                        idx++
                    }
                    for (i in 0 until cancelsPerBook) {
                        val instrSpec = specs[(i + bookIdx + amendsPerBook) % specs.size]
                        val dayOffset = -(1L + ((idx * 11L) % 19L))
                        val tradeTime = BASE_TIME.plus(dayOffset, ChronoUnit.DAYS)
                            .plusSeconds(40000L + ((idx * 433L) % 25000L))
                        val qty = midpointQty(instrSpec, bias = idx * 7)
                        val side = if ((idx + bookIdx) % 3 == 0) "SELL" else "BUY"
                        additionalSpecs += LifecycleSpec(
                            idx, LifecycleKind.CANCEL, bookId, instrSpec.id, instrSpec.assetClass,
                            instrSpec.currency, instrSpec.typicalPrice, qty, side, tradeTime,
                        )
                        idx++
                    }
                }
            }
            val lifecycleSpecs = seedSpecs + additionalSpecs

            lifecycleSpecs.forEach { spec ->
                val bookAbbrev = spec.bookId.replace("-", "").take(2)
                val instrAbbrev = spec.instrId.lowercase().replace("-", "").take(10)
                val baseId = "seed-gen-ac-$bookAbbrev-$instrAbbrev-${spec.idx.toString().padStart(2, '0')}"
                val (userId, userRole) = personaFor(spec.bookId, spec.assetClass, (spec.idx - 1) % 2 == 0)

                // Original BOOKED event — common to both amend and cancel chains.
                result += AuditEvent(
                    tradeId = baseId,
                    bookId = spec.bookId,
                    instrumentId = spec.instrId,
                    assetClass = spec.assetClass,
                    side = spec.side,
                    quantity = spec.qty.toString(),
                    priceAmount = spec.priceStr,
                    priceCurrency = spec.currency,
                    tradedAt = spec.baseTime.toString(),
                    receivedAt = spec.baseTime.plusSeconds(1),
                    userId = userId,
                    userRole = userRole,
                    eventType = "TRADE_BOOKED",
                )

                when (spec.kind) {
                    LifecycleKind.AMEND -> {
                        val amendQty = (spec.qty * 105 / 100)
                        result += AuditEvent(
                            tradeId = "$baseId-amend",
                            bookId = spec.bookId,
                            instrumentId = spec.instrId,
                            assetClass = spec.assetClass,
                            side = spec.side,
                            quantity = amendQty.toString(),
                            priceAmount = spec.priceStr,
                            priceCurrency = spec.currency,
                            tradedAt = spec.baseTime.plusSeconds(180).toString(),
                            receivedAt = spec.baseTime.plusSeconds(181),
                            userId = userId,
                            userRole = userRole,
                            eventType = "TRADE_AMENDED",
                            details = "originalTradeId=$baseId",
                        )
                    }
                    LifecycleKind.CANCEL -> {
                        // Cancel transitions the SAME tradeId from LIVE → CANCELLED.
                        result += AuditEvent(
                            tradeId = baseId,
                            bookId = spec.bookId,
                            instrumentId = spec.instrId,
                            assetClass = spec.assetClass,
                            side = spec.side,
                            quantity = spec.qty.toString(),
                            priceAmount = spec.priceStr,
                            priceCurrency = spec.currency,
                            tradedAt = spec.baseTime.plusSeconds(120).toString(),
                            receivedAt = spec.baseTime.plusSeconds(121),
                            userId = userId,
                            userRole = userRole,
                            eventType = "TRADE_CANCELLED",
                        )
                    }
                }
            }

            // ── Day-trade round trips ──
            val dayTradeDay = BASE_TIME.plus(-2, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.DAYS)
            val aaplMorn = dayTradeDay.plusSeconds(53400)
            val aaplAftn = dayTradeDay.plusSeconds(72600)
            val nvdaMorn = dayTradeDay.plusSeconds(54600)
            val nvdaAftn = dayTradeDay.plusSeconds(73200)

            result += AuditEvent(
                tradeId = "seed-gen-dt-aapl-morn",
                bookId = "equity-growth", instrumentId = "AAPL", assetClass = "EQUITY",
                side = "BUY", quantity = "2000", priceAmount = "185.50", priceCurrency = "USD",
                tradedAt = aaplMorn.toString(), receivedAt = aaplMorn.plusSeconds(1),
                userId = "trader1", userRole = "TRADER",
            )
            result += AuditEvent(
                tradeId = "seed-gen-dt-aapl-aftn",
                bookId = "equity-growth", instrumentId = "AAPL", assetClass = "EQUITY",
                side = "SELL", quantity = "2000", priceAmount = "186.80", priceCurrency = "USD",
                tradedAt = aaplAftn.toString(), receivedAt = aaplAftn.plusSeconds(1),
                userId = "trader1", userRole = "TRADER",
            )
            result += AuditEvent(
                tradeId = "seed-gen-dt-nvda-morn",
                bookId = "tech-momentum", instrumentId = "NVDA", assetClass = "EQUITY",
                side = "BUY", quantity = "300", priceAmount = "885.00", priceCurrency = "USD",
                tradedAt = nvdaMorn.toString(), receivedAt = nvdaMorn.plusSeconds(1),
                userId = "trader2", userRole = "TRADER",
            )
            result += AuditEvent(
                tradeId = "seed-gen-dt-nvda-aftn",
                bookId = "tech-momentum", instrumentId = "NVDA", assetClass = "EQUITY",
                side = "SELL", quantity = "300", priceAmount = "888.50", priceCurrency = "USD",
                tradedAt = nvdaAftn.toString(), receivedAt = nvdaAftn.plusSeconds(1),
                userId = "trader2", userRole = "TRADER",
            )

            // ── 2s10s flattener ──
            val flatDay = BASE_TIME.plus(-7, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.DAYS)
                .plusSeconds(57000)
            result += AuditEvent(
                tradeId = "seed-gen-fi-us2y-flat",
                bookId = "fixed-income", instrumentId = "US2Y", assetClass = "FIXED_INCOME",
                side = "BUY", quantity = "20000", priceAmount = "99.25", priceCurrency = "USD",
                tradedAt = flatDay.toString(), receivedAt = flatDay.plusSeconds(1),
                userId = "pm1", userRole = "PORTFOLIO_MANAGER",
            )
            result += AuditEvent(
                tradeId = "seed-gen-fi-us10y-flat",
                bookId = "fixed-income", instrumentId = "US10Y", assetClass = "FIXED_INCOME",
                side = "SELL", quantity = "10000", priceAmount = "96.50", priceCurrency = "USD",
                tradedAt = flatDay.plusSeconds(60).toString(), receivedAt = flatDay.plusSeconds(61),
                userId = "pm1", userRole = "PORTFOLIO_MANAGER",
            )

            return result
        }

        val CORE_EVENTS: List<AuditEvent> = listOf(
            // ── equity-growth portfolio: 5 equity trades ──
            AuditEvent(
                tradeId = "seed-eq-aapl-001",
                bookId ="equity-growth",
                instrumentId = "AAPL",
                assetClass = "EQUITY",
                side = "BUY",
                quantity = "25000",
                priceAmount = "185.50",
                priceCurrency = "USD",
                tradedAt = TRADED_AT,
                receivedAt = RECEIVED_AT,
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-eq-googl-001",
                bookId ="equity-growth",
                instrumentId = "GOOGL",
                assetClass = "EQUITY",
                side = "BUY",
                quantity = "15000",
                priceAmount = "175.20",
                priceCurrency = "USD",
                tradedAt = TRADED_AT,
                receivedAt = RECEIVED_AT,
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-eq-msft-001",
                bookId ="equity-growth",
                instrumentId = "MSFT",
                assetClass = "EQUITY",
                side = "BUY",
                quantity = "8000",
                priceAmount = "420.00",
                priceCurrency = "USD",
                tradedAt = TRADED_AT,
                receivedAt = RECEIVED_AT,
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-eq-amzn-001",
                bookId ="equity-growth",
                instrumentId = "AMZN",
                assetClass = "EQUITY",
                side = "BUY",
                quantity = "12000",
                priceAmount = "205.75",
                priceCurrency = "USD",
                tradedAt = TRADED_AT,
                receivedAt = RECEIVED_AT,
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-eq-tsla-001",
                bookId ="equity-growth",
                instrumentId = "TSLA",
                assetClass = "EQUITY",
                side = "BUY",
                quantity = "10000",
                priceAmount = "248.30",
                priceCurrency = "USD",
                tradedAt = TRADED_AT,
                receivedAt = RECEIVED_AT,
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),

            // ── multi-asset portfolio: 6 trades across asset classes ──
            AuditEvent(
                tradeId = "seed-ma-aapl-001",
                bookId ="multi-asset",
                instrumentId = "AAPL",
                assetClass = "EQUITY",
                side = "BUY",
                quantity = "5000",
                priceAmount = "186.00",
                priceCurrency = "USD",
                tradedAt = TRADED_AT,
                receivedAt = RECEIVED_AT,
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-ma-eurusd-001",
                bookId ="multi-asset",
                instrumentId = "EURUSD",
                assetClass = "FX",
                side = "BUY",
                quantity = "10000000",
                priceAmount = "1.0842",
                priceCurrency = "USD",
                tradedAt = TRADED_AT,
                receivedAt = RECEIVED_AT,
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-ma-us10y-001",
                bookId ="multi-asset",
                instrumentId = "US10Y",
                assetClass = "FIXED_INCOME",
                side = "BUY",
                quantity = "50000",
                priceAmount = "96.75",
                priceCurrency = "USD",
                tradedAt = TRADED_AT,
                receivedAt = RECEIVED_AT,
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-ma-gc-001",
                bookId ="multi-asset",
                instrumentId = "GC",
                assetClass = "COMMODITY",
                side = "BUY",
                quantity = "100",
                priceAmount = "2045.60",
                priceCurrency = "USD",
                tradedAt = TRADED_AT,
                receivedAt = RECEIVED_AT,
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-ma-spx-put-001",
                bookId ="multi-asset",
                instrumentId = "SPX-PUT-4500",
                assetClass = "DERIVATIVE",
                side = "BUY",
                quantity = "500",
                priceAmount = "32.50",
                priceCurrency = "USD",
                tradedAt = TRADED_AT,
                receivedAt = RECEIVED_AT,
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-ma-msft-001",
                bookId ="multi-asset",
                instrumentId = "MSFT",
                assetClass = "EQUITY",
                side = "BUY",
                quantity = "7500",
                priceAmount = "418.50",
                priceCurrency = "USD",
                tradedAt = TRADED_AT,
                receivedAt = RECEIVED_AT,
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),

            // ── fixed-income portfolio: 3 fixed income trades ──
            AuditEvent(
                tradeId = "seed-fi-us2y-001",
                bookId ="fixed-income",
                instrumentId = "US2Y",
                assetClass = "FIXED_INCOME",
                side = "BUY",
                quantity = "100000",
                priceAmount = "99.25",
                priceCurrency = "USD",
                tradedAt = TRADED_AT,
                receivedAt = RECEIVED_AT,
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-fi-us10y-001",
                bookId ="fixed-income",
                instrumentId = "US10Y",
                assetClass = "FIXED_INCOME",
                side = "BUY",
                quantity = "80000",
                priceAmount = "96.50",
                priceCurrency = "USD",
                tradedAt = TRADED_AT,
                receivedAt = RECEIVED_AT,
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-fi-us30y-001",
                bookId ="fixed-income",
                instrumentId = "US30Y",
                assetClass = "FIXED_INCOME",
                side = "BUY",
                quantity = "50000",
                priceAmount = "92.10",
                priceCurrency = "USD",
                tradedAt = TRADED_AT,
                receivedAt = RECEIVED_AT,
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),

            // ── emerging-markets portfolio: 5 positions + 1 sell ──
            AuditEvent(
                tradeId = "seed-em-baba-001",
                bookId ="emerging-markets",
                instrumentId = "BABA",
                assetClass = "EQUITY",
                side = "BUY",
                quantity = "40000",
                priceAmount = "83.20",
                priceCurrency = "USD",
                tradedAt = dayStr(1),
                receivedAt = receivedAtDay(1),
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-em-tsla-001",
                bookId ="emerging-markets",
                instrumentId = "TSLA",
                assetClass = "EQUITY",
                side = "BUY",
                quantity = "6000",
                priceAmount = "250.10",
                priceCurrency = "USD",
                tradedAt = dayStr(1),
                receivedAt = receivedAtDay(1),
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-em-eurusd-001",
                bookId ="emerging-markets",
                instrumentId = "EURUSD",
                assetClass = "FX",
                side = "BUY",
                quantity = "8000000",
                priceAmount = "1.0850",
                priceCurrency = "USD",
                tradedAt = dayStr(1),
                receivedAt = receivedAtDay(1),
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-em-gbpusd-001",
                bookId ="emerging-markets",
                instrumentId = "GBPUSD",
                assetClass = "FX",
                side = "BUY",
                quantity = "5000000",
                priceAmount = "1.2580",
                priceCurrency = "USD",
                tradedAt = dayStr(2),
                receivedAt = receivedAtDay(2),
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-em-usdjpy-001",
                bookId ="emerging-markets",
                instrumentId = "USDJPY",
                assetClass = "FX",
                side = "BUY",
                quantity = "10000000",
                priceAmount = "150.20",
                priceCurrency = "USD",
                tradedAt = dayStr(2),
                receivedAt = receivedAtDay(2),
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-em-baba-002",
                bookId ="emerging-markets",
                instrumentId = "BABA",
                assetClass = "EQUITY",
                side = "SELL",
                quantity = "10000",
                priceAmount = "86.50",
                priceCurrency = "USD",
                tradedAt = dayStr(4),
                receivedAt = receivedAtDay(4),
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),

            // ── macro-hedge portfolio: 6 positions + 1 sell ──
            AuditEvent(
                tradeId = "seed-mh-usdjpy-001",
                bookId ="macro-hedge",
                instrumentId = "USDJPY",
                assetClass = "FX",
                side = "BUY",
                quantity = "15000000",
                priceAmount = "149.80",
                priceCurrency = "USD",
                tradedAt = TRADED_AT,
                receivedAt = RECEIVED_AT,
                userId = RISK_MGR_ID,
                userRole = RISK_MGR_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-mh-gc-001",
                bookId ="macro-hedge",
                instrumentId = "GC",
                assetClass = "COMMODITY",
                side = "BUY",
                quantity = "200",
                priceAmount = "2040.00",
                priceCurrency = "USD",
                tradedAt = TRADED_AT,
                receivedAt = RECEIVED_AT,
                userId = RISK_MGR_ID,
                userRole = RISK_MGR_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-mh-cl-001",
                bookId ="macro-hedge",
                instrumentId = "CL",
                assetClass = "COMMODITY",
                side = "BUY",
                quantity = "500",
                priceAmount = "76.80",
                priceCurrency = "USD",
                tradedAt = dayStr(1),
                receivedAt = receivedAtDay(1),
                userId = RISK_MGR_ID,
                userRole = RISK_MGR_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-mh-si-001",
                bookId ="macro-hedge",
                instrumentId = "SI",
                assetClass = "COMMODITY",
                side = "BUY",
                quantity = "200",
                priceAmount = "23.10",
                priceCurrency = "USD",
                tradedAt = dayStr(1),
                receivedAt = receivedAtDay(1),
                userId = RISK_MGR_ID,
                userRole = RISK_MGR_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-mh-de10y-001",
                bookId ="macro-hedge",
                instrumentId = "DE10Y",
                assetClass = "FIXED_INCOME",
                side = "BUY",
                quantity = "30000",
                priceAmount = "97.80",
                priceCurrency = "EUR",
                tradedAt = dayStr(2),
                receivedAt = receivedAtDay(2),
                userId = RISK_MGR_ID,
                userRole = RISK_MGR_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-mh-spx-put-001",
                bookId ="macro-hedge",
                instrumentId = "SPX-PUT-4500",
                assetClass = "DERIVATIVE",
                side = "BUY",
                quantity = "800",
                priceAmount = "31.20",
                priceCurrency = "USD",
                tradedAt = dayStr(2),
                receivedAt = receivedAtDay(2),
                userId = RISK_MGR_ID,
                userRole = RISK_MGR_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-mh-gc-002",
                bookId ="macro-hedge",
                instrumentId = "GC",
                assetClass = "COMMODITY",
                side = "SELL",
                quantity = "50",
                priceAmount = "2060.50",
                priceCurrency = "USD",
                tradedAt = dayStr(4),
                receivedAt = receivedAtDay(4),
                userId = RISK_MGR_ID,
                userRole = RISK_MGR_ROLE,
            ),

            // ── tech-momentum portfolio: 4 positions + 1 sell ──
            AuditEvent(
                tradeId = "seed-tm-nvda-001",
                bookId ="tech-momentum",
                instrumentId = "NVDA",
                assetClass = "EQUITY",
                side = "BUY",
                quantity = "5000",
                priceAmount = "885.00",
                priceCurrency = "USD",
                tradedAt = dayStr(2),
                receivedAt = receivedAtDay(2),
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-tm-meta-001",
                bookId ="tech-momentum",
                instrumentId = "META",
                assetClass = "EQUITY",
                side = "BUY",
                quantity = "8000",
                priceAmount = "502.30",
                priceCurrency = "USD",
                tradedAt = dayStr(2),
                receivedAt = receivedAtDay(2),
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-tm-msft-001",
                bookId ="tech-momentum",
                instrumentId = "MSFT",
                assetClass = "EQUITY",
                side = "BUY",
                quantity = "6000",
                priceAmount = "421.50",
                priceCurrency = "USD",
                tradedAt = dayStr(2),
                receivedAt = receivedAtDay(2),
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-tm-googl-001",
                bookId ="tech-momentum",
                instrumentId = "GOOGL",
                assetClass = "EQUITY",
                side = "BUY",
                quantity = "12000",
                priceAmount = "176.80",
                priceCurrency = "USD",
                tradedAt = dayStr(2),
                receivedAt = receivedAtDay(2),
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-tm-meta-002",
                bookId ="tech-momentum",
                instrumentId = "META",
                assetClass = "EQUITY",
                side = "SELL",
                quantity = "2000",
                priceAmount = "510.00",
                priceCurrency = "USD",
                tradedAt = dayStr(6),
                receivedAt = receivedAtDay(6),
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),

            // ── balanced-income portfolio: 5 positions + 1 sell ──
            AuditEvent(
                tradeId = "seed-bi-us10y-001",
                bookId ="balanced-income",
                instrumentId = "US10Y",
                assetClass = "FIXED_INCOME",
                side = "BUY",
                quantity = "60000",
                priceAmount = "96.60",
                priceCurrency = "USD",
                tradedAt = dayStr(1),
                receivedAt = receivedAtDay(1),
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-bi-us30y-001",
                bookId ="balanced-income",
                instrumentId = "US30Y",
                assetClass = "FIXED_INCOME",
                side = "BUY",
                quantity = "40000",
                priceAmount = "92.30",
                priceCurrency = "USD",
                tradedAt = dayStr(1),
                receivedAt = receivedAtDay(1),
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-bi-de10y-001",
                bookId ="balanced-income",
                instrumentId = "DE10Y",
                assetClass = "FIXED_INCOME",
                side = "BUY",
                quantity = "25000",
                priceAmount = "97.90",
                priceCurrency = "EUR",
                tradedAt = dayStr(2),
                receivedAt = receivedAtDay(2),
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-bi-jpm-001",
                bookId ="balanced-income",
                instrumentId = "JPM",
                assetClass = "EQUITY",
                side = "BUY",
                quantity = "15000",
                priceAmount = "208.40",
                priceCurrency = "USD",
                tradedAt = dayStr(2),
                receivedAt = receivedAtDay(2),
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-bi-aapl-001",
                bookId ="balanced-income",
                instrumentId = "AAPL",
                assetClass = "EQUITY",
                side = "BUY",
                quantity = "7000",
                priceAmount = "187.20",
                priceCurrency = "USD",
                tradedAt = dayStr(4),
                receivedAt = receivedAtDay(4),
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-bi-us30y-002",
                bookId ="balanced-income",
                instrumentId = "US30Y",
                assetClass = "FIXED_INCOME",
                side = "SELL",
                quantity = "10000",
                priceAmount = "93.10",
                priceCurrency = "USD",
                tradedAt = dayStr(6),
                receivedAt = receivedAtDay(6),
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),

            // ── derivatives-book portfolio: 5 positions + 1 sell ──
            AuditEvent(
                tradeId = "seed-db-spx-call-001",
                bookId ="derivatives-book",
                instrumentId = "SPX-CALL-5000",
                assetClass = "DERIVATIVE",
                side = "BUY",
                quantity = "2000",
                priceAmount = "41.50",
                priceCurrency = "USD",
                tradedAt = dayStr(1),
                receivedAt = receivedAtDay(1),
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-db-vix-put-001",
                bookId ="derivatives-book",
                instrumentId = "VIX-PUT-15",
                assetClass = "DERIVATIVE",
                side = "BUY",
                quantity = "5000",
                priceAmount = "3.75",
                priceCurrency = "USD",
                tradedAt = dayStr(1),
                receivedAt = receivedAtDay(1),
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-db-spx-put-001",
                bookId ="derivatives-book",
                instrumentId = "SPX-PUT-4500",
                assetClass = "DERIVATIVE",
                side = "BUY",
                quantity = "1500",
                priceAmount = "33.00",
                priceCurrency = "USD",
                tradedAt = dayStr(2),
                receivedAt = receivedAtDay(2),
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-db-nvda-001",
                bookId ="derivatives-book",
                instrumentId = "NVDA",
                assetClass = "EQUITY",
                side = "BUY",
                quantity = "5000",
                priceAmount = "888.00",
                priceCurrency = "USD",
                tradedAt = dayStr(2),
                receivedAt = receivedAtDay(2),
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-db-tsla-001",
                bookId ="derivatives-book",
                instrumentId = "TSLA",
                assetClass = "EQUITY",
                side = "BUY",
                quantity = "8000",
                priceAmount = "249.50",
                priceCurrency = "USD",
                tradedAt = dayStr(4),
                receivedAt = receivedAtDay(4),
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-db-spx-call-002",
                bookId ="derivatives-book",
                instrumentId = "SPX-CALL-5000",
                assetClass = "DERIVATIVE",
                side = "SELL",
                quantity = "800",
                priceAmount = "44.20",
                priceCurrency = "USD",
                tradedAt = dayStr(6),
                receivedAt = receivedAtDay(6),
                userId = RISK_MGR_ID,
                userRole = RISK_MGR_ROLE,
            ),

            // ── fixed-income book: corporate bond + interest rate swap ──
            AuditEvent(
                tradeId = "seed-fi-jpm-bond-001",
                bookId = "fixed-income",
                instrumentId = "JPM-BOND-2031",
                assetClass = "FIXED_INCOME",
                side = "BUY",
                quantity = "20000",
                priceAmount = "101.50",
                priceCurrency = "USD",
                tradedAt = dayStr(3),
                receivedAt = receivedAtDay(3),
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-fi-sofr5y-001",
                bookId = "fixed-income",
                instrumentId = "USD-SOFR-5Y",
                assetClass = "FIXED_INCOME",
                side = "BUY",
                quantity = "5000",
                priceAmount = "99.80",
                priceCurrency = "USD",
                tradedAt = dayStr(3),
                receivedAt = receivedAtDay(3),
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),

            // ── macro-hedge book: FX forward, WTI future, gold call option ──
            AuditEvent(
                tradeId = "seed-mh-gbpusd3m-001",
                bookId = "macro-hedge",
                instrumentId = "GBPUSD-3M",
                assetClass = "FX",
                side = "BUY",
                quantity = "3000000",
                priceAmount = "1.2800",
                priceCurrency = "USD",
                tradedAt = dayStr(3),
                receivedAt = receivedAtDay(3),
                userId = RISK_MGR_ID,
                userRole = RISK_MGR_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-mh-wti-aug26-001",
                bookId = "macro-hedge",
                instrumentId = "WTI-AUG26",
                assetClass = "COMMODITY",
                side = "BUY",
                quantity = "300",
                priceAmount = "75.50",
                priceCurrency = "USD",
                tradedAt = dayStr(3),
                receivedAt = receivedAtDay(3),
                userId = RISK_MGR_ID,
                userRole = RISK_MGR_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-mh-gc-call-001",
                bookId = "macro-hedge",
                instrumentId = "GC-C-2200-DEC26",
                assetClass = "COMMODITY",
                side = "BUY",
                quantity = "100",
                priceAmount = "45.80",
                priceCurrency = "USD",
                tradedAt = dayStr(5),
                receivedAt = receivedAtDay(5),
                userId = RISK_MGR_ID,
                userRole = RISK_MGR_ROLE,
            ),

            // ── emerging-markets book: FX option hedge ──
            AuditEvent(
                tradeId = "seed-em-eurusd-opt-001",
                bookId = "emerging-markets",
                instrumentId = "EURUSD-P-1.08-SEP26",
                assetClass = "DERIVATIVE",
                side = "BUY",
                quantity = "2000",
                priceAmount = "2.15",
                priceCurrency = "USD",
                tradedAt = dayStr(3),
                receivedAt = receivedAtDay(3),
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),

            // ── equity-growth book: collar structure on AAPL ──
            AuditEvent(
                tradeId = "seed-eg-aapl-col-c-001",
                bookId = "equity-growth",
                instrumentId = "AAPL-C-200-20260620",
                assetClass = "DERIVATIVE",
                side = "SELL",
                quantity = "5000",
                priceAmount = "8.50",
                priceCurrency = "USD",
                tradedAt = dayStr(5),
                receivedAt = receivedAtDay(5),
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-eg-aapl-col-p-001",
                bookId = "equity-growth",
                instrumentId = "AAPL-P-180-20260620",
                assetClass = "DERIVATIVE",
                side = "BUY",
                quantity = "5000",
                priceAmount = "6.20",
                priceCurrency = "USD",
                tradedAt = dayStr(5),
                receivedAt = receivedAtDay(5),
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),

            // ── derivatives-book: bull call spread + put spread + NVDA collar + SPX delta hedge ──
            AuditEvent(
                tradeId = "seed-db-spx-cs-001",
                bookId = "derivatives-book",
                instrumentId = "SPX-CALL-5200",
                assetClass = "DERIVATIVE",
                side = "SELL",
                quantity = "1200",
                priceAmount = "22.30",
                priceCurrency = "USD",
                tradedAt = dayStr(3),
                receivedAt = receivedAtDay(3),
                userId = RISK_MGR_ID,
                userRole = RISK_MGR_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-db-spx-ps-001",
                bookId = "derivatives-book",
                instrumentId = "SPX-PUT-4800",
                assetClass = "DERIVATIVE",
                side = "BUY",
                quantity = "1500",
                priceAmount = "55.40",
                priceCurrency = "USD",
                tradedAt = dayStr(3),
                receivedAt = receivedAtDay(3),
                userId = RISK_MGR_ID,
                userRole = RISK_MGR_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-db-spx-ps-002",
                bookId = "derivatives-book",
                instrumentId = "SPX-PUT-4500",
                assetClass = "DERIVATIVE",
                side = "SELL",
                quantity = "1500",
                priceAmount = "28.75",
                priceCurrency = "USD",
                tradedAt = dayStr(3),
                receivedAt = receivedAtDay(3),
                userId = RISK_MGR_ID,
                userRole = RISK_MGR_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-db-nvda-col-p-001",
                bookId = "derivatives-book",
                instrumentId = "NVDA-P-800-20260620",
                assetClass = "DERIVATIVE",
                side = "BUY",
                quantity = "3000",
                priceAmount = "35.20",
                priceCurrency = "USD",
                tradedAt = dayStr(4),
                receivedAt = receivedAtDay(4),
                userId = RISK_MGR_ID,
                userRole = RISK_MGR_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-db-nvda-col-c-001",
                bookId = "derivatives-book",
                instrumentId = "NVDA-C-950-20260620",
                assetClass = "DERIVATIVE",
                side = "SELL",
                quantity = "3000",
                priceAmount = "28.50",
                priceCurrency = "USD",
                tradedAt = dayStr(4),
                receivedAt = receivedAtDay(4),
                userId = RISK_MGR_ID,
                userRole = RISK_MGR_ROLE,
            ),
            AuditEvent(
                tradeId = "seed-db-spx-fut-001",
                bookId = "derivatives-book",
                instrumentId = "SPX-SEP26",
                assetClass = "DERIVATIVE",
                side = "SELL",
                quantity = "400",
                priceAmount = "5020.00",
                priceCurrency = "USD",
                tradedAt = dayStr(5),
                receivedAt = receivedAtDay(5),
                userId = RISK_MGR_ID,
                userRole = RISK_MGR_ROLE,
            ),

            // ── balanced-income book: EURUSD spot hedge for DE10Y EUR exposure ──
            AuditEvent(
                tradeId = "seed-bi-eurusd-hdg-001",
                bookId = "balanced-income",
                instrumentId = "EURUSD",
                assetClass = "FX",
                side = "SELL",
                quantity = "2500000",
                priceAmount = "1.0860",
                priceCurrency = "USD",
                tradedAt = dayStr(3),
                receivedAt = receivedAtDay(3),
                userId = TRADER_ID,
                userRole = TRADER_ROLE,
            ),
        )

        val EVENTS: List<AuditEvent> = CORE_EVENTS + buildGeneratedAuditEvents()
    }
}

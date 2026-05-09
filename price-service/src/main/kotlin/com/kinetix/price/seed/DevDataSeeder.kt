package com.kinetix.price.seed

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.PricePoint
import com.kinetix.common.model.PriceSource
import com.kinetix.common.model.Money
import com.kinetix.price.persistence.PriceRepository
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Currency

class DevDataSeeder(
    private val repository: PriceRepository,
) {
    private val log = LoggerFactory.getLogger(DevDataSeeder::class.java)

    suspend fun seed() {
        val existing = repository.findLatest(InstrumentId("AAPL"))
        if (existing != null) {
            log.info("Price data already present, skipping seed")
            return
        }

        log.info("Seeding price data for {} instruments", INSTRUMENTS.size)

        for ((instrumentId, config) in INSTRUMENTS) {
            seedInstrument(instrumentId, config)
            seedDailyCloses(instrumentId, config)
        }

        // Seed IDX-SPX with 300 daily prices for factor loading estimation
        // (OLS regression requires >= 252 trading days).
        seedBenchmarkIndex(InstrumentId("IDX-SPX"), basePrice = 5_000.0, dailyVol = 0.012)

        log.info("Price data seeding complete")
    }

    private suspend fun seedInstrument(instrumentId: InstrumentId, config: InstrumentConfig) {
        val currency = Currency.getInstance(config.currency)
        val staleOffset = STALE_OFFSET_HOURS[instrumentId.value] ?: 0L
        val latestTime = LATEST_TIME.minus(staleOffset, ChronoUnit.HOURS)
        val startPrice = config.startPrice
        val latestPrice = config.latestPrice
        val hours = 168 // 7 days of hourly data

        // Generate hourly data points interpolating from startPrice to latestPrice
        for (i in 0..hours) {
            val fraction = i.toDouble() / hours
            // Linear interpolation with small variation
            val interpolated = startPrice + (latestPrice - startPrice) * fraction
            // Add deterministic variation based on instrument + hour
            val variation = 1.0 + (instrumentId.value.hashCode() + i).rem(100) * 0.0001
            val price = BigDecimal(interpolated * variation)
                .setScale(config.scale, RoundingMode.HALF_UP)

            val timestamp = latestTime.minus((hours - i).toLong(), ChronoUnit.HOURS)
            val point = PricePoint(
                instrumentId = instrumentId,
                price = Money(price, currency),
                timestamp = timestamp,
                source = PriceSource.EXCHANGE,
            )
            repository.save(point)
        }
    }

    /**
     * Seeds 252 daily closing prices for an instrument using geometric Brownian motion
     * with a deterministic pseudo-random number generator so the series is reproducible.
     * All prices are clamped to a minimum of 0.01 to guarantee positivity.
     */
    private suspend fun seedDailyCloses(instrumentId: InstrumentId, config: InstrumentConfig) {
        val currency = Currency.getInstance(config.currency)
        val staleOffset = STALE_OFFSET_HOURS[instrumentId.value] ?: 0L
        val anchor = LATEST_TIME.minus(staleOffset, ChronoUnit.HOURS)
        val days = 252
        var price = config.startPrice
        val seed = instrumentId.value.hashCode().toLong()
        var state = seed xor 0x1A2B3C4D5E6F7A8BL // distinct LCG state from benchmark

        for (day in days downTo 0) {
            state = state * 6364136223846793005L + 1442695040888963407L
            val u = ((state ushr 17) and 0xFFFFL).toDouble() / 65535.0
            val z = (u - 0.5) * 3.46
            val dailyReturn = z * config.dailyVol + 0.0002
            price *= (1.0 + dailyReturn)
            price = price.coerceAtLeast(0.01)

            val timestamp = anchor.minus(day.toLong(), ChronoUnit.DAYS)
            val point = PricePoint(
                instrumentId = instrumentId,
                price = Money(
                    BigDecimal(price).setScale(config.scale, RoundingMode.HALF_UP),
                    currency,
                ),
                timestamp = timestamp,
                source = PriceSource.EXCHANGE,
            )
            repository.save(point)
        }
    }

    /**
     * Seeds 300 synthetic daily closing prices for a benchmark index.
     * Prices follow a geometric Brownian motion with deterministic pseudo-random
     * returns so the series is reproducible and contains sufficient history for
     * OLS factor loading estimation (requires >= 252 days).
     */
    private suspend fun seedBenchmarkIndex(
        instrumentId: InstrumentId,
        basePrice: Double,
        dailyVol: Double,
    ) {
        val currency = Currency.getInstance("USD")
        val days = 300
        var price = basePrice
        val seed = instrumentId.value.hashCode().toLong()
        var state = seed xor 0x6C62272E07BB0142L // simple LCG state

        for (day in days downTo 0) {
            // Deterministic pseudo-random return via LCG
            state = state * 6364136223846793005L + 1442695040888963407L
            val u = ((state ushr 17) and 0xFFFFL).toDouble() / 65535.0  // [0,1)
            // Box-Muller (use same state for both; acceptable for seeding)
            val z = (u - 0.5) * 3.46  // rough normal approximation
            val dailyReturn = z * dailyVol + 0.0003  // slight upward drift
            price *= (1.0 + dailyReturn)
            price = price.coerceAtLeast(1.0)

            val timestamp = LATEST_TIME.minus(day.toLong(), ChronoUnit.DAYS)
            val point = PricePoint(
                instrumentId = instrumentId,
                price = Money(BigDecimal(price).setScale(2, RoundingMode.HALF_UP), currency),
                timestamp = timestamp,
                source = PriceSource.EXCHANGE,
            )
            repository.save(point)
        }
        log.info("Seeded {} daily prices for benchmark index {}", days + 1, instrumentId.value)
    }

    internal data class InstrumentConfig(
        val currency: String,
        val startPrice: Double,
        val latestPrice: Double,
        val assetClass: AssetClass,
        val scale: Int = 2,
        val dailyVol: Double = 0.015,
    )

    companion object {
        val LATEST_TIME: Instant = Instant.parse("2026-02-22T10:00:00Z")

        // data_quality_intent: intentional_anomaly_demo
        // Gap 8 anomaly: JNJ's freshest price is held 30 hours behind the
        // platform-wide latest. The /api/v1/prices/stale endpoint surfaces
        // it; the gateway data-quality status flags it as a WARNING; the
        // risk engine substitutes the last-known price and tags the VaR
        // result with dataQualityFlag=STALE_INPUT (follow-up commit).
        val STALE_OFFSET_HOURS: Map<String, Long> = mapOf(
            "JNJ" to 30L,
        )

        val INSTRUMENT_IDS: Set<String> = setOf(
            "AAPL", "GOOGL", "MSFT", "AMZN", "TSLA",
            "EURUSD", "US2Y", "US10Y", "US30Y", "GC", "SPX-PUT-4500",
            "NVDA", "META", "JPM", "BABA",
            "GBPUSD", "USDJPY",
            "CL", "SI",
            "SPX-CALL-5000", "VIX-PUT-15",
            "DE10Y",
            // Phase 3d/3e/3f: new instruments with trades
            "SPX-PUT-4800", "SPX-CALL-5200",
            "NVDA-C-950-20260620", "NVDA-P-800-20260620",
            "AAPL-P-180-20260620", "AAPL-C-200-20260620",
            "JPM-BOND-2031", "USD-SOFR-5Y",
            "GBPUSD-3M", "WTI-AUG26", "GC-C-2200-DEC26",
            "EURUSD-P-1.08-SEP26", "SPX-SEP26",
            // Expanded universe
            "AMD", "INTC", "CRM", "ORCL", "ADBE",
            "BAC", "GS", "MS",
            "DIS", "KO", "WMT",
            "JNJ", "PFE", "UNH",
            "XOM", "CVX",
            "MSFT-C-450-20260620", "MSFT-P-400-20260620",
            "TSLA-C-280-20260620", "TSLA-P-220-20260620",
            "GOOGL-C-190-20260620", "GOOGL-P-160-20260620",
            "AMZN-C-220-20260620", "AMZN-P-190-20260620",
            "US5Y", "UK10Y", "JP10Y", "DE2Y",
            "AAPL-BOND-2030", "GS-BOND-2029", "MSFT-BOND-2032",
            "AUDUSD", "USDCAD", "USDCHF", "EURGBP", "NZDUSD",
            "EURUSD-6M", "USDJPY-3M",
            "USDJPY-C-155-SEP26",
            "USD-SOFR-10Y", "EUR-ESTR-5Y",
            "NDX-SEP26", "RTY-SEP26",
            "NG", "HG", "PL", "ZC",
            "CL-P-70-DEC26",
        )

        internal val INSTRUMENTS: Map<InstrumentId, InstrumentConfig> = mapOf(
            // Large-cap equities — dailyVol = 0.015
            InstrumentId("AAPL") to InstrumentConfig("USD", 187.10, 189.25, AssetClass.EQUITY, dailyVol = 0.015),
            InstrumentId("GOOGL") to InstrumentConfig("USD", 176.50, 178.90, AssetClass.EQUITY, dailyVol = 0.015),
            InstrumentId("MSFT") to InstrumentConfig("USD", 422.30, 425.60, AssetClass.EQUITY, dailyVol = 0.015),
            InstrumentId("AMZN") to InstrumentConfig("USD", 207.80, 210.30, AssetClass.EQUITY, dailyVol = 0.015),
            InstrumentId("JPM") to InstrumentConfig("USD", 206.50, 211.80, AssetClass.EQUITY, dailyVol = 0.015),
            // High-vol equities — dailyVol = 0.025
            InstrumentId("TSLA") to InstrumentConfig("USD", 244.50, 242.15, AssetClass.EQUITY, dailyVol = 0.025),
            InstrumentId("NVDA") to InstrumentConfig("USD", 875.00, 892.50, AssetClass.EQUITY, dailyVol = 0.025),
            InstrumentId("META") to InstrumentConfig("USD", 498.20, 508.40, AssetClass.EQUITY, dailyVol = 0.025),
            InstrumentId("BABA") to InstrumentConfig("USD", 82.40, 86.10, AssetClass.EQUITY, dailyVol = 0.025),
            // FX majors — dailyVol = 0.005 (USDJPY = 0.006)
            InstrumentId("EURUSD") to InstrumentConfig("USD", 1.0830, 1.0856, AssetClass.FX, scale = 4, dailyVol = 0.005),
            InstrumentId("GBPUSD") to InstrumentConfig("USD", 1.2550, 1.2620, AssetClass.FX, scale = 4, dailyVol = 0.005),
            InstrumentId("USDJPY") to InstrumentConfig("USD", 149.20, 150.80, AssetClass.FX, scale = 4, dailyVol = 0.006),
            // Fixed Income — government bonds
            InstrumentId("US2Y") to InstrumentConfig("USD", 99.30, 99.40, AssetClass.FIXED_INCOME, dailyVol = 0.002),
            InstrumentId("US10Y") to InstrumentConfig("USD", 96.85, 97.10, AssetClass.FIXED_INCOME, dailyVol = 0.003),
            InstrumentId("US30Y") to InstrumentConfig("USD", 92.80, 93.25, AssetClass.FIXED_INCOME, dailyVol = 0.004),
            InstrumentId("DE10Y") to InstrumentConfig("EUR", 97.50, 98.20, AssetClass.FIXED_INCOME, dailyVol = 0.003),
            // Commodities
            InstrumentId("GC") to InstrumentConfig("USD", 2038.20, 2058.40, AssetClass.COMMODITY, dailyVol = 0.012),
            InstrumentId("CL") to InstrumentConfig("USD", 75.80, 78.30, AssetClass.COMMODITY, dailyVol = 0.018),
            InstrumentId("SI") to InstrumentConfig("USD", 22.80, 23.65, AssetClass.COMMODITY, dailyVol = 0.015),
            // Derivatives / options
            InstrumentId("SPX-PUT-4500") to InstrumentConfig("USD", 30.10, 28.75, AssetClass.DERIVATIVE, dailyVol = 0.03),
            InstrumentId("SPX-CALL-5000") to InstrumentConfig("USD", 39.50, 43.80, AssetClass.DERIVATIVE, dailyVol = 0.03),
            InstrumentId("VIX-PUT-15") to InstrumentConfig("USD", 4.10, 3.60, AssetClass.DERIVATIVE, dailyVol = 0.03),
            // Phase 3d/3e/3f: new option and futures instruments
            InstrumentId("SPX-PUT-4800") to InstrumentConfig("USD", 52.40, 58.20, AssetClass.DERIVATIVE, dailyVol = 0.03),
            InstrumentId("SPX-CALL-5200") to InstrumentConfig("USD", 25.60, 23.50, AssetClass.DERIVATIVE, dailyVol = 0.03),
            InstrumentId("NVDA-C-950-20260620") to InstrumentConfig("USD", 30.20, 26.80, AssetClass.DERIVATIVE, dailyVol = 0.035),
            InstrumentId("NVDA-P-800-20260620") to InstrumentConfig("USD", 32.50, 37.50, AssetClass.DERIVATIVE, dailyVol = 0.035),
            InstrumentId("AAPL-P-180-20260620") to InstrumentConfig("USD", 5.80, 6.80, AssetClass.DERIVATIVE, dailyVol = 0.03),
            InstrumentId("AAPL-C-200-20260620") to InstrumentConfig("USD", 9.20, 7.80, AssetClass.DERIVATIVE, dailyVol = 0.03),
            InstrumentId("JPM-BOND-2031") to InstrumentConfig("USD", 101.20, 102.30, AssetClass.FIXED_INCOME, dailyVol = 0.004),
            InstrumentId("USD-SOFR-5Y") to InstrumentConfig("USD", 99.70, 99.95, AssetClass.FIXED_INCOME, dailyVol = 0.002),
            InstrumentId("GBPUSD-3M") to InstrumentConfig("USD", 1.2750, 1.2800, AssetClass.FX, scale = 4, dailyVol = 0.005),
            InstrumentId("WTI-AUG26") to InstrumentConfig("USD", 74.50, 76.20, AssetClass.COMMODITY, dailyVol = 0.018),
            InstrumentId("GC-C-2200-DEC26") to InstrumentConfig("USD", 42.30, 48.50, AssetClass.COMMODITY, dailyVol = 0.02),
            InstrumentId("EURUSD-P-1.08-SEP26") to InstrumentConfig("USD", 2.40, 1.95, AssetClass.DERIVATIVE, dailyVol = 0.025),
            InstrumentId("SPX-SEP26") to InstrumentConfig("USD", 4980.00, 5035.00, AssetClass.DERIVATIVE, dailyVol = 0.012),
            // ── Expanded equities ──
            // Semi-conductor — dailyVol = 0.025
            InstrumentId("AMD") to InstrumentConfig("USD", 158.40, 164.20, AssetClass.EQUITY, dailyVol = 0.025),
            InstrumentId("INTC") to InstrumentConfig("USD", 22.80, 24.10, AssetClass.EQUITY, dailyVol = 0.020),
            // Enterprise software — dailyVol = 0.020
            InstrumentId("CRM") to InstrumentConfig("USD", 298.50, 305.80, AssetClass.EQUITY, dailyVol = 0.020),
            InstrumentId("ORCL") to InstrumentConfig("USD", 172.30, 176.50, AssetClass.EQUITY, dailyVol = 0.015),
            InstrumentId("ADBE") to InstrumentConfig("USD", 462.80, 470.50, AssetClass.EQUITY, dailyVol = 0.020),
            // Financials — dailyVol = 0.015-0.018
            InstrumentId("BAC") to InstrumentConfig("USD", 38.20, 39.80, AssetClass.EQUITY, dailyVol = 0.015),
            InstrumentId("GS") to InstrumentConfig("USD", 482.50, 495.30, AssetClass.EQUITY, dailyVol = 0.018),
            InstrumentId("MS") to InstrumentConfig("USD", 98.60, 101.40, AssetClass.EQUITY, dailyVol = 0.018),
            // Consumer — dailyVol = 0.010-0.018
            InstrumentId("DIS") to InstrumentConfig("USD", 108.30, 112.50, AssetClass.EQUITY, dailyVol = 0.018),
            InstrumentId("KO") to InstrumentConfig("USD", 61.20, 62.80, AssetClass.EQUITY, dailyVol = 0.010),
            InstrumentId("WMT") to InstrumentConfig("USD", 168.50, 172.30, AssetClass.EQUITY, dailyVol = 0.010),
            // Healthcare — dailyVol = 0.010-0.015
            InstrumentId("JNJ") to InstrumentConfig("USD", 155.80, 158.40, AssetClass.EQUITY, dailyVol = 0.010),
            InstrumentId("PFE") to InstrumentConfig("USD", 27.40, 28.60, AssetClass.EQUITY, dailyVol = 0.015),
            InstrumentId("UNH") to InstrumentConfig("USD", 528.30, 540.60, AssetClass.EQUITY, dailyVol = 0.015),
            // Energy — dailyVol = 0.018
            InstrumentId("XOM") to InstrumentConfig("USD", 112.40, 116.80, AssetClass.EQUITY, dailyVol = 0.018),
            InstrumentId("CVX") to InstrumentConfig("USD", 158.70, 163.20, AssetClass.EQUITY, dailyVol = 0.018),
            // ── New equity options — dailyVol = 0.03 ──
            InstrumentId("MSFT-C-450-20260620") to InstrumentConfig("USD", 12.50, 10.80, AssetClass.DERIVATIVE, dailyVol = 0.03),
            InstrumentId("MSFT-P-400-20260620") to InstrumentConfig("USD", 8.20, 9.50, AssetClass.DERIVATIVE, dailyVol = 0.03),
            InstrumentId("TSLA-C-280-20260620") to InstrumentConfig("USD", 15.80, 12.40, AssetClass.DERIVATIVE, dailyVol = 0.035),
            InstrumentId("TSLA-P-220-20260620") to InstrumentConfig("USD", 10.50, 13.20, AssetClass.DERIVATIVE, dailyVol = 0.035),
            InstrumentId("GOOGL-C-190-20260620") to InstrumentConfig("USD", 8.30, 6.80, AssetClass.DERIVATIVE, dailyVol = 0.03),
            InstrumentId("GOOGL-P-160-20260620") to InstrumentConfig("USD", 5.40, 6.50, AssetClass.DERIVATIVE, dailyVol = 0.03),
            InstrumentId("AMZN-C-220-20260620") to InstrumentConfig("USD", 10.20, 8.60, AssetClass.DERIVATIVE, dailyVol = 0.03),
            InstrumentId("AMZN-P-190-20260620") to InstrumentConfig("USD", 7.80, 9.20, AssetClass.DERIVATIVE, dailyVol = 0.03),
            // ── New government bonds ──
            InstrumentId("US5Y") to InstrumentConfig("USD", 98.50, 98.80, AssetClass.FIXED_INCOME, dailyVol = 0.0025),
            InstrumentId("UK10Y") to InstrumentConfig("GBP", 95.80, 96.30, AssetClass.FIXED_INCOME, dailyVol = 0.003),
            InstrumentId("JP10Y") to InstrumentConfig("JPY", 99.20, 99.40, AssetClass.FIXED_INCOME, dailyVol = 0.002),
            InstrumentId("DE2Y") to InstrumentConfig("EUR", 99.60, 99.75, AssetClass.FIXED_INCOME, dailyVol = 0.002),
            // ── New corporate bonds ──
            InstrumentId("AAPL-BOND-2030") to InstrumentConfig("USD", 100.80, 101.50, AssetClass.FIXED_INCOME, dailyVol = 0.004),
            InstrumentId("GS-BOND-2029") to InstrumentConfig("USD", 102.20, 103.10, AssetClass.FIXED_INCOME, dailyVol = 0.005),
            InstrumentId("MSFT-BOND-2032") to InstrumentConfig("USD", 99.50, 100.20, AssetClass.FIXED_INCOME, dailyVol = 0.003),
            // ── New FX ──
            InstrumentId("AUDUSD") to InstrumentConfig("USD", 0.6520, 0.6580, AssetClass.FX, scale = 4, dailyVol = 0.006),
            InstrumentId("USDCAD") to InstrumentConfig("CAD", 1.3580, 1.3620, AssetClass.FX, scale = 4, dailyVol = 0.005),
            InstrumentId("USDCHF") to InstrumentConfig("CHF", 0.8820, 0.8860, AssetClass.FX, scale = 4, dailyVol = 0.005),
            InstrumentId("EURGBP") to InstrumentConfig("GBP", 0.8580, 0.8610, AssetClass.FX, scale = 4, dailyVol = 0.004),
            InstrumentId("NZDUSD") to InstrumentConfig("USD", 0.6080, 0.6140, AssetClass.FX, scale = 4, dailyVol = 0.007),
            // ── New FX forwards ──
            InstrumentId("EURUSD-6M") to InstrumentConfig("USD", 1.0860, 1.0880, AssetClass.FX, scale = 4, dailyVol = 0.005),
            InstrumentId("USDJPY-3M") to InstrumentConfig("JPY", 148.80, 149.50, AssetClass.FX, scale = 4, dailyVol = 0.006),
            // ── New FX option ──
            InstrumentId("USDJPY-C-155-SEP26") to InstrumentConfig("USD", 3.20, 2.80, AssetClass.DERIVATIVE, dailyVol = 0.025),
            // ── New IRS ──
            InstrumentId("USD-SOFR-10Y") to InstrumentConfig("USD", 99.40, 99.70, AssetClass.FIXED_INCOME, dailyVol = 0.003),
            InstrumentId("EUR-ESTR-5Y") to InstrumentConfig("EUR", 99.50, 99.75, AssetClass.FIXED_INCOME, dailyVol = 0.002),
            // ── New equity futures ──
            InstrumentId("NDX-SEP26") to InstrumentConfig("USD", 17800.00, 18100.00, AssetClass.DERIVATIVE, dailyVol = 0.014),
            InstrumentId("RTY-SEP26") to InstrumentConfig("USD", 2080.00, 2120.00, AssetClass.DERIVATIVE, dailyVol = 0.016),
            // ── New commodity futures ──
            InstrumentId("NG") to InstrumentConfig("USD", 2.85, 3.10, AssetClass.COMMODITY, dailyVol = 0.025),
            InstrumentId("HG") to InstrumentConfig("USD", 4.15, 4.28, AssetClass.COMMODITY, dailyVol = 0.015),
            InstrumentId("PL") to InstrumentConfig("USD", 980.00, 1005.00, AssetClass.COMMODITY, dailyVol = 0.015),
            InstrumentId("ZC") to InstrumentConfig("USD", 4.52, 4.68, AssetClass.COMMODITY, dailyVol = 0.018),
            // ── New commodity option ──
            InstrumentId("CL-P-70-DEC26") to InstrumentConfig("USD", 3.80, 4.50, AssetClass.COMMODITY, dailyVol = 0.02),
        )
    }
}

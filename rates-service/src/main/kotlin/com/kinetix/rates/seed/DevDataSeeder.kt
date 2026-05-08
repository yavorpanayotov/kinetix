package com.kinetix.rates.seed

import com.kinetix.common.demo.CurveAndVolDerivations
import com.kinetix.common.demo.CurveDefinition
import com.kinetix.common.demo.DemoTape
import com.kinetix.common.demo.YieldCurveSnapshot
import com.kinetix.common.model.CurvePoint
import com.kinetix.common.model.ForwardCurve
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.RateSource
import com.kinetix.common.model.RiskFreeRate
import com.kinetix.common.model.Tenor
import com.kinetix.common.model.YieldCurve
import com.kinetix.rates.persistence.ForwardCurveRepository
import com.kinetix.rates.persistence.RiskFreeRateRepository
import com.kinetix.rates.persistence.YieldCurveRepository
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import java.util.Currency

class DevDataSeeder(
    private val yieldCurveRepository: YieldCurveRepository,
    private val riskFreeRateRepository: RiskFreeRateRepository,
    private val forwardCurveRepository: ForwardCurveRepository,
    private val derivations: CurveAndVolDerivations = SHARED_DERIVATIONS,
    private val curveCurrencies: List<String> = listOf("USD", "EUR", "GBP", "JPY"),
) {
    private val log = LoggerFactory.getLogger(DevDataSeeder::class.java)

    suspend fun seed() {
        val existing = yieldCurveRepository.findLatest("USD")
        if (existing != null) {
            log.info("Rates data already present, skipping seed")
            return
        }

        log.info("Seeding rates data: 252 daily snapshots × {} currencies", curveCurrencies.size)

        seedYieldCurveHistory()
        seedRiskFreeRates()
        seedForwardCurves()

        log.info("Rates data seeding complete")
    }

    private suspend fun seedYieldCurveHistory() {
        var totalSnapshots = 0
        for (currency in curveCurrencies) {
            val snapshots = derivations.yieldCurveSnapshots(currency)
            for (snap in snapshots) {
                yieldCurveRepository.save(snap.toYieldCurve())
                totalSnapshots++
            }
        }
        log.info("Seeded {} yield curve snapshots across {} currencies", totalSnapshots, curveCurrencies.size)
    }

    private suspend fun seedRiskFreeRates() {
        for ((config, rate) in RISK_FREE_RATE_DATA) {
            val riskFreeRate = RiskFreeRate(
                currency = Currency.getInstance(config.first),
                tenor = config.second,
                rate = rate,
                asOfDate = AS_OF,
                source = RateSource.CENTRAL_BANK,
            )
            riskFreeRateRepository.save(riskFreeRate)
        }
        log.info("Seeded {} risk-free rates", RISK_FREE_RATE_DATA.size)
    }

    private suspend fun seedForwardCurves() {
        for ((instrumentId, config) in FORWARD_CURVE_DATA) {
            val points = FORWARD_CURVE_TENORS.zip(config.values).map { (tenor, value) ->
                CurvePoint(tenor = tenor, value = value)
            }
            val curve = ForwardCurve(
                instrumentId = InstrumentId(instrumentId),
                assetClass = config.assetClass,
                points = points,
                asOfDate = AS_OF,
                source = RateSource.INTERNAL,
            )
            forwardCurveRepository.save(curve)
        }
        log.info("Seeded {} forward curves", FORWARD_CURVE_DATA.size)
    }

    internal data class ForwardCurveConfig(
        val assetClass: String,
        val values: List<Double>,
    )

    companion object {
        val AS_OF: Instant = Instant.parse("2026-02-22T10:00:00Z")

        // Phase 0 shared synthesis: rates, vol, prices, correlations, P&L all read from
        // the same DemoTape so consistency checks reconcile.
        internal val SHARED_TAPE: DemoTape = DemoTape()
        internal val SHARED_DERIVATIONS: CurveAndVolDerivations = CurveAndVolDerivations(SHARED_TAPE)

        // Kept for RatesFeedSimulator and legacy seeding paths; values align with
        // the tape's day-0 snapshot for the supported tenor/currency set.
        internal val YIELD_CURVE_TENORS: List<(BigDecimal) -> Tenor> = listOf(
            Tenor::overnight,
            Tenor::oneWeek,
            Tenor::oneMonth,
            Tenor::threeMonths,
            Tenor::sixMonths,
            Tenor::oneYear,
            Tenor::twoYears,
            Tenor::fiveYears,
            Tenor::tenYears,
            Tenor::thirtyYears,
        )

        internal val YIELD_CURVE_DATA: Map<String, List<BigDecimal>> = mapOf(
            "USD" to listOf(
                BigDecimal("0.0450"), BigDecimal("0.0452"), BigDecimal("0.0455"),
                BigDecimal("0.0458"), BigDecimal("0.0462"), BigDecimal("0.0465"),
                BigDecimal("0.0468"), BigDecimal("0.0472"), BigDecimal("0.0476"),
                BigDecimal("0.0480"),
            ),
            "EUR" to listOf(
                BigDecimal("0.0300"), BigDecimal("0.0302"), BigDecimal("0.0305"),
                BigDecimal("0.0308"), BigDecimal("0.0312"), BigDecimal("0.0315"),
                BigDecimal("0.0320"), BigDecimal("0.0328"), BigDecimal("0.0335"),
                BigDecimal("0.0340"),
            ),
        )

        internal val RISK_FREE_RATE_DATA: Map<Pair<String, String>, Double> = mapOf(
            ("USD" to "3M") to 4.55,
            ("EUR" to "3M") to 3.05,
        )

        internal val FORWARD_CURVE_TENORS = listOf("1M", "3M", "6M", "1Y", "2Y")

        internal val FORWARD_CURVE_DATA: Map<String, ForwardCurveConfig> = mapOf(
            "EURUSD" to ForwardCurveConfig("FX", listOf(1.0858, 1.0865, 1.0878, 1.0905, 1.0960)),
            "GBPUSD" to ForwardCurveConfig("FX", listOf(1.2625, 1.2638, 1.2660, 1.2710, 1.2800)),
            "USDJPY" to ForwardCurveConfig("FX", listOf(150.60, 150.20, 149.50, 148.10, 145.50)),
            "GC" to ForwardCurveConfig("COMMODITY", listOf(2060.5, 2065.8, 2074.2, 2092.0, 2128.5)),
            "CL" to ForwardCurveConfig("COMMODITY", listOf(78.10, 77.50, 76.30, 74.20, 70.80)),
            "SI" to ForwardCurveConfig("COMMODITY", listOf(23.70, 23.82, 23.98, 24.30, 24.95)),
        )

        private fun YieldCurveSnapshot.toYieldCurve(): YieldCurve {
            val tenorFactories = listOf<(BigDecimal) -> Tenor>(
                Tenor::overnight, Tenor::oneWeek, Tenor::oneMonth, Tenor::threeMonths, Tenor::sixMonths,
                Tenor::oneYear, Tenor::twoYears, Tenor::fiveYears, Tenor::tenYears, Tenor::thirtyYears,
            )
            require(rates.size == tenorFactories.size) {
                "Snapshot has ${rates.size} rates but YieldCurve expects ${tenorFactories.size}"
            }
            val tenors = tenorFactories.zip(rates.toList()).map { (factory, rate) ->
                factory(
                    rate.coerceAtLeast(0.0)
                        .toBigDecimal(MathContext.DECIMAL128)
                        .setScale(6, RoundingMode.HALF_UP),
                )
            }
            return YieldCurve(
                currency = Currency.getInstance(currency),
                asOf = date.atStartOfDay(ZoneOffset.UTC).toInstant(),
                tenors = tenors,
                curveId = currency,
                source = RateSource.CENTRAL_BANK,
            )
        }
    }
}

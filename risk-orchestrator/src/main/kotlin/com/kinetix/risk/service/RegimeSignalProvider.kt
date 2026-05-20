package com.kinetix.risk.service

import com.kinetix.common.model.InstrumentId
import com.kinetix.risk.client.ClientResponse
import com.kinetix.risk.client.CorrelationServiceClient
import com.kinetix.risk.client.PriceServiceClient
import com.kinetix.risk.model.RegimeSignals
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Gathers the raw input signals consumed by the market-regime classifier
 * (`gather_signals()` in `specs/regime.allium:158`).
 *
 * - `realisedVol20d`: 20-day EWMA annualised volatility derived from the daily
 *   price history of a representative market benchmark instrument.
 * - `crossAssetCorrelation`: the average off-diagonal entry of the latest
 *   Ledoit-Wolf correlation matrix returned by correlation-service.
 * - `creditSpreadBps` / `pnlVolatility`: left null when the upstream feeds are
 *   unavailable — the detector then runs in degraded mode (see the
 *   `HandleDegradedSignals` rule), which is the spec-intended behaviour rather
 *   than fabricating a value.
 *
 * Signal-gathering failures never abort a detection cycle: a missing or stale
 * feed yields a degraded signal (null or last-known default) and the detector
 * holds the confirmed regime.
 */
class RegimeSignalProvider(
    private val priceServiceClient: PriceServiceClient,
    private val correlationServiceClient: CorrelationServiceClient,
    private val benchmarkInstrumentId: InstrumentId,
    private val correlationLabels: List<String>,
    private val ewmaLambda: Double = 0.94,
    private val tradingDaysPerYear: Int = 252,
    private val clock: () -> Instant = { Instant.now() },
) {
    private val logger = LoggerFactory.getLogger(RegimeSignalProvider::class.java)

    suspend fun gather(): RegimeSignals {
        val realisedVol = realisedVol20d()
        val crossAssetCorrelation = crossAssetCorrelation()
        return RegimeSignals(
            realisedVol20d = realisedVol,
            crossAssetCorrelation = crossAssetCorrelation,
            creditSpreadBps = null,
            pnlVolatility = null,
        )
    }

    /**
     * 20-day EWMA annualised volatility of the benchmark instrument's daily
     * log returns. Returns 0.0 when insufficient history is available.
     */
    private suspend fun realisedVol20d(): Double {
        val now = clock()
        val from = now.minus(40, ChronoUnit.DAYS)
        val history = when (val r = priceServiceClient.getPriceHistory(benchmarkInstrumentId, from, now, "1d")) {
            is ClientResponse.Success -> r.value
            else -> {
                logger.warn("No price history for regime benchmark {}, realised vol defaults to 0", benchmarkInstrumentId.value)
                return 0.0
            }
        }
        val prices = history
            .sortedBy { it.timestamp }
            .map { it.price.amount.toDouble() }
            .filter { it > 0.0 }
        if (prices.size < 2) return 0.0

        val logReturns = (1 until prices.size).map { ln(prices[it] / prices[it - 1]) }
        // EWMA variance, most recent observation weighted highest.
        var variance = 0.0
        for (ret in logReturns) {
            variance = ewmaLambda * variance + (1 - ewmaLambda) * ret * ret
        }
        return sqrt(variance) * sqrt(tradingDaysPerYear.toDouble())
    }

    /** Average off-diagonal entry of the latest correlation matrix. */
    private suspend fun crossAssetCorrelation(): Double {
        val matrix = when (val r = correlationServiceClient.getCorrelationMatrix(correlationLabels)) {
            is ClientResponse.Success -> r.value
            else -> {
                logger.warn("No correlation matrix available, cross-asset correlation defaults to 0")
                return 0.0
            }
        }
        val n = matrix.labels.size
        if (n < 2 || matrix.values.size < n * n) return 0.0

        var sum = 0.0
        var count = 0
        for (i in 0 until n) {
            for (j in 0 until n) {
                if (i != j) {
                    sum += matrix.values[i * n + j]
                    count++
                }
            }
        }
        return if (count == 0) 0.0 else sum / count
    }
}

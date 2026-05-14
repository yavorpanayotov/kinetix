package com.kinetix.correlation.seed

import com.kinetix.common.demo.DemoTape
import com.kinetix.common.model.CorrelationMatrix
import com.kinetix.common.model.EstimationMethod
import com.kinetix.correlation.persistence.CorrelationMatrixRepository
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Seeds the initial correlation matrix from the shared [DemoTape], so demo correlations
 * are *derived* from the same synthesized returns the rest of the platform sees rather
 * than from hand-coded constants. The regime calendar baked into the tape drives any
 * stress-window spikes automatically — the realised Pearson correlation of log-returns
 * already reflects the elevated co-movement during the stress regimes.
 *
 * See `docs/plans/demo-follow-up.md` PR 1, item 1.
 */
class DevDataSeeder(
    private val correlationMatrixRepository: CorrelationMatrixRepository,
    private val tape: DemoTape = DemoTape(),
) {
    private val log = LoggerFactory.getLogger(DevDataSeeder::class.java)

    suspend fun seed() {
        val existing = correlationMatrixRepository.findLatest(listOf("AAPL", "MSFT"), WINDOW_DAYS)
        if (existing != null) {
            log.info("Correlation data already present, skipping seed")
            return
        }

        log.info("Seeding correlation matrix for {} instruments (derived from DemoTape)", LABELS.size)

        val matrix = CorrelationMatrix(
            labels = LABELS,
            values = correlationValues(),
            windowDays = WINDOW_DAYS,
            asOfDate = AS_OF,
            method = EstimationMethod.HISTORICAL,
        )
        correlationMatrixRepository.save(matrix)

        log.info("Correlation matrix seeding complete")
    }

    /**
     * Row-major pairwise realised correlation matrix of log-returns over the
     * tape's most recent [WINDOW_DAYS] window. Symmetric with a unit diagonal by
     * construction (see [DemoTape.realisedCorrelation]).
     */
    fun correlationValues(): List<Double> {
        val n = LABELS.size
        val matrix = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            matrix[i][i] = 1.0
            for (j in i + 1 until n) {
                val corr = tape.realisedCorrelation(LABELS[i], LABELS[j], endDay = 0, window = WINDOW_DAYS)
                matrix[i][j] = corr
                matrix[j][i] = corr
            }
        }
        return matrix.flatMap { it.toList() }
    }

    companion object {
        val AS_OF: Instant = Instant.parse("2026-02-22T10:00:00Z")
        const val WINDOW_DAYS = 252

        val LABELS: List<String> = listOf(
            "AAPL", "AAPL-BOND-2030", "AAPL-C-200-20260620", "AAPL-P-180-20260620",
            "ADBE", "AMD", "AMZN", "AMZN-C-220-20260620", "AMZN-P-190-20260620",
            "AUDUSD", "BABA", "BAC", "CL", "CL-P-70-DEC26", "CRM", "CVX",
            "DE10Y", "DE2Y", "DIS",
            "EUR-ESTR-5Y", "EURGBP", "EURUSD", "EURUSD-6M", "EURUSD-P-1.08-SEP26",
            "GBPUSD", "GBPUSD-3M", "GC", "GC-C-2200-DEC26",
            "GOOGL", "GOOGL-C-190-20260620", "GOOGL-P-160-20260620",
            "GS", "GS-BOND-2029",
            "HG", "INTC",
            "JNJ", "JP10Y", "JPM", "JPM-BOND-2031",
            "KO",
            "META", "MS", "MSFT", "MSFT-BOND-2032", "MSFT-C-450-20260620", "MSFT-P-400-20260620",
            "NDX-SEP26", "NG", "NVDA", "NVDA-C-950-20260620", "NVDA-P-800-20260620",
            "NZDUSD",
            "ORCL",
            "PFE", "PL",
            "RTY-SEP26",
            "SI", "SPX-CALL-5000", "SPX-CALL-5200", "SPX-PUT-4500", "SPX-PUT-4800", "SPX-SEP26",
            "TSLA", "TSLA-C-280-20260620", "TSLA-P-220-20260620",
            "UK10Y", "UNH",
            "US10Y", "US2Y", "US30Y", "US5Y",
            "USD-SOFR-10Y", "USD-SOFR-5Y", "USDCAD", "USDCHF",
            "USDJPY", "USDJPY-3M", "USDJPY-C-155-SEP26",
            "VIX-PUT-15", "WMT", "WTI-AUG26",
            "XOM", "ZC",
        )
    }
}

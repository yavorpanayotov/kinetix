package com.kinetix.common.demo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.ints.shouldBeInRange
import org.slf4j.LoggerFactory
import kotlin.math.ln

/**
 * Acceptance test for the demo tape's calibration as a backtestable P&L source.
 *
 * Runs a rolling-window historical VaR backtest over the seeded 252-day return series
 * for AAPL (a representative high-quality equity series in [DemoTapeUniverse]) and
 * verifies the exception count falls inside the expected band for a 99%-confidence
 * VaR. With a 90-day VaR estimation window over the 252-day tape we have 161
 * backtest observations; the expected exception count at 99% confidence is
 * 161 * 0.01 ≈ 1.61. The bound `[1, 5]` is a 99% Kupiec POF acceptance band — wide
 * enough to absorb the Student-t fat tails (df=5) and the embedded stress regime in
 * the tape, tight enough to catch gross mis-calibration in either direction.
 *
 * The 90-day VaR window is the largest standard estimation window where there is
 * still a usable backtest tail in 252 days of data; it also keeps the empirical
 * tail quantile stable across regime transitions on the tape.
 *
 * Replaces the tautology placeholder previously in `DemoTapeTest` (see PR 1 item 4
 * in `docs/plans/demo-follow-up.md`).
 *
 * Note: the upstream `seeded P&L series` from `risk-orchestrator`'s `DevDataSeeder`
 * is currently still hardcoded constants (audit gap #1, scope item #3 in PR 1).
 * Once that gap closes, this test should be extended to assert Kupiec also passes
 * on the book-level attributed P&L. Today, the tape's instrument return series is
 * the source-of-truth P&L generator the rest of the platform derives from.
 */
class KupiecBacktestAcceptanceTest : FunSpec({

    val logger = LoggerFactory.getLogger(KupiecBacktestAcceptanceTest::class.java)

    val tape = DemoTape()
    val symbol = "AAPL"
    val confidence = 0.99
    val varWindow = 90
    // Reverse-chronological indexing: day 0 = most recent. A 90-day VaR window
    // ending at day d uses returns[d..d+89]. We test the return at day d-1
    // (the *next* day after the window's end-of-day, advancing forward in real time)
    // against -VaR. Loop over all days where both window and next-day return exist.
    val backtestDays = RegimeCalendar.DAYS - varWindow - 1

    test("99% historical VaR exception count over the seeded tape is in [1, 5]") {
        var exceptions = 0
        var total = 0
        // Iterate over each backtest day. endDay slides from the oldest valid window
        // forward in real time (i.e. endDay decreasing in reverse-chrono indexing).
        for (endDay in backtestDays downTo 1) {
            val varEstimate = tape.historicalVaR(
                symbol = symbol,
                confidence = confidence,
                window = varWindow,
                endDay = endDay,
            )
            // Realised return on the day immediately following the window — i.e. the
            // next chronological day, which in reverse-chrono indexing is endDay - 1.
            val realised = tape.dailyReturn(symbol, endDay - 1)
            if (realised < -varEstimate) {
                exceptions++
            }
            total++
        }

        val expectedRate = 1.0 - confidence
        val observedRate = exceptions.toDouble() / total.toDouble()
        val kupiecLR = kupiecPofStatistic(exceptions = exceptions, total = total, p = expectedRate)
        logger.info(
            "Kupiec POF backtest: symbol={} total={} exceptions={} observed_rate={} expected_rate={} LR_statistic={}",
            symbol,
            total,
            exceptions,
            "%.4f".format(observedRate),
            "%.4f".format(expectedRate),
            "%.4f".format(kupiecLR),
        )

        // Assert the count band — tight enough to catch gross mis-calibration in either
        // direction (zero exceptions ⇒ VaR too conservative; >5 ⇒ VaR too loose).
        exceptions shouldBeInRange 1..5
        // Sanity: total backtest sample size is what we expected (~161 days for a
        // 90-day VaR window over a 252-day tape).
        total shouldBeInRange 155..165
    }

    test("Kupiec POF likelihood-ratio statistic is finite and non-negative") {
        // The LR statistic must always be non-negative (the alternative-hypothesis
        // log-likelihood is at least as good as the null). Log-likelihood arithmetic
        // sometimes produces tiny negatives from float roundoff — clamp at -1e-9.
        val lrSingleException = kupiecPofStatistic(exceptions = 1, total = 100, p = 0.01)
        lrSingleException.shouldBeBetween(0.0, 5.0, 0.0)

        val lrManyExceptions = kupiecPofStatistic(exceptions = 25, total = 100, p = 0.01)
        // 25% observed vs 1% expected over 100 trials is a massive rejection.
        lrManyExceptions.shouldBeBetween(40.0, 200.0, 0.0)
    }
})

/**
 * Kupiec's Proportion-of-Failures (POF) likelihood-ratio test statistic.
 *
 * Under the null hypothesis that the true exception rate equals the configured
 * VaR level `p`, the statistic is asymptotically chi-squared with one degree of
 * freedom. Reject the null at 95% if `LR > 3.841`.
 *
 * LR = -2 * ln( [(1-p)^(T-x) * p^x] / [(1-x/T)^(T-x) * (x/T)^x] )
 */
internal fun kupiecPofStatistic(exceptions: Int, total: Int, p: Double): Double {
    require(total > 0) { "total must be > 0" }
    require(exceptions in 0..total) { "exceptions must be in 0..total" }
    require(p in 0.0..1.0) { "p must be in [0,1]" }
    if (exceptions == 0) {
        // x=0: the (x/T)^x term is 1 by convention; only the (1-p)^T / 1 piece remains.
        return -2.0 * (total.toDouble() * ln(1.0 - p))
    }
    if (exceptions == total) {
        return -2.0 * (total.toDouble() * ln(p))
    }
    val pHat = exceptions.toDouble() / total.toDouble()
    val lnNull = (total - exceptions).toDouble() * ln(1.0 - p) + exceptions.toDouble() * ln(p)
    val lnAlt = (total - exceptions).toDouble() * ln(1.0 - pHat) + exceptions.toDouble() * ln(pHat)
    val lr = -2.0 * (lnNull - lnAlt)
    // Clamp tiny negatives from float roundoff.
    return if (lr < 0.0 && lr > -1e-9) 0.0 else lr
}

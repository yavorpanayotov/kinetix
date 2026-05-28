package com.kinetix.price.outlier

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import kotlin.math.abs

/**
 * Flag a new price as an intraday outlier when it differs from the
 * prior mid by more than [thresholdPct] (default 10%). Emits a
 * Micrometer counter `price.outlier.flagged.count` so the platform
 * can alert on sudden spikes in outlier rates (a flag here is almost
 * always a bad tick that operations needs to verify).
 *
 * A zero or negative prior mid is treated as "no reference" and never
 * flags — the first tick of the day, or a recovery after a halt,
 * shouldn't trigger a false alarm.
 */
class IntradayOutlierDetector(
    registry: MeterRegistry,
    private val thresholdPct: Double = 0.10,
) {
    private val counter: Counter = Counter.builder(METRIC_NAME)
        .description("Number of intraday prices flagged as outliers")
        .register(registry)

    fun check(instrumentId: String, priorMid: Double, newPrice: Double): Boolean {
        if (priorMid <= 0.0) return false
        val movePct = abs(newPrice - priorMid) / priorMid
        val isOutlier = movePct >= thresholdPct
        if (isOutlier) counter.increment()
        return isOutlier
    }

    companion object {
        const val METRIC_NAME = "price.outlier.flagged.count"
    }
}

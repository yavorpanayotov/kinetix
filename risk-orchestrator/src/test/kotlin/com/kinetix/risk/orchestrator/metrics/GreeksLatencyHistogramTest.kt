package com.kinetix.risk.orchestrator.metrics

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration

/**
 * Greeks compute latency is heterogeneous: a fixed-income book with
 * 10k positions takes much longer than a flat equity-only book. A
 * single aggregate timer hides that — the histogram tagged with
 * asset_class lets the platform alert on the slow tier without false
 * alarms from the fast one. Histogram name:
 * `risk.orchestrator.greeks.compute.latency`.
 */
class GreeksLatencyHistogramTest : FunSpec({

    test("records a sample under the asset_class tag") {
        val registry = SimpleMeterRegistry()
        val histogram = GreeksLatencyHistogram(registry)
        histogram.recordSample(assetClass = "FIXED_INCOME", duration = Duration.ofMillis(120))
        val timer = registry.find(GreeksLatencyHistogram.METRIC_NAME)
            .tag("asset_class", "FIXED_INCOME").timer()
        timer!!.count() shouldBe 1L
        timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) shouldBeGreaterThan 119.0
    }

    test("samples in different asset classes register under different timers") {
        val registry = SimpleMeterRegistry()
        val histogram = GreeksLatencyHistogram(registry)
        histogram.recordSample("FIXED_INCOME", Duration.ofMillis(120))
        histogram.recordSample("EQUITY", Duration.ofMillis(20))
        val timers = registry.find(GreeksLatencyHistogram.METRIC_NAME).timers()
        timers.size shouldBe 2
    }

    test("repeated samples for the same asset_class accumulate in one timer") {
        val registry = SimpleMeterRegistry()
        val histogram = GreeksLatencyHistogram(registry)
        repeat(5) { histogram.recordSample("EQUITY", Duration.ofMillis(50)) }
        val timer = registry.find(GreeksLatencyHistogram.METRIC_NAME)
            .tag("asset_class", "EQUITY").timer()
        timer!!.count() shouldBe 5L
    }

    test("zero-duration sample is recorded (defensive against trivial books)") {
        val registry = SimpleMeterRegistry()
        val histogram = GreeksLatencyHistogram(registry)
        histogram.recordSample("EQUITY", Duration.ZERO)
        val timer = registry.find(GreeksLatencyHistogram.METRIC_NAME)
            .tag("asset_class", "EQUITY").timer()
        timer!!.count() shouldBe 1L
    }
})

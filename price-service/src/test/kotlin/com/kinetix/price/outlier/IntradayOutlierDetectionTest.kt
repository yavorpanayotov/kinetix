package com.kinetix.price.outlier

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

/**
 * Bad ticks happen: a feed glitch publishes a $0.01 print for a $200
 * stock, or a copy-paste in the testing console flips an 8 into an 18.
 * Without an outlier gate the price-service serializes the bad print
 * into the audit chain. The detector flags moves > 10% from the prior
 * mid as outliers and emits a counter so the platform can alert on a
 * sudden spike in outlier rates.
 */
class IntradayOutlierDetectionTest : FunSpec({

    test("a small move is not an outlier") {
        val registry = SimpleMeterRegistry()
        val detector = IntradayOutlierDetector(registry, thresholdPct = 0.10)
        detector.check("AAPL", priorMid = 200.0, newPrice = 201.0) shouldBe false
        registry.find("price.outlier.flagged.count").counter()?.count() ?: 0.0
    }

    test("a 15% move is an outlier and increments the counter") {
        val registry = SimpleMeterRegistry()
        val detector = IntradayOutlierDetector(registry, thresholdPct = 0.10)
        detector.check("AAPL", 200.0, 230.0) shouldBe true
        registry.find("price.outlier.flagged.count").counter()!!.count() shouldBe 1.0
    }

    test("a -15% move is also an outlier (symmetric)") {
        val registry = SimpleMeterRegistry()
        val detector = IntradayOutlierDetector(registry, thresholdPct = 0.10)
        detector.check("AAPL", 200.0, 170.0) shouldBe true
    }

    test("at exactly the threshold (10%) it is flagged (>= inclusive)") {
        val registry = SimpleMeterRegistry()
        val detector = IntradayOutlierDetector(registry, thresholdPct = 0.10)
        detector.check("AAPL", 100.0, 110.0) shouldBe true
    }

    test("zero prior mid (no reference) does not flag") {
        val registry = SimpleMeterRegistry()
        val detector = IntradayOutlierDetector(registry, thresholdPct = 0.10)
        detector.check("AAPL", priorMid = 0.0, newPrice = 100.0) shouldBe false
    }

    test("multiple instruments increment the same counter") {
        val registry = SimpleMeterRegistry()
        val detector = IntradayOutlierDetector(registry, thresholdPct = 0.10)
        detector.check("AAPL", 200.0, 230.0)
        detector.check("MSFT", 300.0, 350.0)
        registry.find("price.outlier.flagged.count").counter()!!.count() shouldBe 2.0
    }
})

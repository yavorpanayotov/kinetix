package com.kinetix.risk.orchestrator.concentration

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.math.abs

/**
 * The concentration gauge surfaces the largest single-position weight in
 * a portfolio as a fraction of the gross notional. A trader sitting on a
 * single 60%-weight position is in a structurally different risk
 * universe from one with a flat 5%-each book, and the on-call risk
 * officer wants to see that at a glance — not after digging through the
 * positions table. Gauge name:
 * `risk.orchestrator.portfolio.concentration.max_weight`.
 */
class PortfolioConcentrationGaugeTest : FunSpec({

    test("publishes a gauge tagged with the portfolio id") {
        val registry = SimpleMeterRegistry()
        val gauge = PortfolioConcentrationGauge(registry)
        gauge.observe("BOOK-A", mapOf("AAPL" to 30.0, "MSFT" to 70.0))
        val m = registry.find("risk.orchestrator.portfolio.concentration.max_weight")
            .tag("portfolio_id", "BOOK-A").gauge()
        m!!.value() shouldBe 0.7
    }

    test("uses the absolute notional so a short position counts as concentration") {
        val registry = SimpleMeterRegistry()
        val gauge = PortfolioConcentrationGauge(registry)
        gauge.observe("BOOK-B", mapOf("AAPL" to -90.0, "MSFT" to 10.0))
        val m = registry.find("risk.orchestrator.portfolio.concentration.max_weight")
            .tag("portfolio_id", "BOOK-B").gauge()
        m!!.value() shouldBe 0.9
    }

    test("equally-weighted book is 1/N concentration") {
        val registry = SimpleMeterRegistry()
        val gauge = PortfolioConcentrationGauge(registry)
        gauge.observe("BOOK-C", mapOf("AAPL" to 25.0, "MSFT" to 25.0, "GOOG" to 25.0, "AMZN" to 25.0))
        val m = registry.find("risk.orchestrator.portfolio.concentration.max_weight")
            .tag("portfolio_id", "BOOK-C").gauge()
        abs(m!!.value() - 0.25) shouldBeLessThanOrEqual 1e-9
    }

    test("an empty portfolio publishes 0 (no concentration)") {
        val registry = SimpleMeterRegistry()
        val gauge = PortfolioConcentrationGauge(registry)
        gauge.observe("BOOK-D", emptyMap())
        val m = registry.find("risk.orchestrator.portfolio.concentration.max_weight")
            .tag("portfolio_id", "BOOK-D").gauge()
        m!!.value() shouldBe 0.0
    }

    test("re-observing the same portfolio updates the value (gauge does not duplicate)") {
        val registry = SimpleMeterRegistry()
        val gauge = PortfolioConcentrationGauge(registry)
        gauge.observe("BOOK-E", mapOf("AAPL" to 30.0, "MSFT" to 70.0))
        gauge.observe("BOOK-E", mapOf("AAPL" to 90.0, "MSFT" to 10.0))
        val gauges = registry.find("risk.orchestrator.portfolio.concentration.max_weight")
            .tag("portfolio_id", "BOOK-E").gauges()
        gauges.size shouldBe 1
        gauges.first().value() shouldBe 0.9
    }

    test("books with all-zero positions publish 0 (defensive)") {
        val registry = SimpleMeterRegistry()
        val gauge = PortfolioConcentrationGauge(registry)
        gauge.observe("BOOK-F", mapOf("AAPL" to 0.0, "MSFT" to 0.0))
        val m = registry.find("risk.orchestrator.portfolio.concentration.max_weight")
            .tag("portfolio_id", "BOOK-F").gauge()
        m!!.value() shouldBe 0.0
    }
})

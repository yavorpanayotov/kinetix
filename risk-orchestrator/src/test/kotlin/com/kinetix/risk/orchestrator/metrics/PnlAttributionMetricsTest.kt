package com.kinetix.risk.orchestrator.metrics

import com.kinetix.common.model.BookId
import com.kinetix.risk.model.PnlAttribution
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

private fun attribution(
    book: String = "balanced-income",
    total: String = "12345.67",
    delta: String = "8000.00",
    gamma: String = "1000.00",
    vega: String = "500.00",
    theta: String = "-250.00",
    rho: String = "95.00",
    unexplained: String = "3000.67",
) = PnlAttribution(
    bookId = BookId(book),
    date = LocalDate.parse("2026-05-29"),
    currency = "USD",
    totalPnl = BigDecimal(total),
    deltaPnl = BigDecimal(delta),
    gammaPnl = BigDecimal(gamma),
    vegaPnl = BigDecimal(vega),
    thetaPnl = BigDecimal(theta),
    rhoPnl = BigDecimal(rho),
    unexplainedPnl = BigDecimal(unexplained),
    positionAttributions = emptyList(),
    calculatedAt = Instant.parse("2026-05-29T17:00:00Z"),
)

class PnlAttributionMetricsTest : FunSpec({

    test("publish registers total, unexplained and per-greek gauges tagged by book") {
        val registry = SimpleMeterRegistry()
        val metrics = PnlAttributionMetrics(registry)

        metrics.publish(attribution())

        registry.get("pnl_attribution_total_pnl").tag("book_id", "balanced-income").gauge().value() shouldBe 12345.67
        registry.get("pnl_attribution_unexplained_pnl").tag("book_id", "balanced-income").gauge().value() shouldBe 3000.67
        registry.get("pnl_attribution_greek_pnl").tags("book_id", "balanced-income", "greek", "delta").gauge().value() shouldBe 8000.0
        registry.get("pnl_attribution_greek_pnl").tags("book_id", "balanced-income", "greek", "gamma").gauge().value() shouldBe 1000.0
        registry.get("pnl_attribution_greek_pnl").tags("book_id", "balanced-income", "greek", "vega").gauge().value() shouldBe 500.0
        registry.get("pnl_attribution_greek_pnl").tags("book_id", "balanced-income", "greek", "theta").gauge().value() shouldBe -250.0
        registry.get("pnl_attribution_greek_pnl").tags("book_id", "balanced-income", "greek", "rho").gauge().value() shouldBe 95.0
    }

    test("re-publishing the same book updates the existing gauge in place, not a duplicate") {
        val registry = SimpleMeterRegistry()
        val metrics = PnlAttributionMetrics(registry)

        metrics.publish(attribution(total = "100.00"))
        metrics.publish(attribution(total = "200.00"))

        registry.get("pnl_attribution_total_pnl").tag("book_id", "balanced-income").gauge().value() shouldBe 200.0
        registry.find("pnl_attribution_total_pnl").gauges().size shouldBe 1
    }

    test("different books each get their own series") {
        val registry = SimpleMeterRegistry()
        val metrics = PnlAttributionMetrics(registry)

        metrics.publish(attribution(book = "macro-hedge", total = "777.00"))
        metrics.publish(attribution(book = "emerging-markets", total = "888.00"))

        registry.get("pnl_attribution_total_pnl").tag("book_id", "macro-hedge").gauge().value() shouldBe 777.0
        registry.get("pnl_attribution_total_pnl").tag("book_id", "emerging-markets").gauge().value() shouldBe 888.0
    }
})

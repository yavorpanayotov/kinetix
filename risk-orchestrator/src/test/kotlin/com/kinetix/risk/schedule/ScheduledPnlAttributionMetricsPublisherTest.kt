package com.kinetix.risk.schedule

import com.kinetix.common.model.BookId
import com.kinetix.risk.model.PnlAttribution
import com.kinetix.risk.orchestrator.metrics.PnlAttributionMetrics
import com.kinetix.risk.persistence.PnlAttributionRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

private fun attribution(book: String, total: String) = PnlAttribution(
    bookId = BookId(book),
    date = LocalDate.parse("2026-05-29"),
    currency = "USD",
    totalPnl = BigDecimal(total),
    deltaPnl = BigDecimal("1.00"),
    gammaPnl = BigDecimal("0.00"),
    vegaPnl = BigDecimal("0.00"),
    thetaPnl = BigDecimal("0.00"),
    rhoPnl = BigDecimal("0.00"),
    unexplainedPnl = BigDecimal("0.00"),
    positionAttributions = emptyList(),
    calculatedAt = Instant.parse("2026-05-29T17:00:00Z"),
)

class ScheduledPnlAttributionMetricsPublisherTest : FunSpec({

    test("publishOnce emits gauges for the latest attribution of every book") {
        val repository = mockk<PnlAttributionRepository>()
        coEvery { repository.findLatestByBookId(BookId("balanced-income")) } returns attribution("balanced-income", "100.00")
        coEvery { repository.findLatestByBookId(BookId("macro-hedge")) } returns attribution("macro-hedge", "200.00")
        val registry = SimpleMeterRegistry()

        val publisher = ScheduledPnlAttributionMetricsPublisher(
            repository = repository,
            metrics = PnlAttributionMetrics(registry),
            bookIds = { listOf(BookId("balanced-income"), BookId("macro-hedge")) },
        )

        publisher.publishOnce()

        registry.get("pnl_attribution_total_pnl").tag("book_id", "balanced-income").gauge().value() shouldBe 100.0
        registry.get("pnl_attribution_total_pnl").tag("book_id", "macro-hedge").gauge().value() shouldBe 200.0
    }

    test("publishOnce skips books with no persisted attribution without failing") {
        val repository = mockk<PnlAttributionRepository>()
        coEvery { repository.findLatestByBookId(BookId("has-data")) } returns attribution("has-data", "50.00")
        coEvery { repository.findLatestByBookId(BookId("no-data")) } returns null
        val registry = SimpleMeterRegistry()

        val publisher = ScheduledPnlAttributionMetricsPublisher(
            repository = repository,
            metrics = PnlAttributionMetrics(registry),
            bookIds = { listOf(BookId("has-data"), BookId("no-data")) },
        )

        publisher.publishOnce()

        registry.get("pnl_attribution_total_pnl").tag("book_id", "has-data").gauge().value() shouldBe 50.0
        registry.find("pnl_attribution_total_pnl").tag("book_id", "no-data").gauge() shouldBe null
    }
})

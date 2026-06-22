package com.kinetix.risk.service

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.risk.model.DailyRiskSnapshot
import com.kinetix.risk.model.PositionRisk
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.LocalDate

private fun row(
    instrumentId: String,
    delta: Double? = null,
    gamma: Double? = null,
    vega: Double? = null,
) = PositionRisk(
    instrumentId = InstrumentId(instrumentId),
    assetClass = AssetClass.DERIVATIVE,
    marketValue = BigDecimal.TEN,
    delta = delta,
    gamma = gamma,
    vega = vega,
    varContribution = BigDecimal.ONE,
    esContribution = BigDecimal.ONE,
    percentageOfTotal = BigDecimal.ONE,
)

private fun snap(
    instrumentId: String,
    date: LocalDate,
    delta: Double? = null,
    gamma: Double? = null,
    vega: Double? = null,
    theta: Double? = null,
    rho: Double? = null,
) = DailyRiskSnapshot(
    bookId = BookId("derivatives-book"),
    snapshotDate = date,
    instrumentId = InstrumentId(instrumentId),
    assetClass = AssetClass.DERIVATIVE,
    quantity = BigDecimal.ONE,
    marketPrice = BigDecimal.TEN,
    delta = delta,
    gamma = gamma,
    vega = vega,
    theta = theta,
    rho = rho,
)

class PositionGreeksOverlayTest : FunSpec({

    test("fills null greeks from the latest daily snapshot") {
        val result = overlayGreeksFromSnapshots(
            listOf(row("MSFT-C-450")),
            listOf(snap("MSFT-C-450", LocalDate.parse("2026-06-15"), delta = 0.33, gamma = 0.12, vega = 6.5, theta = -49.6, rho = 0.27)),
        )
        result[0].delta shouldBe 0.33
        result[0].gamma shouldBe 0.12
        result[0].vega shouldBe 6.5
        result[0].theta shouldBe -49.6
        result[0].rho shouldBe 0.27
    }

    test("never overwrites a live computed greek (e.g. linear cash-equity delta)") {
        val result = overlayGreeksFromSnapshots(
            listOf(row("MSFT", delta = 27_502_502.30, gamma = 0.0, vega = 0.0)),
            listOf(snap("MSFT", LocalDate.parse("2026-06-15"), delta = 999.0, gamma = 999.0, vega = 999.0)),
        )
        result[0].delta shouldBe 27_502_502.30
        result[0].gamma shouldBe 0.0
        result[0].vega shouldBe 0.0
    }

    test("prefers the most recent snapshot when several dates exist") {
        val result = overlayGreeksFromSnapshots(
            listOf(row("AAPL-C-200")),
            listOf(
                snap("AAPL-C-200", LocalDate.parse("2026-06-10"), delta = 0.1),
                snap("AAPL-C-200", LocalDate.parse("2026-06-15"), delta = 0.9),
            ),
        )
        result[0].delta shouldBe 0.9
    }

    test("leaves the row untouched when the snapshot also has no greeks (non-convergent option)") {
        val result = overlayGreeksFromSnapshots(
            listOf(row("GC-C-2200-DEC26")),
            listOf(snap("GC-C-2200-DEC26", LocalDate.parse("2026-06-15"))),
        )
        result[0].delta.shouldBeNull()
        result[0].gamma.shouldBeNull()
        result[0].vega.shouldBeNull()
    }

    test("returns the input unchanged when there are no snapshots") {
        val rows = listOf(row("MSFT-C-450"))
        overlayGreeksFromSnapshots(rows, emptyList()) shouldBe rows
    }
})

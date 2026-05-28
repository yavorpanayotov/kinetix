package com.kinetix.risk.service

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Position
import com.kinetix.common.model.instrument.InstrumentTypeCode
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.client.RiskEngineClient
import com.kinetix.risk.kafka.RiskResultPublisher
import com.kinetix.risk.model.CalculationType
import com.kinetix.risk.model.ComponentBreakdown
import com.kinetix.risk.model.ConfidenceLevel
import com.kinetix.risk.model.GreekValues
import com.kinetix.risk.model.GreeksResult
import com.kinetix.risk.model.ValuationOutput
import com.kinetix.risk.model.ValuationResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

/**
 * Regression for the trader-review P0 bug: `Risk → Position Risk Breakdown`
 * was showing identical per-row Delta/Gamma/Vega for every instrument in an
 * asset class — because [VaRCalculationService.computePositionRisk] was
 * reading Greeks out of the per-asset-class [GreeksResult] aggregate instead
 * of attributing them per position.
 *
 * The expected behaviour:
 *
 *  1. Two cash-equity positions with **different share counts** must surface
 *     **different deltas** (delta scales linearly with quantity).
 *  2. Cash equities have **zero gamma and zero vega** (no convexity, no
 *     vol-sensitivity). Only options do.
 *
 * If either expectation breaks, the per-instrument breakdown collapses to the
 * asset-class aggregate again and the table becomes useless to a trader.
 */
class PerInstrumentGreeksTest : FunSpec({

    val USD = Currency.getInstance("USD")

    fun cashEquityPosition(
        instrumentId: String,
        quantity: String,
        marketPrice: String,
    ) = Position(
        bookId = BookId("port-1"),
        instrumentId = InstrumentId(instrumentId),
        assetClass = AssetClass.EQUITY,
        quantity = BigDecimal(quantity),
        averageCost = Money(BigDecimal(marketPrice), USD),
        marketPrice = Money(BigDecimal(marketPrice), USD),
        instrumentType = InstrumentTypeCode.CASH_EQUITY,
    )

    val positionProvider = mockk<PositionProvider>()
    val riskEngineClient = mockk<RiskEngineClient>()
    val resultPublisher = mockk<RiskResultPublisher>()
    val service = VaRCalculationService(
        positionProvider, riskEngineClient, resultPublisher, SimpleMeterRegistry(),
    )

    // Asset-class aggregate carries non-zero gamma/vega so we can detect when
    // the bug — copying the aggregate onto every row — silently reappears.
    val equityAggregate = GreekValues(
        assetClass = AssetClass.EQUITY,
        delta = -2_111_524.48,
        gamma = -445_120_486.75,
        vega = -1_645_799.29,
    )

    val valuationResult = ValuationResult(
        bookId = BookId("port-1"),
        calculationType = CalculationType.PARAMETRIC,
        confidenceLevel = ConfidenceLevel.CL_95,
        varValue = 10_000.0,
        expectedShortfall = 12_500.0,
        componentBreakdown = listOf(
            ComponentBreakdown(AssetClass.EQUITY, 10_000.0, 100.0),
        ),
        greeks = GreeksResult(
            assetClassGreeks = listOf(equityAggregate),
            theta = 47_265.38,
            rho = -665.94,
        ),
        calculatedAt = Instant.now(),
        computedOutputs = setOf(
            ValuationOutput.VAR,
            ValuationOutput.EXPECTED_SHORTFALL,
            ValuationOutput.GREEKS,
        ),
    )

    test("two cash-equity positions with different share counts produce different per-instrument deltas") {
        val aapl = cashEquityPosition(instrumentId = "AAPL", quantity = "100", marketPrice = "170.00")
        val jnj = cashEquityPosition(instrumentId = "JNJ", quantity = "50", marketPrice = "160.00")

        val rows = service.computePositionRisk(listOf(aapl, jnj), valuationResult)

        rows shouldHaveSize 2
        val aaplRow = rows.first { it.instrumentId.value == "AAPL" }
        val jnjRow = rows.first { it.instrumentId.value == "JNJ" }

        // Each row carries a delta value.
        aaplRow.delta shouldNotBe null
        jnjRow.delta shouldNotBe null

        // The bug surfaces as both deltas equal to the asset-class aggregate.
        // After the fix, per-instrument deltas must be distinct because the
        // instruments have different share counts and different prices.
        aaplRow.delta shouldNotBe jnjRow.delta
        aaplRow.delta shouldNotBe equityAggregate.delta
        jnjRow.delta shouldNotBe equityAggregate.delta
    }

    test("cash-equity positions have zero gamma and zero vega regardless of asset-class aggregate") {
        val aapl = cashEquityPosition(instrumentId = "AAPL", quantity = "100", marketPrice = "170.00")
        val jpm = cashEquityPosition(instrumentId = "JPM", quantity = "200", marketPrice = "150.00")

        val rows = service.computePositionRisk(listOf(aapl, jpm), valuationResult)

        rows shouldHaveSize 2
        rows.forEach { row ->
            // Cash equity has no convexity and no vol sensitivity. The
            // aggregate carries non-zero gamma/vega from any options book —
            // but a cash-equity row must NOT inherit those.
            row.gamma!!.shouldBeExactly(0.0)
            row.vega!!.shouldBeExactly(0.0)
        }
    }

    test("per-instrument delta for cash equity scales with signed quantity (dollar delta = quantity * marketPrice)") {
        // Dollar delta for a long cash equity is qty * spot.
        val aapl = cashEquityPosition(instrumentId = "AAPL", quantity = "100", marketPrice = "170.00")
        val jnj = cashEquityPosition(instrumentId = "JNJ", quantity = "50", marketPrice = "160.00")

        val rows = service.computePositionRisk(listOf(aapl, jnj), valuationResult)

        val aaplRow = rows.first { it.instrumentId.value == "AAPL" }
        val jnjRow = rows.first { it.instrumentId.value == "JNJ" }

        // 100 * 170 = 17,000 ; 50 * 160 = 8,000
        aaplRow.delta!!.shouldBeExactly(17_000.0)
        jnjRow.delta!!.shouldBeExactly(8_000.0)
    }

    test("a short cash-equity position produces a negative delta") {
        val shortAapl = cashEquityPosition(instrumentId = "AAPL", quantity = "-100", marketPrice = "170.00")

        val rows = service.computePositionRisk(listOf(shortAapl), valuationResult)

        rows shouldHaveSize 1
        rows[0].delta!!.shouldBeExactly(-17_000.0)
    }
})

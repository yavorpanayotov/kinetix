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
import com.kinetix.risk.model.PositionGreek
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

    // ---------------------------------------------------------------------
    // Trader-review P0 #2: per-instrument DV01 / Theta / Rho.
    //
    // The previous fix wired per-row Delta/Gamma/Vega. The DV01, Theta, and
    // Rho columns were still rendering `—` for every instrument because:
    //   - PositionRisk did not carry theta / rho at all,
    //   - DV01 had no place to live in the domain object,
    //   - the orchestrator never asked the engine for them per-row.
    //
    // The expected behaviour after the fix:
    //   1. Cash equity rows: theta = rho = 0 (linear instrument), dv01 = 0
    //      (no rate sensitivity). Explicit zero — not null — so the UI can
    //      tell "computed and zero" apart from "missing".
    //   2. Option rows: theta + rho come from the per-position Black-Scholes
    //      output already surfaced through positionGreeks.
    //   3. Treasury (FIXED_INCOME) rows: dv01 is a non-zero positive number,
    //      proportional to the position's market value (analytical DV01
    //      derived in the orchestrator).
    // ---------------------------------------------------------------------

    fun governmentBondPosition(
        instrumentId: String,
        quantity: String,
        marketPrice: String,
    ) = Position(
        bookId = BookId("port-1"),
        instrumentId = InstrumentId(instrumentId),
        assetClass = AssetClass.FIXED_INCOME,
        quantity = BigDecimal(quantity),
        averageCost = Money(BigDecimal(marketPrice), USD),
        marketPrice = Money(BigDecimal(marketPrice), USD),
        instrumentType = InstrumentTypeCode.GOVERNMENT_BOND,
    )

    fun equityOptionPosition(
        instrumentId: String,
        quantity: String,
        marketPrice: String,
    ) = Position(
        bookId = BookId("port-1"),
        instrumentId = InstrumentId(instrumentId),
        assetClass = AssetClass.DERIVATIVE,
        quantity = BigDecimal(quantity),
        averageCost = Money(BigDecimal(marketPrice), USD),
        marketPrice = Money(BigDecimal(marketPrice), USD),
        instrumentType = InstrumentTypeCode.EQUITY_OPTION,
    )

    test("cash-equity row carries explicit zero theta, rho, and dv01 (so the UI does not render em-dash)") {
        val aapl = cashEquityPosition(instrumentId = "AAPL", quantity = "100", marketPrice = "170.00")

        val rows = service.computePositionRisk(listOf(aapl), valuationResult)

        rows shouldHaveSize 1
        val row = rows.single()
        // Cash equity has no time-decay, no option-style rate sensitivity,
        // and no DV01 — but we must emit explicit zeros so the table cell
        // shows "0" and the asset-class formatter (rather than missing
        // data) decides whether to render `—`.
        row.theta!!.shouldBeExactly(0.0)
        row.rho!!.shouldBeExactly(0.0)
        row.dv01!!.shouldBeExactly(0.0)
    }

    test("equity option row carries the per-position Black-Scholes theta and rho returned by the engine") {
        // The engine attributes theta / rho per option via BS partials.
        // The orchestrator must surface them on the per-instrument row.
        val callTheta = -4.25
        val callRho = 6.10
        val resultWithOptionGreeks = valuationResult.copy(
            positionGreeks = listOf(
                PositionGreek(
                    instrumentId = "AAPL-CALL",
                    delta = 52.4,
                    gamma = 0.024,
                    vega = 18.5,
                    theta = callTheta,
                    rho = callRho,
                ),
            ),
        )

        val option = equityOptionPosition(
            instrumentId = "AAPL-CALL",
            quantity = "10",
            marketPrice = "350.00",
        )
        val rows = service.computePositionRisk(listOf(option), resultWithOptionGreeks)

        rows shouldHaveSize 1
        val row = rows.single()
        row.theta!!.shouldBeExactly(callTheta)
        row.rho!!.shouldBeExactly(callRho)
        // Options are not rates instruments; DV01 should be zero, not null.
        row.dv01!!.shouldBeExactly(0.0)
    }

    test("government-bond row carries a non-zero positive DV01 derived from its market value") {
        // DV01 for a Treasury is dollar PV change for a 1bp parallel rate
        // shift. The orchestrator approximates it analytically from the
        // position's signed market value when the engine has not surfaced
        // a per-position DV01; the value must be positive (PV falls when
        // rates rise) and scale with the position's market exposure.
        val ust10y = governmentBondPosition(
            instrumentId = "UST-10Y",
            quantity = "1000",
            marketPrice = "100.00",
        )
        val rows = service.computePositionRisk(listOf(ust10y), valuationResult)

        rows shouldHaveSize 1
        val row = rows.single()
        row.dv01 shouldNotBe null
        // The exact value depends on the analytical approximation, but it
        // MUST be strictly positive (the trader-review bug surfaced as
        // dv01 = null / 0 for every bond) and MUST be small relative to
        // the position's market value (DV01 is ~modified_duration × MV ×
        // 0.0001 → on the order of 10⁻³ of market value).
        (row.dv01!! > 0.0) shouldBe true
        // Theta / Rho on a plain bond are not surfaced via Black-Scholes;
        // they must default to explicit zero, not null.
        row.theta!!.shouldBeExactly(0.0)
        row.rho!!.shouldBeExactly(0.0)
    }

    test("dv01 scales linearly with market value (the analytical formula has the right shape)") {
        val small = governmentBondPosition(instrumentId = "UST-A", quantity = "100", marketPrice = "100.00")
        val large = governmentBondPosition(instrumentId = "UST-B", quantity = "1000", marketPrice = "100.00")

        val rows = service.computePositionRisk(listOf(small, large), valuationResult)
        val smallRow = rows.first { it.instrumentId.value == "UST-A" }
        val largeRow = rows.first { it.instrumentId.value == "UST-B" }

        // A position 10× larger should produce a DV01 that is also 10×
        // larger (analytical DV01 is linear in market value).
        (largeRow.dv01!! / smallRow.dv01!!).shouldBeExactly(10.0)
    }
})

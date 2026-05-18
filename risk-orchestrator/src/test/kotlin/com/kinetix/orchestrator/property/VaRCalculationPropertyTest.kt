package com.kinetix.orchestrator.property

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Position
import com.kinetix.common.model.instrument.InstrumentTypeCode
import com.kinetix.proto.risk.DataDependenciesResponse
import com.kinetix.risk.client.InstrumentServiceClient
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.client.RiskEngineClient
import com.kinetix.risk.client.dtos.InstrumentDto
import com.kinetix.risk.kafka.RiskResultPublisher
import com.kinetix.risk.model.CalculationType
import com.kinetix.risk.model.ComponentBreakdown
import com.kinetix.risk.model.ConfidenceLevel
import com.kinetix.risk.model.FactorDecompositionSnapshot
import com.kinetix.risk.model.MarketDataValue
import com.kinetix.risk.model.TimeSeriesMarketData
import com.kinetix.risk.model.VaRCalculationRequest
import com.kinetix.risk.model.VaRResult
import com.kinetix.risk.model.ValuationOutput
import com.kinetix.risk.model.ValuationResult
import com.kinetix.risk.service.VaRCalculationService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.property.Arb
import io.kotest.property.PropertyTesting
import io.kotest.property.arbitrary.bigDecimal
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.stringPattern
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

/**
 * Property-based tests for [VaRCalculationService].
 *
 * These tests pin the [RiskEngineClient] to a hand-rolled fake that computes
 * historical-method VaR as the empirical loss-quantile over a fixed,
 * deterministic scenario set. With the scenarios fixed, the orchestrator's
 * end-to-end VaR — `service.calculateVaR(...).varValue` — must satisfy the
 * mathematical properties of historical VaR:
 *
 *  1. Monotone in confidence level. For the same portfolio,
 *     `VaR(c1) <= VaR(c2)` whenever `c1 <= c2`.
 *  2. Sub-additivity under the historical method. Computed over a *shared*
 *     scenario set, the empirical loss-quantile satisfies
 *     `VaR(A ∪ B) <= VaR(A) + VaR(B)`. This is the coherent-risk-measure
 *     property; historical VaR is sub-additive in this construction because
 *     `quantile_alpha(L_A + L_B) <= quantile_alpha(L_A) + quantile_alpha(L_B)`
 *     when the losses are linear functionals of returns and the quantiles
 *     are taken over the same finite empirical distribution.
 *  3. Empty portfolio returns zero VaR. The orchestrator short-circuits to
 *     `null` for an empty book — which we treat as zero per the property.
 *
 * The fake `RiskEngineClient` runs entirely in-JVM (no Python sidecar, no
 * gRPC), so these are pure unit tests suitable for `./gradlew test`. They
 * exercise the orchestration path, not the sidecar's numerical correctness.
 */
class VaRCalculationPropertyTest : FunSpec({

    // Deterministic seed so failures are reproducible across runs and CI.
    PropertyTesting.defaultSeed = 7_777L

    val usd = Currency.getInstance("USD")

    // Generate ~250 scenario returns (one year of daily) for the fake risk
    // engine. Fixed across the whole spec so VaR(A) and VaR(B) and VaR(A∪B)
    // share the same empirical distribution — required for sub-additivity.
    val scenarioReturns: List<Double> = run {
        val rng = java.util.Random(42L)
        List(250) { rng.nextGaussian() * 0.015 } // ~1.5% daily vol
    }

    val instrumentIdArb = Arb.stringPattern("[A-Z]{3,5}")
    val quantityArb = Arb.bigDecimal(BigDecimal("1"), BigDecimal("1000"))
    val priceArb = Arb.bigDecimal(BigDecimal("10"), BigDecimal("500"))
    val assetClassArb = Arb.element(
        AssetClass.EQUITY, AssetClass.FIXED_INCOME, AssetClass.FX, AssetClass.COMMODITY,
    )

    fun positionArb(bookId: BookId): Arb<Position> = Arb.bind(
        instrumentIdArb, quantityArb, priceArb, assetClassArb,
    ) { instId, qty, price, ac ->
        Position(
            bookId = bookId,
            instrumentId = InstrumentId(instId),
            assetClass = ac,
            quantity = qty,
            averageCost = Money(price, usd),
            marketPrice = Money(price, usd),
            instrumentType = InstrumentTypeCode.CASH_EQUITY,
        )
    }

    /**
     * Builds a fake [RiskEngineClient] that returns a historical-method VaR
     * computed as the empirical (1-CL) loss-quantile of portfolio P&L over
     * [scenarios]. The portfolio P&L per scenario is `sum_i mv_i * r_s` where
     * `r_s` is the scenario return — a single common factor model, which is
     * deliberately simple but mathematically valid for the invariants under
     * test (monotonicity, sub-additivity).
     */
    fun fakeRiskEngine(scenarios: List<Double>, bookId: BookId): RiskEngineClient = object : RiskEngineClient {
        override suspend fun calculateVaR(
            request: VaRCalculationRequest,
            positions: List<Position>,
            marketData: List<MarketDataValue>,
            instrumentMap: Map<String, InstrumentDto>,
        ): VaRResult = error("not used in this property test")

        override suspend fun valuate(
            request: VaRCalculationRequest,
            positions: List<Position>,
            marketData: List<MarketDataValue>,
            instrumentMap: Map<String, InstrumentDto>,
        ): ValuationResult {
            val totalMarketValue = positions.sumOf { it.marketValue.amount.toDouble() }
            // P&L per scenario, then losses (negated P&L).
            val losses = scenarios.map { -totalMarketValue * it }.sorted()
            val alpha = request.confidenceLevel.value // 0.95, 0.975, 0.99
            // Loss at the alpha-quantile of the empirical loss distribution.
            // VaR is non-negative (a magnitude); take max with 0.
            val idx = ((alpha * (losses.size - 1)).toInt()).coerceIn(0, losses.size - 1)
            val varValue = maxOf(0.0, losses[idx])

            // Synthetic single-asset-class component breakdown so the
            // orchestrator's downstream wiring doesn't divide by zero.
            val breakdown = positions
                .groupBy { it.assetClass }
                .map { (ac, _) -> ComponentBreakdown(ac, varValue / positions.size.coerceAtLeast(1), 0.0) }

            return ValuationResult(
                bookId = bookId,
                calculationType = request.calculationType,
                confidenceLevel = request.confidenceLevel,
                varValue = varValue,
                expectedShortfall = varValue * 1.25,
                componentBreakdown = breakdown,
                greeks = null,
                calculatedAt = Instant.EPOCH,
                computedOutputs = setOf(ValuationOutput.VAR, ValuationOutput.EXPECTED_SHORTFALL),
            )
        }

        override suspend fun discoverDependencies(
            positions: List<Position>,
            calculationType: String,
            confidenceLevel: String,
            instrumentMap: Map<String, InstrumentDto>,
        ): DataDependenciesResponse = error("not used in this property test")

        override suspend fun decomposeFactorRisk(
            bookId: BookId,
            positions: List<Position>,
            marketData: Map<String, TimeSeriesMarketData>,
            totalVar: Double,
        ): FactorDecompositionSnapshot = error("not used in this property test")
    }

    fun positionProviderReturning(positions: List<Position>): PositionProvider = object : PositionProvider {
        override suspend fun getPositions(bookId: BookId): List<Position> = positions
    }

    val noopPublisher = object : RiskResultPublisher {
        override suspend fun publish(result: ValuationResult, correlationId: String?) {}
    }

    fun buildService(positions: List<Position>, bookId: BookId): VaRCalculationService =
        VaRCalculationService(
            positionProvider = positionProviderReturning(positions),
            riskEngineClient = fakeRiskEngine(scenarioReturns, bookId),
            resultPublisher = noopPublisher,
        )

    fun historicalRequest(bookId: BookId, cl: ConfidenceLevel): VaRCalculationRequest =
        VaRCalculationRequest(
            bookId = bookId,
            calculationType = CalculationType.HISTORICAL,
            confidenceLevel = cl,
        )

    suspend fun calcVar(positions: List<Position>, bookId: BookId, cl: ConfidenceLevel): Double {
        val service = buildService(positions, bookId)
        return service.calculateVaR(historicalRequest(bookId, cl))?.varValue ?: 0.0
    }

    test("historical VaR is monotone non-decreasing in confidence level") {
        val bookId = BookId("port-mono")
        checkAll(
            iterations = 50,
            genA = Arb.list(positionArb(bookId), 1..6),
        ) { positions ->
            runBlocking {
                val v95 = calcVar(positions, bookId, ConfidenceLevel.CL_95)
                val v975 = calcVar(positions, bookId, ConfidenceLevel.CL_975)
                val v99 = calcVar(positions, bookId, ConfidenceLevel.CL_99)

                v95.shouldBeLessThanOrEqual(v975 + 1e-9)
                v975.shouldBeLessThanOrEqual(v99 + 1e-9)
            }
        }
    }

    test("historical VaR is sub-additive over disjoint portfolios: VaR(A ∪ B) <= VaR(A) + VaR(B)") {
        val bookId = BookId("port-subadd")
        // Disjoint instrument ids so the union really is A ∪ B with no overlap.
        val instrumentArb = Arb.list(instrumentIdArb, 2..8).map { it.distinct() }

        checkAll(iterations = 50, genA = instrumentArb) { distinctIds ->
            if (distinctIds.size < 2) return@checkAll
            // Split half-half into two portfolios.
            val mid = distinctIds.size / 2
            val aIds = distinctIds.take(mid)
            val bIds = distinctIds.drop(mid)
            if (aIds.isEmpty() || bIds.isEmpty()) return@checkAll

            // For each instrument, draw a position with a fixed RNG so the
            // generator deterministically produces the same A, B, and A∪B.
            val rng = java.util.Random(distinctIds.hashCode().toLong())
            fun pos(id: String): Position = Position(
                bookId = bookId,
                instrumentId = InstrumentId(id),
                assetClass = AssetClass.entries[rng.nextInt(AssetClass.entries.size - 1)], // skip DERIVATIVE last
                quantity = BigDecimal(1 + rng.nextInt(999)),
                averageCost = Money(BigDecimal(10 + rng.nextInt(490)), usd),
                marketPrice = Money(BigDecimal(10 + rng.nextInt(490)), usd),
                instrumentType = InstrumentTypeCode.CASH_EQUITY,
            )
            val a = aIds.map(::pos)
            val b = bIds.map(::pos)
            val union = a + b

            runBlocking {
                val varA = calcVar(a, bookId, ConfidenceLevel.CL_95)
                val varB = calcVar(b, bookId, ConfidenceLevel.CL_95)
                val varUnion = calcVar(union, bookId, ConfidenceLevel.CL_95)

                varUnion.shouldBeLessThanOrEqual(varA + varB + 1e-6)
            }
        }
    }

    test("empty portfolio returns zero VaR") {
        val bookId = BookId("port-empty")
        // No randomness needed — property holds for the single empty case —
        // but wrap in a tiny checkAll so this file is uniformly property-style.
        checkAll(iterations = 5, genA = Arb.int(1..1)) {
            runBlocking {
                val v = calcVar(positions = emptyList(), bookId = bookId, cl = ConfidenceLevel.CL_95)
                v shouldBe 0.0
            }
        }
    }
})

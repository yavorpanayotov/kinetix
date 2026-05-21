package com.kinetix.rates.feed

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.CurvePoint
import com.kinetix.common.model.ForwardCurve
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.RateSource
import com.kinetix.common.model.RiskFreeRate
import com.kinetix.common.model.Tenor
import com.kinetix.common.model.YieldCurve
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency
import kotlin.math.abs
import kotlin.random.Random

private val USD = Currency.getInstance("USD")
private val EUR = Currency.getInstance("EUR")
private val AS_OF = Instant.parse("2026-02-22T10:00:00Z")

private fun seedYieldCurves(): List<YieldCurve> = listOf(
    YieldCurve(
        currency = USD,
        asOf = AS_OF,
        tenors = listOf(
            Tenor.oneMonth(BigDecimal("0.0455")),
            Tenor.threeMonths(BigDecimal("0.0458")),
            Tenor.oneYear(BigDecimal("0.0465")),
        ),
        curveId = "USD",
        source = RateSource.CENTRAL_BANK,
    ),
)

private fun seedRiskFreeRates(): List<RiskFreeRate> = listOf(
    RiskFreeRate(currency = USD, tenor = "3M", rate = 4.55, asOfDate = AS_OF, source = RateSource.CENTRAL_BANK),
)

private fun seedForwardCurves(): List<ForwardCurve> = listOf(
    ForwardCurve(
        instrumentId = InstrumentId("EURUSD"),
        assetClass = AssetClass.FX,
        points = listOf(CurvePoint("1M", 1.0858), CurvePoint("3M", 1.0865), CurvePoint("1Y", 1.0905)),
        asOfDate = AS_OF,
        source = RateSource.INTERNAL,
    ),
)

class RatesFeedSimulatorTest : FunSpec({

    test("tick produces updated yield curves for all seeded currencies") {
        val sim = RatesFeedSimulator(
            seedYieldCurves = seedYieldCurves(),
            seedRiskFreeRates = seedRiskFreeRates(),
            seedForwardCurves = seedForwardCurves(),
            random = Random(42),
        )

        val tick = sim.tick(Instant.now())
        tick.yieldCurves shouldHaveSize 1
        tick.yieldCurves[0].currency shouldBe USD
    }

    test("tick produces updated risk-free rates") {
        val sim = RatesFeedSimulator(
            seedYieldCurves = seedYieldCurves(),
            seedRiskFreeRates = seedRiskFreeRates(),
            seedForwardCurves = seedForwardCurves(),
            random = Random(42),
        )

        val tick = sim.tick(Instant.now())
        tick.riskFreeRates shouldHaveSize 1
        tick.riskFreeRates[0].currency shouldBe USD
        tick.riskFreeRates[0].tenor shouldBe "3M"
    }

    test("tick produces updated forward curves") {
        val sim = RatesFeedSimulator(
            seedYieldCurves = seedYieldCurves(),
            seedRiskFreeRates = seedRiskFreeRates(),
            seedForwardCurves = seedForwardCurves(),
            random = Random(42),
        )

        val tick = sim.tick(Instant.now())
        tick.forwardCurves shouldHaveSize 1
        tick.forwardCurves[0].instrumentId shouldBe InstrumentId("EURUSD")
    }

    test("tick updates asOfDate to the provided timestamp") {
        val sim = RatesFeedSimulator(
            seedYieldCurves = seedYieldCurves(),
            seedRiskFreeRates = seedRiskFreeRates(),
            seedForwardCurves = seedForwardCurves(),
            random = Random(42),
        )

        val now = Instant.parse("2026-03-01T12:00:00Z")
        val tick = sim.tick(now)

        tick.yieldCurves[0].asOf shouldBe now
        tick.riskFreeRates[0].asOfDate shouldBe now
        tick.forwardCurves[0].asOfDate shouldBe now
    }

    test("yield curve rates mean-revert toward seed values") {
        val sim = RatesFeedSimulator(
            seedYieldCurves = seedYieldCurves(),
            seedRiskFreeRates = seedRiskFreeRates(),
            seedForwardCurves = seedForwardCurves(),
            random = Random(42),
        )

        val seedRate = 0.0455 // 1M tenor
        val observedRates = mutableListOf<Double>()

        repeat(10_000) {
            val tick = sim.tick(Instant.now())
            observedRates.add(tick.yieldCurves[0].tenors[0].rate.toDouble())
        }

        // Mean of all observed rates should be within 2% of the seed
        val meanRate = observedRates.average()
        val deviation = abs(meanRate - seedRate) / seedRate
        deviation shouldBeLessThan 0.02
    }

    test("rates remain non-negative after many ticks") {
        val sim = RatesFeedSimulator(
            seedYieldCurves = seedYieldCurves(),
            seedRiskFreeRates = seedRiskFreeRates(),
            seedForwardCurves = seedForwardCurves(),
            random = Random(42),
        )

        repeat(10_000) {
            val tick = sim.tick(Instant.now())
            for (curve in tick.yieldCurves) {
                for (tenor in curve.tenors) {
                    tenor.rate shouldBeGreaterThanOrEqualTo BigDecimal.ZERO
                }
            }
        }
    }

    test("deterministic output with same random seed") {
        fun create() = RatesFeedSimulator(
            seedYieldCurves = seedYieldCurves(),
            seedRiskFreeRates = seedRiskFreeRates(),
            seedForwardCurves = seedForwardCurves(),
            random = Random(99),
        )

        val sim1 = create()
        val sim2 = create()

        val now = Instant.now()
        repeat(10) {
            val t1 = sim1.tick(now)
            val t2 = sim2.tick(now)
            t1.yieldCurves[0].tenors[0].rate.compareTo(t2.yieldCurves[0].tenors[0].rate) shouldBe 0
            t1.riskFreeRates[0].rate shouldBe t2.riskFreeRates[0].rate
            t1.forwardCurves[0].points[0].value shouldBe t2.forwardCurves[0].points[0].value
        }
    }

    test("tenor structure is preserved across ticks") {
        val sim = RatesFeedSimulator(
            seedYieldCurves = seedYieldCurves(),
            seedRiskFreeRates = seedRiskFreeRates(),
            seedForwardCurves = seedForwardCurves(),
            random = Random(42),
        )

        val tick = sim.tick(Instant.now())

        // Yield curve tenors preserve labels and days
        tick.yieldCurves[0].tenors.map { it.label } shouldBe listOf("1M", "3M", "1Y")
        tick.yieldCurves[0].tenors.map { it.days } shouldBe listOf(30, 90, 365)

        // Forward curve tenors preserved
        tick.forwardCurves[0].points.map { it.tenor } shouldBe listOf("1M", "3M", "1Y")

        // After multiple ticks, values should diverge from seed
        repeat(100) { sim.tick(Instant.now()) }
        val tick2 = sim.tick(Instant.now())
        tick2.yieldCurves[0].tenors[0].rate.compareTo(BigDecimal("0.0455")) shouldNotBe 0
    }
})

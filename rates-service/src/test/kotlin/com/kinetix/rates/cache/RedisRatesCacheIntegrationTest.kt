package com.kinetix.rates.cache

import com.kinetix.common.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

private val USD = Currency.getInstance("USD")
private val NOW = Instant.parse("2026-01-15T10:00:00Z")

class RedisRatesCacheIntegrationTest : FunSpec({

    val connection = RedisTestSetup.start()
    val cache: RatesCache = RedisRatesCache(connection)

    beforeEach {
        connection.sync().flushall()
    }

    test("put and get yield curve") {
        val curve = YieldCurve(
            curveId = "USD-TREASURY",
            currency = USD,
            asOf = NOW,
            tenors = listOf(Tenor.oneMonth(BigDecimal("0.0400")), Tenor.oneYear(BigDecimal("0.0500"))),
            source = RateSource.CENTRAL_BANK,
        )
        cache.putYieldCurve(curve)

        val found = cache.getYieldCurve("USD-TREASURY")
        found.shouldNotBeNull()
        found.curveId shouldBe "USD-TREASURY"
        found.currency shouldBe USD
        found.tenors[0].rate.compareTo(BigDecimal("0.0400")) shouldBe 0
        found.source shouldBe RateSource.CENTRAL_BANK
    }

    test("get yield curve returns null for unknown curveId") {
        cache.getYieldCurve("UNKNOWN").shouldBeNull()
    }

    test("put and get risk-free rate") {
        val rate = RiskFreeRate(
            currency = USD,
            tenor = "3M",
            rate = 0.0525,
            asOfDate = NOW,
            source = RateSource.CENTRAL_BANK,
        )
        cache.putRiskFreeRate(rate)

        val found = cache.getRiskFreeRate(USD, "3M")
        found.shouldNotBeNull()
        found.tenor shouldBe "3M"
        found.rate shouldBe 0.0525
    }

    test("get risk-free rate returns null for unknown key") {
        cache.getRiskFreeRate(Currency.getInstance("JPY"), "O/N").shouldBeNull()
    }

    test("put and get forward curve") {
        val curve = ForwardCurve(
            instrumentId = InstrumentId("EURUSD"),
            assetClass = AssetClass.FX,
            points = listOf(CurvePoint("1M", 1.0855)),
            asOfDate = NOW,
            source = RateSource.REUTERS,
        )
        cache.putForwardCurve(curve)

        val found = cache.getForwardCurve(InstrumentId("EURUSD"))
        found.shouldNotBeNull()
        found.instrumentId shouldBe InstrumentId("EURUSD")
        found.assetClass shouldBe AssetClass.FX
        found.points[0].value shouldBe 1.0855
    }

    test("get forward curve returns null for unknown instrument") {
        cache.getForwardCurve(InstrumentId("UNKNOWN")).shouldBeNull()
    }

    test("put overwrites previous yield curve") {
        val curve1 = YieldCurve(
            curveId = "USD-TREASURY",
            currency = USD,
            asOf = NOW,
            tenors = listOf(Tenor.oneMonth(BigDecimal("0.0400"))),
            source = RateSource.CENTRAL_BANK,
        )
        cache.putYieldCurve(curve1)

        val curve2 = YieldCurve(
            curveId = "USD-TREASURY",
            currency = USD,
            asOf = Instant.parse("2026-01-16T10:00:00Z"),
            tenors = listOf(Tenor.oneMonth(BigDecimal("0.0450"))),
            source = RateSource.CENTRAL_BANK,
        )
        cache.putYieldCurve(curve2)

        val found = cache.getYieldCurve("USD-TREASURY")
        found.shouldNotBeNull()
        found.tenors[0].rate.compareTo(BigDecimal("0.0450")) shouldBe 0
    }
})

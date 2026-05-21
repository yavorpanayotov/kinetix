package com.kinetix.rates.service

import com.kinetix.common.model.*
import com.kinetix.rates.cache.RatesCache
import com.kinetix.rates.kafka.RatesPublisher
import com.kinetix.rates.persistence.ForwardCurveRepository
import com.kinetix.rates.persistence.RiskFreeRateRepository
import com.kinetix.rates.persistence.YieldCurveRepository
import io.kotest.core.spec.style.FunSpec
import io.mockk.*
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

private val USD = Currency.getInstance("USD")
private val NOW = Instant.parse("2026-02-24T10:00:00Z")

class RateIngestionServiceTest : FunSpec({

    val yieldCurveRepo = mockk<YieldCurveRepository>()
    val riskFreeRateRepo = mockk<RiskFreeRateRepository>()
    val forwardCurveRepo = mockk<ForwardCurveRepository>()
    val cache = mockk<RatesCache>()
    val publisher = mockk<RatesPublisher>()

    val service = RateIngestionService(yieldCurveRepo, riskFreeRateRepo, forwardCurveRepo, cache, publisher)

    beforeEach {
        clearMocks(yieldCurveRepo, riskFreeRateRepo, forwardCurveRepo, cache, publisher)
    }

    test("ingests yield curve by saving, caching, and publishing") {
        val curve = YieldCurve(
            currency = USD,
            asOf = NOW,
            tenors = listOf(
                Tenor.oneMonth(BigDecimal("0.04")),
                Tenor.oneYear(BigDecimal("0.05")),
            ),
            curveId = "USD-TREASURY",
            source = RateSource.CENTRAL_BANK,
        )

        coEvery { yieldCurveRepo.save(curve) } just Runs
        coEvery { cache.putYieldCurve(curve) } just Runs
        coEvery { publisher.publishYieldCurve(curve) } just Runs

        service.ingest(curve)

        coVerify(ordering = Ordering.ORDERED) {
            yieldCurveRepo.save(curve)
            cache.putYieldCurve(curve)
            publisher.publishYieldCurve(curve)
        }
    }

    test("ingests risk-free rate by saving, caching, and publishing") {
        val rate = RiskFreeRate(
            currency = USD,
            tenor = "3M",
            rate = 0.0525,
            asOfDate = NOW,
            source = RateSource.CENTRAL_BANK,
        )

        coEvery { riskFreeRateRepo.save(rate) } just Runs
        coEvery { cache.putRiskFreeRate(rate) } just Runs
        coEvery { publisher.publishRiskFreeRate(rate) } just Runs

        service.ingest(rate)

        coVerify(ordering = Ordering.ORDERED) {
            riskFreeRateRepo.save(rate)
            cache.putRiskFreeRate(rate)
            publisher.publishRiskFreeRate(rate)
        }
    }

    test("ingests forward curve by saving, caching, and publishing") {
        val curve = ForwardCurve(
            instrumentId = InstrumentId("EURUSD"),
            assetClass = AssetClass.FX,
            points = listOf(
                CurvePoint("1M", 1.0855),
                CurvePoint("3M", 1.0870),
            ),
            asOfDate = NOW,
            source = RateSource.REUTERS,
        )

        coEvery { forwardCurveRepo.save(curve) } just Runs
        coEvery { cache.putForwardCurve(curve) } just Runs
        coEvery { publisher.publishForwardCurve(curve) } just Runs

        service.ingest(curve)

        coVerify(ordering = Ordering.ORDERED) {
            forwardCurveRepo.save(curve)
            cache.putForwardCurve(curve)
            publisher.publishForwardCurve(curve)
        }
    }
})

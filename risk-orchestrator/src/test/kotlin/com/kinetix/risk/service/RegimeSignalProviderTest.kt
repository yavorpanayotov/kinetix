package com.kinetix.risk.service

import com.kinetix.common.model.CorrelationMatrix
import com.kinetix.common.model.EstimationMethod
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.PricePoint
import com.kinetix.common.model.PriceSource
import com.kinetix.risk.client.ClientResponse
import com.kinetix.risk.client.CorrelationServiceClient
import com.kinetix.risk.client.PriceServiceClient
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

private val USD: Currency = Currency.getInstance("USD")
private val BENCHMARK = InstrumentId("SPX")

private fun pricePoint(price: Double, daysAgo: Long): PricePoint = PricePoint(
    instrumentId = BENCHMARK,
    price = Money(BigDecimal.valueOf(price), USD),
    timestamp = Instant.parse("2026-05-20T00:00:00Z").minusSeconds(daysAgo * 86_400),
    source = PriceSource.EXCHANGE,
)

private fun matrix(labels: List<String>, values: List<Double>) = CorrelationMatrix(
    labels = labels,
    values = values,
    windowDays = 252,
    asOfDate = Instant.parse("2026-05-20T00:00:00Z"),
    method = EstimationMethod.SHRINKAGE,
)

class RegimeSignalProviderTest : FunSpec({

    fun provider(
        priceClient: PriceServiceClient,
        correlationClient: CorrelationServiceClient,
    ) = RegimeSignalProvider(
        priceServiceClient = priceClient,
        correlationServiceClient = correlationClient,
        benchmarkInstrumentId = BENCHMARK,
        correlationLabels = listOf("EQUITY", "RATES", "CREDIT"),
        clock = { Instant.parse("2026-05-20T00:00:00Z") },
    )

    test("cross-asset correlation is the average off-diagonal of the matrix") {
        val priceClient = mockk<PriceServiceClient>()
        coEvery { priceClient.getPriceHistory(any(), any(), any(), any()) } returns
            ClientResponse.Success(emptyList())

        val correlationClient = mockk<CorrelationServiceClient>()
        // 3x3 matrix, off-diagonal entries: 0.5, 0.6, 0.5, 0.7, 0.6, 0.7 -> avg 0.6
        coEvery { correlationClient.getCorrelationMatrix(any(), any()) } returns
            ClientResponse.Success(
                matrix(
                    labels = listOf("EQUITY", "RATES", "CREDIT"),
                    values = listOf(
                        1.0, 0.5, 0.6,
                        0.5, 1.0, 0.7,
                        0.6, 0.7, 1.0,
                    ),
                )
            )

        val signals = provider(priceClient, correlationClient).gather()
        signals.crossAssetCorrelation shouldBe 0.6
    }

    test("realised vol is positive when the benchmark has a volatile price history") {
        val priceClient = mockk<PriceServiceClient>()
        val history = listOf(
            pricePoint(100.0, 5),
            pricePoint(104.0, 4),
            pricePoint(98.0, 3),
            pricePoint(105.0, 2),
            pricePoint(99.0, 1),
        )
        coEvery { priceClient.getPriceHistory(any(), any(), any(), any()) } returns
            ClientResponse.Success(history)

        val correlationClient = mockk<CorrelationServiceClient>()
        coEvery { correlationClient.getCorrelationMatrix(any(), any()) } returns
            ClientResponse.NotFound(404)

        val signals = provider(priceClient, correlationClient).gather()
        signals.realisedVol20d shouldBeGreaterThan 0.0
    }

    test("realised vol defaults to zero when price history is unavailable") {
        val priceClient = mockk<PriceServiceClient>()
        coEvery { priceClient.getPriceHistory(any(), any(), any(), any()) } returns
            ClientResponse.ServiceUnavailable()

        val correlationClient = mockk<CorrelationServiceClient>()
        coEvery { correlationClient.getCorrelationMatrix(any(), any()) } returns
            ClientResponse.Success(matrix(listOf("A", "B"), listOf(1.0, 0.3, 0.3, 1.0)))

        val signals = provider(priceClient, correlationClient).gather()
        signals.realisedVol20d shouldBe 0.0
    }

    test("cross-asset correlation defaults to zero when no matrix is available") {
        val priceClient = mockk<PriceServiceClient>()
        coEvery { priceClient.getPriceHistory(any(), any(), any(), any()) } returns
            ClientResponse.Success(emptyList())

        val correlationClient = mockk<CorrelationServiceClient>()
        coEvery { correlationClient.getCorrelationMatrix(any(), any()) } returns
            ClientResponse.NetworkError(RuntimeException("down"))

        val signals = provider(priceClient, correlationClient).gather()
        signals.crossAssetCorrelation shouldBe 0.0
    }

    test("optional credit and pnl signals are null so the detector runs in degraded mode") {
        val priceClient = mockk<PriceServiceClient>()
        coEvery { priceClient.getPriceHistory(any(), any(), any(), any()) } returns
            ClientResponse.Success(emptyList())

        val correlationClient = mockk<CorrelationServiceClient>()
        coEvery { correlationClient.getCorrelationMatrix(any(), any()) } returns
            ClientResponse.Success(matrix(listOf("A", "B"), listOf(1.0, 0.3, 0.3, 1.0)))

        val signals = provider(priceClient, correlationClient).gather()
        signals.creditSpreadBps shouldBe null
        signals.pnlVolatility shouldBe null
    }
})

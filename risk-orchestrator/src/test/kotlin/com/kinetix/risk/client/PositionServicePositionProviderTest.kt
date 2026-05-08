package com.kinetix.risk.client

import com.kinetix.common.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.math.BigDecimal
import java.util.Currency

private val USD = Currency.getInstance("USD")

class PositionServicePositionProviderTest : FunSpec({

    val client = mockk<PositionServiceClient>()
    val provider = PositionServicePositionProvider(client)

    test("delegates to PositionServiceClient.getPositions") {
        val bookId = BookId("port-1")
        val positions = listOf(
            Position(
                bookId = bookId,
                instrumentId = InstrumentId("AAPL"),
                assetClass = AssetClass.EQUITY,
                quantity = BigDecimal("100"),
                averageCost = Money(BigDecimal("150.00"), USD),
                marketPrice = Money(BigDecimal("170.00"), USD),
                instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
            ),
        )

        coEvery { client.getPositions(bookId) } returns ClientResponse.Success(positions)

        val result = provider.getPositions(bookId)

        result shouldHaveSize 1
        result[0].instrumentId shouldBe InstrumentId("AAPL")
        coVerify(exactly = 1) { client.getPositions(bookId) }
    }

    test("returns empty list when no positions exist") {
        val bookId = BookId("empty-port")
        coEvery { client.getPositions(bookId) } returns ClientResponse.Success(emptyList())

        val result = provider.getPositions(bookId)

        result shouldHaveSize 0
    }

    test("returns empty list when client returns NotFound") {
        val bookId = BookId("missing-port")
        coEvery { client.getPositions(bookId) } returns ClientResponse.NotFound(404)

        val result = provider.getPositions(bookId)

        result shouldHaveSize 0
    }

    test("returns empty list when client returns ServiceUnavailable") {
        val bookId = BookId("port-503")
        coEvery { client.getPositions(bookId) } returns ClientResponse.ServiceUnavailable()

        val result = provider.getPositions(bookId)

        result shouldHaveSize 0
    }

    test("returns empty list when client returns UpstreamError") {
        val bookId = BookId("port-500")
        coEvery { client.getPositions(bookId) } returns ClientResponse.UpstreamError(500, "DB failure")

        val result = provider.getPositions(bookId)

        result shouldHaveSize 0
    }

    test("returns empty list when client returns NetworkError") {
        val bookId = BookId("port-net-fail")
        coEvery { client.getPositions(bookId) } returns ClientResponse.NetworkError(java.io.IOException("timeout"))

        val result = provider.getPositions(bookId)

        result shouldHaveSize 0
    }
})

package com.kinetix.common.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

private val USD = Currency.getInstance("USD")
private val AAPL = InstrumentId("AAPL")
private fun usd(amount: String) = Money(BigDecimal(amount), USD)

private fun pricePoint(
    instrumentId: InstrumentId = AAPL,
    price: Money = usd("150.00"),
    timestamp: Instant = Instant.parse("2025-06-15T14:30:00Z"),
    source: PriceSource = PriceSource.BLOOMBERG,
) = PricePoint(
    instrumentId = instrumentId,
    price = price,
    timestamp = timestamp,
    source = source,
)

class PricePointTest : FunSpec({

    test("create PricePoint with valid fields") {
        val point = pricePoint()
        point.instrumentId shouldBe AAPL
        point.price shouldBe usd("150.00")
        point.timestamp shouldBe Instant.parse("2025-06-15T14:30:00Z")
        point.source shouldBe PriceSource.BLOOMBERG
    }

    test("PricePoint with zero price is allowed") {
        val point = pricePoint(price = usd("0.00"))
        point.price.amount.compareTo(BigDecimal.ZERO) shouldBe 0
    }

    test("PricePoint with negative price throws IllegalArgumentException") {
        shouldThrow<IllegalArgumentException> {
            pricePoint(price = usd("-1.00"))
        }
    }

    test("equal PricePoints are equal") {
        pricePoint() shouldBe pricePoint()
    }

    test("PricePoints with different timestamps are not equal") {
        pricePoint(timestamp = Instant.parse("2025-06-15T14:30:00Z")) shouldNotBe
            pricePoint(timestamp = Instant.parse("2025-06-15T15:00:00Z"))
    }

    test("PricePoints with different sources are not equal") {
        pricePoint(source = PriceSource.BLOOMBERG) shouldNotBe
            pricePoint(source = PriceSource.REUTERS)
    }

    test("PriceSource enum has all expected values") {
        PriceSource.entries.map { it.name } shouldBe
            listOf("BLOOMBERG", "REUTERS", "EXCHANGE", "INTERNAL", "MANUAL")
    }

    test("PriceSource display names are human-readable") {
        PriceSource.BLOOMBERG.displayName shouldBe "Bloomberg"
        PriceSource.REUTERS.displayName shouldBe "Reuters"
        PriceSource.EXCHANGE.displayName shouldBe "Exchange"
        PriceSource.INTERNAL.displayName shouldBe "Internal"
        PriceSource.MANUAL.displayName shouldBe "Manual"
    }

    test("PricePoint price is compatible with Position.markToMarket") {
        val point = pricePoint(price = usd("155.00"))
        val position = Position(
            bookId = BookId("p1"),
            instrumentId = AAPL,
            assetClass = AssetClass.EQUITY,
            quantity = java.math.BigDecimal.ZERO,
            averageCost = Money.zero(USD),
            marketPrice = Money.zero(USD),
            instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
        )
        val updated = position.markToMarket(point.price)
        updated.marketPrice shouldBe usd("155.00")
    }
})

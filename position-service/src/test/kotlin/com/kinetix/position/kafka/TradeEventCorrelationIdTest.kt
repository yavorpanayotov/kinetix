package com.kinetix.position.kafka

import com.kinetix.common.kafka.events.TradeEventMessage
import com.kinetix.common.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

private val USD = Currency.getInstance("USD")
private val UUID_REGEX = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

private fun trade(tradeId: String = "t-1") = Trade(
    tradeId = TradeId(tradeId),
    bookId = BookId("port-1"),
    instrumentId = InstrumentId("AAPL"),
    assetClass = AssetClass.EQUITY,
    side = Side.BUY,
    quantity = BigDecimal("100"),
    price = Money(BigDecimal("150.00"), USD),
    tradedAt = Instant.parse("2025-01-15T10:00:00Z"),
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

class TradeEventCorrelationIdTest : FunSpec({

    test("TradeEvent generates correlationId by default") {
        val event = TradeEvent(trade = trade())
        val message = TradeEventMessage.from(event)

        message.correlationId.shouldNotBeNull()
        message.correlationId!! shouldMatch UUID_REGEX
    }

    test("TradeEvent uses provided correlationId") {
        val event = TradeEvent(trade = trade(), correlationId = "my-correlation-id")
        val message = TradeEventMessage.from(event)

        message.correlationId shouldBe "my-correlation-id"
    }

    test("correlationId survives JSON round-trip") {
        val event = TradeEvent(trade = trade(), correlationId = "test-corr-123")
        val message = TradeEventMessage.from(event)
        val json = Json.encodeToString(message)
        val deserialized = Json.decodeFromString<TradeEventMessage>(json)

        deserialized.correlationId shouldBe "test-corr-123"
    }

    test("correlationId defaults to null for backward-compatible deserialization") {
        val jsonWithoutCorrelation = """{"tradeId":"t-1","bookId":"port-1","instrumentId":"AAPL","assetClass":"EQUITY","side":"BUY","quantity":"100","priceAmount":"150.00","priceCurrency":"USD","tradedAt":"2025-01-15T10:00:00Z"}"""
        val event = Json.decodeFromString<TradeEventMessage>(jsonWithoutCorrelation)

        event.correlationId shouldBe null
    }

    test("TradeEventMessage carries counterpartyId and strategyId from Trade") {
        val tradeWithCounterparty = trade().copy(
            counterpartyId = "CP-ACME",
            strategyId = "STRAT-MOMENTUM",
        )
        val event = TradeEvent(trade = tradeWithCounterparty)
        val message = TradeEventMessage.from(event)

        message.counterpartyId shouldBe "CP-ACME"
        message.strategyId shouldBe "STRAT-MOMENTUM"
    }

    test("counterpartyId and strategyId survive JSON round-trip") {
        val tradeWithCounterparty = trade().copy(
            counterpartyId = "CP-BANK",
            strategyId = "STRAT-VOL",
        )
        val event = TradeEvent(trade = tradeWithCounterparty)
        val message = TradeEventMessage.from(event)
        val json = Json.encodeToString(message)
        val deserialized = Json.decodeFromString<TradeEventMessage>(json)

        deserialized.counterpartyId shouldBe "CP-BANK"
        deserialized.strategyId shouldBe "STRAT-VOL"
    }

    test("counterpartyId and strategyId default to null when absent from Trade") {
        val event = TradeEvent(trade = trade())
        val message = TradeEventMessage.from(event)

        message.counterpartyId shouldBe null
        message.strategyId shouldBe null
    }

    test("counterpartyId/strategyId default to null for backward-compatible deserialization") {
        val jsonWithoutFields = """{"tradeId":"t-1","bookId":"port-1","instrumentId":"AAPL","assetClass":"EQUITY","side":"BUY","quantity":"100","priceAmount":"150.00","priceCurrency":"USD","tradedAt":"2025-01-15T10:00:00Z"}"""
        val event = Json.decodeFromString<TradeEventMessage>(jsonWithoutFields)

        event.counterpartyId shouldBe null
        event.strategyId shouldBe null
    }
})

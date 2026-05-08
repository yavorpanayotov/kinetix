package com.kinetix.fix.session

import com.kinetix.fix.venue.VenueSession
import com.kinetix.proto.execution.OrderType
import com.kinetix.proto.execution.PlaceOrderRequest
import com.kinetix.proto.execution.Side
import com.kinetix.proto.execution.TimeInForce
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * NewOrderSingleBuilder constructs FIX 35=D from a [PlaceOrderRequest]. Tests pin
 * each FIX tag the cancel acceptance test pins for 35=F (so cross-message field
 * recovery via fix_message_log works), plus the order-type / TIF / GTD-expiry
 * branches that 35=D adds.
 */
class NewOrderSingleBuilderTest : FunSpec({

    val nyse = VenueSession("NYSE", "FIX.4.4", "KINETIX", "NYSE", 200)
    val transactTime = Instant.parse("2026-05-04T20:30:15.250Z")

    fun limitBuyAaplDay(
        clOrdId: String = "ord-abc-123",
        quantity: String = "100",
        limitPrice: String = "150.25",
    ): PlaceOrderRequest = PlaceOrderRequest.newBuilder()
        .setClOrdId(clOrdId)
        .setVenue("NYSE")
        .setInstrumentId("AAPL")
        .setSide(Side.BUY)
        .setOrderType(OrderType.LIMIT)
        .setQuantity(quantity)
        .setLimitPrice(limitPrice)
        .setTimeInForce(TimeInForce.TIF_DAY)
        .build()

    test("builds 35=D LIMIT/DAY/BUY with ClOrdID, Symbol, Side, OrderQty, OrdType, Price, TIF, TransactTime") {
        val builder = NewOrderSingleBuilder()
        val message = builder.build(nyse, limitBuyAaplDay(), transactTime)

        message.header.getString(35) shouldBe "D"
        message.getString(11) shouldBe "ord-abc-123"   // ClOrdID
        message.getString(55) shouldBe "AAPL"          // Symbol
        message.getString(54) shouldBe "1"              // Side BUY
        message.getString(38) shouldBe "100"           // OrderQty
        message.getString(40) shouldBe "2"              // OrdType LIMIT
        message.getString(44) shouldBe "150.25"        // Price
        message.getString(59) shouldBe "0"              // TimeInForce DAY
    }

    test("MARKET orders omit Price (tag 44) and set OrdType to 1") {
        val builder = NewOrderSingleBuilder()
        val request = PlaceOrderRequest.newBuilder()
            .setClOrdId("ord-mkt")
            .setVenue("NYSE")
            .setInstrumentId("AAPL")
            .setSide(Side.SELL)
            .setOrderType(OrderType.MARKET)
            .setQuantity("50")
            .setTimeInForce(TimeInForce.TIF_IOC)
            .build()
        val message = builder.build(nyse, request, transactTime)

        message.getString(40) shouldBe "1"             // OrdType MARKET
        message.getString(54) shouldBe "2"              // Side SELL
        message.getString(59) shouldBe "3"              // TimeInForce IOC
        message.isSetField(44) shouldBe false           // Price omitted
    }

    test("GTD orders carry the ExpireTime (tag 126) populated from expires_at_iso") {
        val builder = NewOrderSingleBuilder()
        val request = PlaceOrderRequest.newBuilder()
            .setClOrdId("ord-gtd")
            .setVenue("NYSE")
            .setInstrumentId("AAPL")
            .setSide(Side.BUY)
            .setOrderType(OrderType.LIMIT)
            .setQuantity("10")
            .setLimitPrice("100.00")
            .setTimeInForce(TimeInForce.TIF_GTD)
            .setExpiresAtIso("2026-05-05T20:00:00Z")
            .build()
        val message = builder.build(nyse, request, transactTime)

        message.getString(59) shouldBe "6"              // TimeInForce GTD
        message.isSetField(126) shouldBe true
    }

    test("rejects zero/negative quantity") {
        val builder = NewOrderSingleBuilder()
        shouldThrow<IllegalArgumentException> {
            builder.build(nyse, limitBuyAaplDay(quantity = "0"), transactTime)
        }
        shouldThrow<IllegalArgumentException> {
            builder.build(nyse, limitBuyAaplDay(quantity = "-5"), transactTime)
        }
    }

    test("rejects LIMIT order without limit_price") {
        val builder = NewOrderSingleBuilder()
        val request = PlaceOrderRequest.newBuilder()
            .setClOrdId("ord-lim")
            .setVenue("NYSE")
            .setInstrumentId("AAPL")
            .setSide(Side.BUY)
            .setOrderType(OrderType.LIMIT)
            .setQuantity("100")
            .setTimeInForce(TimeInForce.TIF_DAY)
            .build()
        shouldThrow<IllegalArgumentException> {
            builder.build(nyse, request, transactTime)
        }
    }

    test("rejects GTD order without expires_at_iso") {
        val builder = NewOrderSingleBuilder()
        val request = PlaceOrderRequest.newBuilder()
            .setClOrdId("ord-gtd-bad")
            .setVenue("NYSE")
            .setInstrumentId("AAPL")
            .setSide(Side.BUY)
            .setOrderType(OrderType.LIMIT)
            .setQuantity("10")
            .setLimitPrice("100.00")
            .setTimeInForce(TimeInForce.TIF_GTD)
            .build()
        shouldThrow<IllegalArgumentException> {
            builder.build(nyse, request, transactTime)
        }
    }

    test("rejects unspecified Side / OrderType / TimeInForce") {
        val builder = NewOrderSingleBuilder()
        val request = PlaceOrderRequest.newBuilder()
            .setClOrdId("ord-unspec")
            .setVenue("NYSE")
            .setInstrumentId("AAPL")
            .setSide(Side.SIDE_UNSPECIFIED)
            .setOrderType(OrderType.LIMIT)
            .setQuantity("10")
            .setLimitPrice("100.00")
            .setTimeInForce(TimeInForce.TIF_DAY)
            .build()
        shouldThrow<IllegalArgumentException> {
            builder.build(nyse, request, transactTime)
        }
    }

    test("preserves negative limit prices verbatim (synthetic instruments allow negative)") {
        val builder = NewOrderSingleBuilder()
        val request = limitBuyAaplDay(limitPrice = "-1.00")
        val message = builder.build(nyse, request, transactTime)
        message.getString(44) shouldBe "-1.00"
    }
})

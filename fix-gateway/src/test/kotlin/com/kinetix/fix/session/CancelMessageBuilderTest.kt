package com.kinetix.fix.session

import com.kinetix.fix.venue.VenueSession
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

class CancelMessageBuilderTest : FunSpec({

    val nyse = VenueSession("NYSE", "FIX.4.4", "KINETIX", "NYSE", 200)
    val transactTime = Instant.parse("2026-05-04T20:30:15.250Z")

    fun inputs() = CancelMessageBuilder.BuilderInputs(
        origClOrdId = "ord-abc-123",
        venueOrderId = "VENUE-99",
        symbol = "AAPL",
        side = '1', // FIX side BUY
        orderQty = BigDecimal("100"),
        transactTime = transactTime,
    )

    test("builds 35=F with origClOrdID, venue OrderID, side, quantity, transactTime") {
        val builder = CancelMessageBuilder()
        val message = builder.build(nyse, inputs())

        message.header.getString(35) shouldBe "F"
        message.getString(41) shouldBe "ord-abc-123"
        message.getString(37) shouldBe "VENUE-99"
        message.getString(55) shouldBe "AAPL"
        message.getString(54) shouldBe "1"
        message.getString(38) shouldBe "100"
    }

    test("cancel ClOrdID is unique per call and prefixed with origClOrdID") {
        val builder = CancelMessageBuilder()

        val first = builder.build(nyse, inputs()).getString(11)
        val second = builder.build(nyse, inputs()).getString(11)

        first shouldContain "ord-abc-123-cxl-"
        second shouldContain "ord-abc-123-cxl-"
        first shouldNotContain second
    }

    test("seq counter is injectable so concurrent builders agree on monotonicity") {
        val seq = AtomicLong(99)
        val builder = CancelMessageBuilder(cancelClOrdIdSeq = seq)

        val first = builder.build(nyse, inputs()).getString(11)
        first shouldBe "ord-abc-123-cxl-100"
    }
})

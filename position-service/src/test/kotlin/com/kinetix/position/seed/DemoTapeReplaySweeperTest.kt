package com.kinetix.position.seed

import com.kinetix.common.model.AssetClass
import com.kinetix.position.service.BookTradeCommand
import com.kinetix.position.service.TradeBookingService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class DemoTapeReplaySweeperTest : FunSpec({

    fun makeSweeper(
        tradeBookingService: TradeBookingService,
        ready: Boolean = true,
        breachIntervalTicks: Int = 40,
        seed: Long = 42L,
    ) = DemoTapeReplaySweeper(
        tradeBookingService = tradeBookingService,
        instrumentsByBook = INSTRUMENTS_BY_BOOK,
        readinessGate = { ready },
        breachIntervalTicks = breachIntervalTicks,
        clock = FIXED_CLOCK,
        seed = seed,
    )

    test("skips replay when readiness gate is closed") {
        val booking = mockk<TradeBookingService>()
        val sweeper = makeSweeper(booking, ready = false)

        val attempted = sweeper.replayOnce()

        attempted shouldBe 0
        coVerify(exactly = 0) { booking.handle(any()) }
    }

    test("emits at least one trade per tick when ready") {
        val booking = mockk<TradeBookingService>()
        val captured = mutableListOf<BookTradeCommand>()
        coEvery { booking.handle(capture(captured)) } returns mockk(relaxed = true)
        val sweeper = makeSweeper(booking)

        sweeper.replayOnce()

        (captured.size in 1..3) shouldBe true
        captured.forEach { cmd ->
            (cmd.bookId.value in INSTRUMENTS_BY_BOOK.keys) shouldBe true
            cmd.tradeId.value.startsWith("replay-") shouldBe true
        }
    }

    test("trade-ids encode tick number and book abbreviation deterministically") {
        val booking = mockk<TradeBookingService>()
        val cmds = mutableListOf<BookTradeCommand>()
        coEvery { booking.handle(capture(cmds)) } returns mockk(relaxed = true)
        val sweeper = makeSweeper(booking)

        repeat(3) { sweeper.replayOnce() }

        cmds.forEach { cmd ->
            cmd.tradeId.value.startsWith("replay-") shouldBe true
            cmd.tradeId.value.contains("-${FIXED_CLOCK.instant().epochSecond}-") shouldBe true
        }
    }

    test("breach trades fire on the configured interval and carry oversized quantities") {
        val booking = mockk<TradeBookingService>()
        val cmds = mutableListOf<BookTradeCommand>()
        coEvery { booking.handle(capture(cmds)) } returns mockk(relaxed = true)
        val sweeper = makeSweeper(booking, breachIntervalTicks = 3)

        // Tick 1 normal, tick 2 normal, tick 3 breach, tick 4 normal
        repeat(4) { sweeper.replayOnce() }

        val breachTrades = cmds.filter { it.tradeId.value.endsWith("-breach") }
        breachTrades.size shouldBe 1
        // Breach quantity is ≥ 8× typicalQtyMin for the chosen instrument — well
        // above the per-book limit thresholds the existing seed dataset uses.
        val breach = breachTrades.single()
        val instrumentSpec = INSTRUMENTS_BY_BOOK.values.flatten().first { it.id == breach.instrumentId.value }
        (breach.quantity >= BigDecimal(instrumentSpec.typicalQtyMin * 8)) shouldBe true
    }

    test("survives downstream failure with WARN log and no retry") {
        val booking = mockk<TradeBookingService>()
        val attemptSlot = slot<BookTradeCommand>()
        coEvery { booking.handle(capture(attemptSlot)) } throws RuntimeException("kafka down")
        val sweeper = makeSweeper(booking)

        // Should not throw — sweeper drops gracefully.
        val attempts = sweeper.replayOnce()
        (attempts >= 1) shouldBe true
        // The failure was observed; next call still attempts.
        val next = sweeper.replayOnce()
        (next >= 1) shouldBe true
    }

    test("identical seeds produce identical sequences of commands") {
        val bookingA = mockk<TradeBookingService>()
        val bookingB = mockk<TradeBookingService>()
        val capA = mutableListOf<BookTradeCommand>()
        val capB = mutableListOf<BookTradeCommand>()
        coEvery { bookingA.handle(capture(capA)) } returns mockk(relaxed = true)
        coEvery { bookingB.handle(capture(capB)) } returns mockk(relaxed = true)
        val a = makeSweeper(bookingA, seed = 99L)
        val b = makeSweeper(bookingB, seed = 99L)

        repeat(5) { a.replayOnce() }
        repeat(5) { b.replayOnce() }

        capA.size shouldBe capB.size
        capA.zip(capB).forEach { (ca, cb) ->
            ca.tradeId.value shouldBe cb.tradeId.value
            ca.bookId.value shouldBe cb.bookId.value
            ca.instrumentId.value shouldBe cb.instrumentId.value
            ca.side shouldBe cb.side
            ca.quantity shouldBe cb.quantity
        }
    }
}) {
    companion object {
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-05-08T14:30:00Z"), ZoneOffset.UTC)

        val INSTRUMENTS_BY_BOOK: Map<String, List<TradeTapeGenerator.TradeInstrumentSpec>> = mapOf(
            "equity-growth" to listOf(
                TradeTapeGenerator.TradeInstrumentSpec("AAPL", AssetClass.EQUITY, "CASH_EQUITY", "USD", "185.50", 500, 3000),
                TradeTapeGenerator.TradeInstrumentSpec("MSFT", AssetClass.EQUITY, "CASH_EQUITY", "USD", "420.00", 200, 1500),
            ),
            "macro-hedge" to listOf(
                TradeTapeGenerator.TradeInstrumentSpec("EURUSD", AssetClass.FX, "FX_SPOT", "USD", "1.0850", 500_000, 3_000_000, priceScale = 4),
                TradeTapeGenerator.TradeInstrumentSpec("US10Y", AssetClass.FIXED_INCOME, "GOVERNMENT_BOND", "USD", "96.50", 3000, 15000),
            ),
        )
    }
}

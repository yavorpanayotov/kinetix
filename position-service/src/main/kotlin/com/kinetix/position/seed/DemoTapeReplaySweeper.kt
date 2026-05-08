package com.kinetix.position.seed

import com.kinetix.common.demo.TapeRng
import com.kinetix.common.demo.TradeTapeSampler
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Side
import com.kinetix.common.model.TradeId
import com.kinetix.position.service.BookTradeCommand
import com.kinetix.position.service.TradeBookingService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.util.Currency
import kotlin.coroutines.coroutineContext

/**
 * Phase 1 Gap 7 — intraday tape replay for position-service.
 *
 * Generates 1–3 trades/minute through [TradeBookingService.handle] so the demo
 * has a pulse: blotter scrolls, audit chain extends, risk recalcs fire. Every
 * `breachIntervalTicks` ticks, the sweeper emits an oversized "breach trade"
 * intended to push a book over its limit and trigger the limit-breach event /
 * notification path.
 *
 * Hard requirements per the demo-review.md plan:
 * - Coroutine flag, gated by `DEMO_TAPE_REPLAY_ENABLED=true`. Same lifecycle
 *   pattern as [com.kinetix.position.fix.ScheduledOrderExpirySweeper].
 * - Deterministic trade-id per replay tick (timestamp + book + sequence) so
 *   behaviour is reproducible across restarts.
 * - Drops gracefully if the booking path is briefly unavailable: logs WARN, no
 *   retry, the next tick fires regardless.
 * - Readiness gate: skip until [readinessGate] returns true (the most recent
 *   reset / startup seed completed). Avoids replaying onto a half-seeded DB.
 *
 * @param tradeBookingService Production booking path — exercises Kafka, audit,
 *                            limit checks, and risk recalc as a real fill would.
 * @param instrumentsByBook   Per-book instrument catalogue (same shape used by
 *                            the trade tape generator).
 * @param readinessGate       Returns true once the local service has completed
 *                            seed/reset and is safe to replay against.
 * @param intervalMillis      Tick cadence. 30s → 2 trades/min by default.
 * @param breachIntervalTicks ~20 min at 30s → 40 ticks. Every Nth tick emits a
 *                            breach trade.
 * @param breachQuantityScale Multiplier on the typical max quantity for breach
 *                            trades. Large enough to push past book-level limits.
 * @param clock               Injectable for tests.
 * @param seed                LCG seed for determinism.
 */
class DemoTapeReplaySweeper(
    private val tradeBookingService: TradeBookingService,
    private val instrumentsByBook: Map<String, List<TradeTapeGenerator.TradeInstrumentSpec>>,
    private val readinessGate: () -> Boolean,
    private val intervalMillis: Long = 30_000L,
    private val breachIntervalTicks: Int = 40,
    private val breachQuantityScale: Int = 8,
    private val clock: Clock = Clock.systemUTC(),
    private val seed: Long = TapeRng.stableSeed("demo-replay"),
) {
    private val log = LoggerFactory.getLogger(DemoTapeReplaySweeper::class.java)
    private val rng = TapeRng(seed)
    private var sideSignal: Double = 0.0
    private var tickCount: Long = 0
    private val books: List<String> = instrumentsByBook.keys.toList()

    /** Long-lived coroutine. Cancellation propagates via [coroutineContext]. */
    suspend fun start() {
        log.info(
            "Demo tape replay starting: {}ms interval, breach every {} ticks, {} books",
            intervalMillis, breachIntervalTicks, books.size,
        )
        while (coroutineContext.isActive) {
            replayOnce()
            delay(intervalMillis)
        }
    }

    /**
     * Single replay tick. Public for tests. Returns the number of trades that
     * the sweeper attempted to book this tick (regardless of success).
     */
    suspend fun replayOnce(): Int {
        if (!readinessGate()) {
            log.debug("Replay tick skipped — readiness gate is closed")
            return 0
        }
        if (books.isEmpty()) return 0

        tickCount++
        val isBreachTick = tickCount % breachIntervalTicks == 0L
        val tradesThisTick = if (isBreachTick) 1 else 1 + rng.nextLong().mod(2L).toInt()
        var attempted = 0
        for (i in 0 until tradesThisTick) {
            val command = nextCommand(isBreach = isBreachTick && i == 0)
            attempted++
            try {
                tradeBookingService.handle(command)
            } catch (t: Throwable) {
                // Drop gracefully — the next tick fires regardless. WARN only;
                // the booking path is allowed to push back briefly during demos.
                log.warn(
                    "Demo replay tick failed for trade {}: {}",
                    command.tradeId.value,
                    t.message,
                )
            }
        }
        if (isBreachTick) {
            log.info("Demo replay emitted breach trade on tick {}", tickCount)
        }
        return attempted
    }

    private fun nextCommand(isBreach: Boolean): BookTradeCommand {
        val book = books[rng.nextLong().mod(books.size.toLong()).toInt()]
        val instruments = instrumentsByBook[book]!!
        val zipfWeights = TradeTapeSampler.zipfWeights(instruments.size, alpha = 1.4)
        val instrument = instruments[TradeTapeSampler.sampleWeightedIndex(rng, zipfWeights)]

        val (newSignal, isBuy) = TradeTapeSampler.nextSideWithMomentum(rng, sideSignal)
        sideSignal = newSignal
        val side = if (isBuy) Side.BUY else Side.SELL

        val baseQtyDraw = instrument.typicalQtyMin +
            (rng.nextUniform() * (instrument.typicalQtyMax - instrument.typicalQtyMin)).toInt()
        val qty = if (isBreach) {
            BigDecimal(baseQtyDraw.toLong() * breachQuantityScale)
        } else {
            BigDecimal(baseQtyDraw)
        }

        val priceShock = (rng.nextNormal() * 0.005).coerceIn(-0.02, 0.02)
        val price = BigDecimal(instrument.typicalPrice)
            .multiply(BigDecimal(1.0 + priceShock))
            .setScale(instrument.priceScale, RoundingMode.HALF_UP)

        val now = clock.instant()
        val tradeId = deterministicTradeId(book, tickCount, now, isBreach)

        return BookTradeCommand(
            tradeId = TradeId(tradeId),
            bookId = BookId(book),
            instrumentId = InstrumentId(instrument.id),
            assetClass = instrument.assetClass,
            side = side,
            quantity = qty,
            price = Money(price, Currency.getInstance(instrument.currency)),
            tradedAt = now,
            instrumentType = instrument.instrumentType,
            counterpartyId = pickCounterparty(),
        )
    }

    private fun deterministicTradeId(book: String, tick: Long, at: Instant, isBreach: Boolean): String {
        val abbrev = book.replace("-", "").take(3)
        val suffix = if (isBreach) "-breach" else ""
        return "replay-$abbrev-${at.epochSecond}-$tick$suffix"
    }

    private fun pickCounterparty(): String {
        // Replay trades go to a small, stable rotation of G-SIBs so the live
        // counterparty exposure widget shows steady churn against known names.
        val rotation = listOf("CP-GS", "CP-JPM", "CP-BARC", "CP-UBS")
        return rotation[rng.nextLong().mod(rotation.size.toLong()).toInt()]
    }
}

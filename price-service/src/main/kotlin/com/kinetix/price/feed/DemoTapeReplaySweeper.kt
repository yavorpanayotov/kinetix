package com.kinetix.price.feed

import com.kinetix.common.model.PriceSource
import com.kinetix.price.service.PriceIngestionService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.slf4j.LoggerFactory
import java.time.Clock
import kotlin.coroutines.coroutineContext

/**
 * Phase 1 Gap 7 — intraday tape replay for price-service.
 *
 * Drives one price tick across the configured instrument universe every
 * `intervalMillis`, forwarding each tick into [PriceIngestionService.ingest] so
 * price subscribers, the WebSocket fan-out, and the latest-price cache all
 * refresh during the demo. The simulation core is the existing
 * [PriceFeedSimulator] — its GBM/mean-reversion paths produce realistic enough
 * intraday motion that the demo blotter and risk dashboards have a pulse.
 *
 * Hard requirements per the demo-review.md plan:
 * - Coroutine flag, gated by `DEMO_TAPE_REPLAY_ENABLED=true`. Same lifecycle
 *   pattern as position-service's [com.kinetix.position.seed.DemoTapeReplaySweeper].
 * - Readiness gate: skip ticks until [readinessGate] returns true (seed/reset
 *   completed). Avoids replaying onto a half-seeded database.
 * - Drops gracefully on downstream failure: logs WARN, no retry. The next tick
 *   fires regardless.
 *
 * @param simulator       Existing GBM/mean-reversion simulator.
 * @param ingestionService Same path the live price feed uses.
 * @param readinessGate   Returns true once the local service has completed
 *                        seed/reset and is safe to publish prices into.
 * @param intervalMillis  Tick cadence. 5s → 12 ticks/min by default for visible motion.
 * @param clock           Injectable for tests.
 */
class DemoTapeReplaySweeper(
    private val simulator: PriceFeedSimulator,
    private val ingestionService: PriceIngestionService,
    private val readinessGate: () -> Boolean,
    private val intervalMillis: Long = 5_000L,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(DemoTapeReplaySweeper::class.java)

    suspend fun start() {
        log.info("Demo price replay starting: {}ms interval", intervalMillis)
        while (coroutineContext.isActive) {
            replayOnce()
            delay(intervalMillis)
        }
    }

    /** Single replay tick. Public for tests. Returns the number of price points published. */
    suspend fun replayOnce(): Int {
        if (!readinessGate()) {
            log.debug("Price replay tick skipped — readiness gate is closed")
            return 0
        }
        val now = clock.instant()
        return try {
            val ticks = simulator.tick(now, PriceSource.INTERNAL)
            for (point in ticks) {
                try {
                    ingestionService.ingest(point)
                } catch (t: Throwable) {
                    // Drop one bad point; keep going for the rest of the tick.
                    log.warn(
                        "Price replay ingest failed for {}: {}",
                        point.instrumentId.value,
                        t.message,
                    )
                }
            }
            ticks.size
        } catch (t: Throwable) {
            log.warn("Price replay tick failed: {}", t.message)
            0
        }
    }

    /**
     * Convenience: build a sweeper from the seed instrument list — keeps
     * Application.kt wiring readable.
     */
    companion object {
        fun fromSeeds(
            seeds: List<InstrumentSeed>,
            ingestionService: PriceIngestionService,
            readinessGate: () -> Boolean,
            intervalMillis: Long = 5_000L,
            clock: Clock = Clock.systemUTC(),
            tickIntervalSeconds: Double = 5.0,
        ): DemoTapeReplaySweeper {
            val simulator = PriceFeedSimulator(seeds, tickIntervalSeconds = tickIntervalSeconds)
            return DemoTapeReplaySweeper(
                simulator = simulator,
                ingestionService = ingestionService,
                readinessGate = readinessGate,
                intervalMillis = intervalMillis,
                clock = clock,
            )
        }
    }
}

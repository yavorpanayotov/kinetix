package com.kinetix.demo.schedule

import com.kinetix.demo.client.PositionServiceClient
import com.kinetix.demo.client.dtos.PrimeBrokerPositionDto
import com.kinetix.demo.client.dtos.PrimeBrokerStatementRequest
import com.kinetix.demo.client.dtos.RecordExecutionCostRequest
import com.kinetix.demo.client.dtos.StrategyTradeRequest
import com.kinetix.demo.profile.DemoBookProfile
import com.kinetix.demo.profile.DemoBookProfiles
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID
import kotlin.random.Random

/**
 * Books a small, plausible burst of simulated trades against `position-service`
 * each time [runTick] is called inside UTC trading hours on Mon-Fri.
 *
 * For every [DemoBookProfile] in [books]:
 *  - the job rolls `random.nextDouble() <= profile.tradeProbability`,
 *  - on a hit, generates 1..3 trades drawn from the profile's `instrumentIds`
 *    and `notionalRangeUsd`,
 *  - posts each trade via [PositionServiceClient.bookTrade].
 *
 * Outside trading hours, or on weekends, [runTick] returns 0 without touching
 * the position client. The cron / cadence loop lives in `Application.kt`
 * (checkbox 2.3) — this class is intentionally just one tick.
 *
 * ## Counterparty assignment
 *
 * The plan calls for cycling through the 6 `DevDataSeeder` counterparties, but
 * the wire [StrategyTradeRequest] does not carry a counterparty field — that
 * mapping lives on the book itself in `position-service`. Counterparty cycling
 * is therefore deferred; the simulated trade simply inherits the book's
 * existing primary trader/counterparty configuration server-side.
 *
 * ## Pricing
 *
 * Prices come from the in-memory [DefaultPriceBook] (per-asset-class
 * indicative spots). Real `price-service` integration is out of scope here
 * and lands in a later checkbox.
 *
 * ## Execution-cost and reconciliation seeding
 *
 * After each successfully booked trade the job posts one synthetic
 * execution-cost sample via
 * [PositionServiceClient.recordExecutionCost] so the Trades > Execution Cost
 * subtab renders non-empty data. For a deterministically sampled ~5% of
 * trades it also uploads a prime-broker statement that deliberately
 * mismatches the book's internal positions via
 * [PositionServiceClient.uploadPrimeBrokerStatement], so the server-side
 * reconciliation produces at least one break row for the Trades >
 * Reconciliation subtab. Both samples derive every value from the injected
 * seeded [Random] — there is no unseeded randomness anywhere in the job.
 *
 * @property positionClient wire to `position-service` `/strategies/{id}/trades`.
 * @property strategyIdResolver maps `bookId -> List<strategyId>` so each
 *     trade can be uniformly distributed across the book's seeded
 *     sub-strategies (see [DefaultStrategyIdResolver]).
 * @property priceBook indicative per-unit USD prices used to derive quantity
 *     from notional until `price-service` is wired in.
 * @property books profiles to iterate per tick. Defaults to the canonical 8.
 * @property tradingHoursStart inclusive UTC trading-window start.
 * @property tradingHoursEnd inclusive UTC trading-window end.
 * @property clock pluggable clock — UTC in production, fixed in tests.
 * @property random pluggable RNG — `Random.Default` in production, seeded
 *     in tests so assertions are deterministic.
 * @property reconciliationBreakProbability fraction of booked trades that
 *     also trigger a deliberately mismatched prime-broker statement upload.
 */
class SimulatedTraderJob(
    private val positionClient: PositionServiceClient,
    private val strategyIdResolver: StrategyIdResolver,
    private val priceBook: DefaultPriceBook = DefaultPriceBook(),
    private val books: List<DemoBookProfile> = DemoBookProfiles.all(),
    private val tradingHoursStart: LocalTime,
    private val tradingHoursEnd: LocalTime,
    private val clock: Clock = Clock.systemUTC(),
    private val random: Random = Random.Default,
    private val reconciliationBreakProbability: Double = DEFAULT_RECONCILIATION_BREAK_PROBABILITY,
) {

    private val logger = LoggerFactory.getLogger(SimulatedTraderJob::class.java)

    /**
     * Performs a single tick. Returns the number of trades successfully posted
     * to `position-service` — useful for metrics and for deterministic
     * assertions in unit tests.
     */
    suspend fun runTick(): Int {
        val now = ZonedDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

        if (now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY) {
            logger.debug("Skipping SimulatedTraderJob tick — {} is a weekend", now.dayOfWeek)
            return 0
        }

        val time = now.toLocalTime()
        if (time.isBefore(tradingHoursStart) || time.isAfter(tradingHoursEnd)) {
            logger.debug(
                "Skipping SimulatedTraderJob tick — {} UTC is outside trading hours {}–{}",
                time,
                tradingHoursStart,
                tradingHoursEnd,
            )
            return 0
        }

        var posted = 0
        for (profile in books) {
            if (random.nextDouble() > profile.tradeProbability) {
                continue
            }
            val tradesThisTick = random.nextInt(1, 4) // 1..3 inclusive
            repeat(tradesThisTick) {
                if (tryBookOneTrade(profile)) {
                    posted += 1
                }
            }
        }
        return posted
    }

    private suspend fun tryBookOneTrade(profile: DemoBookProfile): Boolean {
        val request = buildTradeRequest(profile)
        val strategies = strategyIdResolver.strategiesFor(profile.bookId)
        require(strategies.isNotEmpty()) {
            "StrategyIdResolver returned no strategies for bookId=${profile.bookId}"
        }
        // Uniform-random pick from the book's seeded strategies so trades
        // distribute roughly evenly across `core`/`satellite`,
        // `vol-arb`/`directional`/`hedge`, etc., instead of all landing on
        // one synthesized "{bookId}-default" strategy (kx-bg3).
        val strategyId = strategies[random.nextInt(strategies.size)]
        return try {
            positionClient.bookTrade(
                bookId = profile.bookId,
                strategyId = strategyId,
                request = request,
            )
            seedExecutionCost(profile, request)
            maybeSeedReconciliationBreak(profile, request)
            true
        } catch (failure: Exception) {
            logger.warn(
                "Failed to book simulated trade for book {} (strategy {}) — continuing",
                profile.bookId,
                strategyId,
                failure,
            )
            false
        }
    }

    /**
     * Posts a synthetic execution-cost sample for the just-booked [trade].
     * All metrics are derived from the injected seeded [random] so the demo
     * data is deterministic. A seeding failure is logged but does not fail
     * the trade — the trade itself has already booked successfully.
     */
    private suspend fun seedExecutionCost(profile: DemoBookProfile, trade: StrategyTradeRequest) {
        val arrivalPrice = BigDecimal(trade.priceAmount)
        // Slippage in basis points: ±25 bps drawn from the seeded RNG.
        val slippageBps = BigDecimal.valueOf(random.nextDouble(-25.0, 25.0))
            .setScale(SLIPPAGE_SCALE, RoundingMode.HALF_UP)
        // Recover an average fill price consistent with the slippage figure:
        // avgFill = arrival * (1 + slippageBps / 10_000).
        val averageFillPrice = arrivalPrice
            .multiply(BigDecimal.ONE + slippageBps.divide(BPS_DENOMINATOR, 10, RoundingMode.HALF_UP))
            .setScale(PRICE_SCALE, RoundingMode.HALF_UP)
        val marketImpactBps = BigDecimal.valueOf(random.nextDouble(0.0, 10.0))
            .setScale(SLIPPAGE_SCALE, RoundingMode.HALF_UP)
        val timingCostBps = BigDecimal.valueOf(random.nextDouble(0.0, 5.0))
            .setScale(SLIPPAGE_SCALE, RoundingMode.HALF_UP)
        val totalCostBps = (slippageBps + marketImpactBps + timingCostBps)
            .setScale(SLIPPAGE_SCALE, RoundingMode.HALF_UP)

        val request = RecordExecutionCostRequest(
            orderId = "demo-ord-${UUID.randomUUID()}",
            instrumentId = trade.instrumentId,
            completedAt = trade.tradedAt,
            arrivalPrice = arrivalPrice.toPlainString(),
            averageFillPrice = averageFillPrice.toPlainString(),
            side = trade.side,
            totalQty = trade.quantity,
            slippageBps = slippageBps.toPlainString(),
            marketImpactBps = marketImpactBps.toPlainString(),
            timingCostBps = timingCostBps.toPlainString(),
            totalCostBps = totalCostBps.toPlainString(),
        )
        try {
            positionClient.recordExecutionCost(profile.bookId, request)
        } catch (failure: Exception) {
            logger.warn(
                "Failed to seed execution cost for book {} instrument {} — continuing",
                profile.bookId,
                trade.instrumentId,
                failure,
            )
        }
    }

    /**
     * For a deterministically sampled fraction of trades, uploads a
     * prime-broker statement whose quantity for the traded instrument
     * deliberately differs from the book's internal position by more than the
     * server-side auto-resolve threshold (1 unit), so the reconciliation
     * produces at least one break row.
     */
    private suspend fun maybeSeedReconciliationBreak(
        profile: DemoBookProfile,
        trade: StrategyTradeRequest,
    ) {
        if (random.nextDouble() > reconciliationBreakProbability) {
            return
        }
        val tradedQty = BigDecimal(trade.quantity)
        // Subtract a clear, > 1-unit delta so the break is material on the
        // server side regardless of the book's other positions.
        val mismatchedQty = (tradedQty - RECONCILIATION_BREAK_DELTA).max(BigDecimal.ZERO)
        val date = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).toString()
        val request = PrimeBrokerStatementRequest(
            bookId = profile.bookId,
            date = date,
            positions = listOf(
                PrimeBrokerPositionDto(
                    instrumentId = trade.instrumentId,
                    quantity = mismatchedQty.toPlainString(),
                    price = trade.priceAmount,
                ),
            ),
        )
        try {
            positionClient.uploadPrimeBrokerStatement(profile.bookId, request)
        } catch (failure: Exception) {
            logger.warn(
                "Failed to seed reconciliation break for book {} instrument {} — continuing",
                profile.bookId,
                trade.instrumentId,
                failure,
            )
        }
    }

    private fun buildTradeRequest(profile: DemoBookProfile): StrategyTradeRequest {
        val instrumentId = profile.instrumentIds[random.nextInt(profile.instrumentIds.size)]
        val side = if (random.nextBoolean()) "BUY" else "SELL"
        val notional = random.nextLong(
            profile.notionalRangeUsd.first,
            profile.notionalRangeUsd.last + 1,
        )
        val price = priceBook.priceFor(profile.assetClass)
        val quantity = maxOf(
            1L,
            notional.toBigDecimal()
                .divide(price, 0, RoundingMode.HALF_UP)
                .toLong(),
        )
        val tradedAt = clock.instant().toString()

        return StrategyTradeRequest(
            tradeId = null,
            instrumentId = instrumentId,
            assetClass = profile.assetClass,
            side = side,
            quantity = quantity.toString(),
            priceAmount = price.toPlainString(),
            priceCurrency = "USD",
            tradedAt = tradedAt,
            instrumentType = instrumentTypeFor(profile.assetClass),
            userId = "demo-orchestrator",
            userRole = "DEMO",
        )
    }

    /**
     * Maps the profile's free-form `assetClass` to a `position-service`
     * [com.kinetix.common.model.instrument.InstrumentTypeCode] name. We
     * deliberately use the upstream enum names (not the placeholder names in
     * the plan) so `position-service` does not reject the trade at the
     * `InstrumentTypeCode.fromString` boundary.
     */
    private fun instrumentTypeFor(assetClass: String): String = when (assetClass) {
        "EQUITY" -> "CASH_EQUITY"
        "FX" -> "FX_SPOT"
        "FIXED_INCOME" -> "GOVERNMENT_BOND"
        "COMMODITY" -> "COMMODITY_FUTURE"
        "DERIVATIVE" -> "EQUITY_OPTION"
        else -> "CASH_EQUITY"
    }

    companion object {
        /** ~5% of booked trades also produce a reconciliation break. */
        const val DEFAULT_RECONCILIATION_BREAK_PROBABILITY: Double = 0.05

        /** Decimal scale for basis-point execution-cost metrics. */
        private const val SLIPPAGE_SCALE: Int = 4

        /** Decimal scale for derived price figures. */
        private const val PRICE_SCALE: Int = 6

        /** Basis-point denominator: 1 bp = 1/10_000. */
        private val BPS_DENOMINATOR: BigDecimal = BigDecimal.valueOf(10_000)

        /**
         * Quantity delta applied to a prime-broker statement to force a
         * material break. Comfortably above the server's 1-unit auto-resolve
         * threshold.
         */
        private val RECONCILIATION_BREAK_DELTA: BigDecimal = BigDecimal.valueOf(10)
    }
}

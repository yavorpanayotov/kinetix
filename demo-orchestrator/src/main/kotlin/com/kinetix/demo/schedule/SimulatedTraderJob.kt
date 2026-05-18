package com.kinetix.demo.schedule

import com.kinetix.demo.client.PositionServiceClient
import com.kinetix.demo.client.dtos.StrategyTradeRequest
import com.kinetix.demo.profile.DemoBookProfile
import com.kinetix.demo.profile.DemoBookProfiles
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
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
 * @property positionClient wire to `position-service` `/strategies/{id}/trades`.
 * @property strategyIdResolver maps `bookId -> strategyId` (strategies are not
 *     pre-seeded; see [DefaultStrategyIdResolver]).
 * @property priceBook indicative per-unit USD prices used to derive quantity
 *     from notional until `price-service` is wired in.
 * @property books profiles to iterate per tick. Defaults to the canonical 8.
 * @property tradingHoursStart inclusive UTC trading-window start.
 * @property tradingHoursEnd inclusive UTC trading-window end.
 * @property clock pluggable clock — UTC in production, fixed in tests.
 * @property random pluggable RNG — `Random.Default` in production, seeded
 *     in tests so assertions are deterministic.
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
        val strategyId = strategyIdResolver.strategyIdFor(profile.bookId)
        return try {
            positionClient.bookTrade(
                bookId = profile.bookId,
                strategyId = strategyId,
                request = request,
            )
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
}

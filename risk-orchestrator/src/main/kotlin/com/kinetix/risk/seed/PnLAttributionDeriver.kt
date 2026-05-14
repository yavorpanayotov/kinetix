package com.kinetix.risk.seed

import com.kinetix.common.demo.BlackScholes
import com.kinetix.common.demo.DemoTape
import com.kinetix.common.demo.RegimeCalendar
import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.risk.model.AttributionDataQuality
import com.kinetix.risk.model.PnlAttribution
import com.kinetix.risk.model.PositionPnlAttribution
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Phase 3 / PR 1 — derive per-book daily P&L attribution from positions × tape moves.
 *
 * Replaces the hand-tuned `BOOK_PNL_PROFILES` constants that previously baked a single
 * yesterday-dated row per book. The deriver walks the full 252-day [DemoTape] for each
 * book in [DemoBookCatalogue] and emits per-day attribution rows:
 *
 *   delta_pnl   = qty * (S_today - S_yesterday)               for cash positions
 *               = sum(qty * delta_BS * dS)                    for option positions
 *   gamma_pnl   = sum(0.5 * qty * gamma_BS * dS^2)            (options only)
 *   vega_pnl    = sum(qty * vega_BS * dVol)                   (options only)
 *   theta_pnl   = sum(qty * theta_BS * dt)                    (options only)
 *   rho_pnl     = 0                                           (rates curve held flat
 *                                                              in this derivation;
 *                                                              fixed-income P&L falls
 *                                                              out of tape price moves)
 *   residual    = total_pnl - sum(greek terms)
 *
 * Stress-window drawdowns fall out for free: the tape's stress regimes (2020/2022
 * analog) push large negative dS through these formulas and the daily P&L lands
 * materially below calm-window P&L without any explicit calibration.
 *
 * Determinism: same tape seed → same numbers, same byte-for-byte BigDecimal output.
 */
class PnLAttributionDeriver(
    private val tape: DemoTape = DemoTape(),
    private val books: Map<String, List<DemoBookPosition>> = DemoBookCatalogue.BOOKS,
    /** Risk-free rate used by Black-Scholes; matches the USD CurveDefinition baseline. */
    private val riskFreeRate: Double = 0.045,
    /** Realised-vol lookback window used as implied vol input for option Greeks. */
    private val volLookbackDays: Int = 20,
) {

    /** All attribution rows across all books, deterministic order. */
    fun derive(): List<PnlAttribution> {
        val out = ArrayList<PnlAttribution>()
        for ((bookId, positions) in books) {
            out += deriveForBook(bookId, positions)
        }
        return out
    }

    private fun deriveForBook(bookId: String, positions: List<DemoBookPosition>): List<PnlAttribution> {
        val rows = ArrayList<PnlAttribution>(DAYS_OF_ATTRIBUTION)
        // day 0 = most recent close; we need both day d and day d+1 to compute a daily
        // return, so the deepest day we can attribute is RegimeCalendar.DAYS - 2.
        // That gives us 251 daily rows per book.
        for (day in 0 until DAYS_OF_ATTRIBUTION) {
            val attribution = deriveForDay(bookId, positions, day)
            if (attribution != null) rows += attribution
        }
        return rows
    }

    private fun deriveForDay(
        bookId: String,
        positions: List<DemoBookPosition>,
        day: Int,
    ): PnlAttribution? {
        val date = tape.calendar.dateForDay(day)
        val positionAttributions = positions.mapNotNull { pos ->
            if (pos.isOption) deriveOptionPnl(pos, day) else deriveCashPnl(pos, day)
        }
        if (positionAttributions.isEmpty()) return null

        val totalPnl = positionAttributions.sumOfBigDecimal { it.totalPnl }
        val deltaPnl = positionAttributions.sumOfBigDecimal { it.deltaPnl }
        val gammaPnl = positionAttributions.sumOfBigDecimal { it.gammaPnl }
        val vegaPnl = positionAttributions.sumOfBigDecimal { it.vegaPnl }
        val thetaPnl = positionAttributions.sumOfBigDecimal { it.thetaPnl }
        val rhoPnl = positionAttributions.sumOfBigDecimal { it.rhoPnl }
        val unexplainedPnl = positionAttributions.sumOfBigDecimal { it.unexplainedPnl }

        val hasOptions = positions.any { it.isOption }

        return PnlAttribution(
            bookId = BookId(bookId),
            date = date,
            currency = "USD",
            totalPnl = totalPnl,
            deltaPnl = deltaPnl,
            gammaPnl = gammaPnl,
            vegaPnl = vegaPnl,
            thetaPnl = thetaPnl,
            rhoPnl = rhoPnl,
            unexplainedPnl = unexplainedPnl,
            positionAttributions = positionAttributions,
            dataQualityFlag = if (hasOptions) AttributionDataQuality.FULL_ATTRIBUTION else AttributionDataQuality.PRICE_ONLY,
            calculatedAt = date.atTime(17, 0).toInstant(ZoneOffset.UTC),
        )
    }

    private fun deriveCashPnl(pos: DemoBookPosition, day: Int): PositionPnlAttribution? {
        val priceToday = tape.priceOn(pos.instrumentId, day)
        val priceYesterday = tape.priceOn(pos.instrumentId, day + 1)
        val dS = priceToday - priceYesterday
        val pnl = pos.quantity * dS
        val deltaPnl = pnl.toMoney()
        // Cash position attribution ties out by construction: total = delta + 0 + 0 + ...
        return PositionPnlAttribution(
            instrumentId = InstrumentId(pos.instrumentId),
            assetClass = pos.assetClass,
            totalPnl = deltaPnl,
            deltaPnl = deltaPnl,
            gammaPnl = BD_ZERO,
            vegaPnl = BD_ZERO,
            thetaPnl = BD_ZERO,
            rhoPnl = BD_ZERO,
            unexplainedPnl = BD_ZERO,
        )
    }

    private fun deriveOptionPnl(pos: DemoBookPosition, day: Int): PositionPnlAttribution {
        val spec = pos.optionSpec!!
        val spotToday = tape.priceOn(spec.underlier, day)
        val spotYesterday = tape.priceOn(spec.underlier, day + 1)
        val volToday = realisedVolOrSpec(spec.underlier, endDay = day)
        val volYesterday = realisedVolOrSpec(spec.underlier, endDay = day + 1)
        val tteToday = spec.yearsToExpiryFromAsOf + day.toDouble() / 252.0
        val tteYesterday = spec.yearsToExpiryFromAsOf + (day + 1).toDouble() / 252.0
        val dt = tteToday - tteYesterday // = -1/252 (time passing shortens tte)
        val dS = spotToday - spotYesterday
        val dVol = volToday - volYesterday

        // Greeks measured at yesterday's market state — standard practice for daily
        // P&L attribution ("explain today's move using yesterday's risk").
        val gYesterday = BlackScholes.greeks(
            spot = spotYesterday,
            strike = spec.strike,
            timeToExpiry = tteYesterday,
            vol = volYesterday,
            riskFreeRate = riskFreeRate,
            type = spec.type,
        )
        val priceYesterdayBS = gYesterday.price
        val priceTodayBS = BlackScholes.price(
            spot = spotToday,
            strike = spec.strike,
            timeToExpiry = tteToday,
            vol = volToday,
            riskFreeRate = riskFreeRate,
            type = spec.type,
        )

        val totalPnlScalar = pos.quantity * (priceTodayBS - priceYesterdayBS)
        val deltaPnlScalar = pos.quantity * gYesterday.delta * dS
        val gammaPnlScalar = pos.quantity * 0.5 * gYesterday.gamma * dS * dS
        val vegaPnlScalar = pos.quantity * gYesterday.vega * dVol
        val thetaPnlScalar = pos.quantity * gYesterday.theta * dt

        val deltaPnl = deltaPnlScalar.toMoney()
        val gammaPnl = gammaPnlScalar.toMoney()
        val vegaPnl = vegaPnlScalar.toMoney()
        val thetaPnl = thetaPnlScalar.toMoney()
        // Residual is computed from rounded components so total = sum(components)
        // is exact at the persistence scale (no sub-penny rounding drift).
        val explainedScalar = deltaPnlScalar + gammaPnlScalar + vegaPnlScalar + thetaPnlScalar
        val residualScalar = totalPnlScalar - explainedScalar
        val residual = residualScalar.toMoney()
        val totalPnl = deltaPnl.add(gammaPnl).add(vegaPnl).add(thetaPnl).add(residual)

        return PositionPnlAttribution(
            instrumentId = InstrumentId(pos.instrumentId),
            assetClass = pos.assetClass,
            totalPnl = totalPnl,
            deltaPnl = deltaPnl,
            gammaPnl = gammaPnl,
            vegaPnl = vegaPnl,
            thetaPnl = thetaPnl,
            rhoPnl = BD_ZERO,
            unexplainedPnl = residual,
        )
    }

    private fun realisedVolOrSpec(underlier: String, endDay: Int): Double {
        // Tape's realisedVol uses a trailing window starting at endDay. When endDay is
        // close to the back of the calendar there may not be enough history left — fall
        // back to a sensible default rather than blowing up.
        val maxWindow = RegimeCalendar.DAYS - endDay
        if (maxWindow < 5) return 0.30
        val w = volLookbackDays.coerceAtMost(maxWindow).coerceAtLeast(5)
        val realised = tape.realisedVol(underlier, endDay = endDay, window = w)
        // Floor vol so BS doesn't blow up if the synthesis produces near-zero realised
        // vol over a short window.
        return realised.coerceAtLeast(0.05)
    }

    private fun Double.toMoney(): BigDecimal =
        BigDecimal(this).setScale(MONEY_SCALE, RoundingMode.HALF_UP)

    private fun List<PositionPnlAttribution>.sumOfBigDecimal(selector: (PositionPnlAttribution) -> BigDecimal): BigDecimal =
        fold(BigDecimal.ZERO.setScale(MONEY_SCALE)) { acc, x -> acc.add(selector(x)) }

    companion object {
        private const val MONEY_SCALE = 8
        private val BD_ZERO: BigDecimal = BigDecimal.ZERO.setScale(MONEY_SCALE)
        private const val DAYS_OF_ATTRIBUTION = RegimeCalendar.DAYS - 1
    }
}

package com.kinetix.position.seed

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Side
import com.kinetix.common.model.TradeId
import com.kinetix.position.service.BookTradeCommand
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Currency

/**
 * Options-book scenario (Phase 2 Gap 2, demo-review v2).
 *
 * Two vol books with 1,000+ option positions across multiple expiries:
 *
 *   options-equity-vol      — 12 equity underlyings × 6 expiries × 4 strikes
 *                              × call/put = 576 positions.
 *   options-cross-asset-vol — 10 cross-asset underlyings × 6 expiries
 *                              × 4 strikes × call/put = 480 positions.
 *
 * Both weekly (W1/W2/W4) and monthly (M1/M2/M3) expiries are present so
 * the demo can show term structure and the gamma profile around weekly
 * expiry. Strikes spread ITM / ATM / OTM-1 / OTM-2 so the vol smile is
 * visible on each tenor. Sides alternate between buy/sell to give each
 * book a realistic long-vol vs short-vol mix.
 */
object OptionsBookScenario {

    const val EQUITY_VOL_BOOK: String = "options-equity-vol"
    const val CROSS_ASSET_VOL_BOOK: String = "options-cross-asset-vol"

    private val USD: Currency = Currency.getInstance("USD")
    private val BASE_TIME: Instant = Instant.parse("2026-02-21T14:00:00Z")

    /** Weekly + monthly expiry codes. Order matters: shorter → longer. */
    private val EXPIRIES: List<String> = listOf("W1", "W2", "W4", "M1", "M2", "M3")

    /** Strike offsets from ATM as fractions of underlying price. */
    private val STRIKE_OFFSETS: List<Double> = listOf(-0.05, 0.00, 0.05, 0.10)

    data class Underlying(
        val symbol: String,
        val price: BigDecimal,
        val contractMultiplier: BigDecimal,
        val baseQty: Int,
    )

    private val EQUITY_UNDERLYINGS: List<Underlying> = listOf(
        Underlying("AAPL",  BigDecimal("185.50"),   BigDecimal("100"), 200),
        Underlying("MSFT",  BigDecimal("421.50"),   BigDecimal("100"), 150),
        Underlying("NVDA",  BigDecimal("885.00"),   BigDecimal("100"), 80),
        Underlying("GOOGL", BigDecimal("176.80"),   BigDecimal("100"), 200),
        Underlying("AMZN",  BigDecimal("206.00"),   BigDecimal("100"), 180),
        Underlying("META",  BigDecimal("502.30"),   BigDecimal("100"), 120),
        Underlying("TSLA",  BigDecimal("249.50"),   BigDecimal("100"), 150),
        Underlying("AMD",   BigDecimal("164.20"),   BigDecimal("100"), 180),
        Underlying("SPX",   BigDecimal("5020.00"),  BigDecimal("100"), 40),
        Underlying("NDX",   BigDecimal("18100.00"), BigDecimal("100"), 20),
        Underlying("RUT",   BigDecimal("2120.00"),  BigDecimal("100"), 60),
        Underlying("QQQ",   BigDecimal("438.00"),   BigDecimal("100"), 220),
    )

    private val CROSS_ASSET_UNDERLYINGS: List<Underlying> = listOf(
        Underlying("VIX",    BigDecimal("16.20"),   BigDecimal("100"),    500),
        Underlying("GC",     BigDecimal("2045.60"), BigDecimal("100"),    25),
        Underlying("CL",     BigDecimal("76.80"),   BigDecimal("1000"),   30),
        Underlying("EURUSD", BigDecimal("1.0842"),  BigDecimal("100000"), 10),
        Underlying("GBPUSD", BigDecimal("1.2600"),  BigDecimal("100000"), 8),
        Underlying("USDJPY", BigDecimal("150.20"),  BigDecimal("100000"), 8),
        Underlying("TLT",    BigDecimal("92.40"),   BigDecimal("100"),    280),
        Underlying("US10Y",  BigDecimal("96.50"),   BigDecimal("1000"),   40),
        Underlying("XAU",    BigDecimal("2045.00"), BigDecimal("100"),    20),
        Underlying("SLV",    BigDecimal("23.10"),   BigDecimal("100"),    600),
    )

    data class OptionSpec(
        val book: String,
        val underlying: Underlying,
        val expiryCode: String,
        val tenorWeeks: Int,
        val strike: BigDecimal,
        val isCall: Boolean,
        val side: Side,
        val price: BigDecimal,
        val quantity: BigDecimal,
    ) {
        val instrumentId: InstrumentId
            get() {
                val cp = if (isCall) "C" else "P"
                // Strike formatted compactly: integer if whole, else "1p25" for 1.25 etc.
                val strikeStr = strike.stripTrailingZeros().toPlainString().replace(".", "p")
                return InstrumentId("${underlying.symbol}-$expiryCode-$cp-$strikeStr")
            }
    }

    val OPTIONS: List<OptionSpec> by lazy { buildOptions() }

    val TRADES: List<BookTradeCommand> by lazy { buildTrades() }

    private fun tenorWeeksFor(code: String): Int = when (code) {
        "W1" -> 1
        "W2" -> 2
        "W4" -> 4
        "M1" -> 4   // ~1 month
        "M2" -> 9
        "M3" -> 13
        else -> 4
    }

    /**
     * Lightweight ATM-premium estimate: spot × annualised-vol-proxy × sqrt(T).
     * 22 % vol proxy + sqrt(weeks/52) gives a usable price ladder for the
     * demo without dragging Black-Scholes into the seeder. Smile is added
     * by skewing OTM puts up relative to OTM calls.
     */
    private fun priceFor(spot: BigDecimal, tenorWeeks: Int, strikeOffset: Double, isCall: Boolean): BigDecimal {
        val volProxy = 0.22
        val tYears = tenorWeeks / 52.0
        val atm = spot.toDouble() * volProxy * Math.sqrt(tYears)
        // Distance from ATM in vol-adjusted terms
        val moneyAdj = if (strikeOffset == 0.0) 1.0 else 1.0 - Math.abs(strikeOffset) * 6.0
        val base = atm * moneyAdj.coerceAtLeast(0.15)
        val skew = if (!isCall && strikeOffset < 0) base * 0.15 else 0.0  // OTM put smile
        val raw = base + skew
        return BigDecimal(raw)
            .setScale(2, RoundingMode.HALF_UP)
            .coerceAtLeast(BigDecimal("0.05"))
    }

    private fun BigDecimal.coerceAtLeast(min: BigDecimal): BigDecimal =
        if (this < min) min else this

    private fun buildOptions(): List<OptionSpec> {
        val out = ArrayList<OptionSpec>(2 * 12 * 6 * 4 * 2)
        var idx = 0
        listOf(EQUITY_VOL_BOOK to EQUITY_UNDERLYINGS, CROSS_ASSET_VOL_BOOK to CROSS_ASSET_UNDERLYINGS)
            .forEach { (book, underlyings) ->
                for (underlying in underlyings) {
                    for (expiry in EXPIRIES) {
                        val tenor = tenorWeeksFor(expiry)
                        for (offset in STRIKE_OFFSETS) {
                            val strike = underlying.price
                                .multiply(BigDecimal(1.0 + offset))
                                .setScale(2, RoundingMode.HALF_UP)
                            for (isCall in listOf(true, false)) {
                                val price = priceFor(underlying.price, tenor, offset, isCall)
                                // Alternate sides deterministically for long/short mix
                                val side = if (idx % 2 == 0) Side.BUY else Side.SELL
                                val qty = BigDecimal(underlying.baseQty)
                                out += OptionSpec(
                                    book = book,
                                    underlying = underlying,
                                    expiryCode = expiry,
                                    tenorWeeks = tenor,
                                    strike = strike,
                                    isCall = isCall,
                                    side = side,
                                    price = price,
                                    quantity = qty,
                                )
                                idx++
                            }
                        }
                    }
                }
            }
        return out
    }

    private fun buildTrades(): List<BookTradeCommand> {
        return OPTIONS.mapIndexed { idx, opt ->
            val tradedAt = BASE_TIME.minus((idx % 19).toLong(), ChronoUnit.DAYS)
            val bookAbbrev = if (opt.book == EQUITY_VOL_BOOK) "eq" else "xa"
            val instrAbbrev = opt.instrumentId.value.lowercase().replace("-", "").take(20)
            BookTradeCommand(
                tradeId = TradeId("seed-opt-$bookAbbrev-$instrAbbrev-${idx.toString().padStart(4, '0')}"),
                bookId = BookId(opt.book),
                instrumentId = opt.instrumentId,
                assetClass = AssetClass.DERIVATIVE,
                side = opt.side,
                quantity = opt.quantity,
                price = Money(opt.price, USD),
                tradedAt = tradedAt,
                instrumentType = "EQUITY_OPTION",
            )
        }
    }
}

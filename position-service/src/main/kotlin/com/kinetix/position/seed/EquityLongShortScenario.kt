package com.kinetix.position.seed

import com.kinetix.common.demo.DemoTraderRoster
import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Side
import com.kinetix.common.model.TradeId
import com.kinetix.common.model.TraderId
import com.kinetix.position.service.BookTradeCommand
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Currency

/**
 * Equity Long/Short scenario (Phase 2 Gap 2, demo-review v2).
 *
 * Produces a single book ("equity-ls") with 800 distinct positions across
 * 8 sectors:
 *
 *   Net long tilt (4):   Tech, Healthcare, Industrials, Consumer Discretionary
 *   Net short tilt (4):  Financials, Energy, Consumer Staples, Materials
 *
 * Per long-tilt sector: 65 long / 35 short positions.
 * Per short-tilt sector: 35 long / 65 short positions.
 * Total: 400 long, 400 short — gross-balanced (factor-neutral by gross notional).
 *
 * Notionals are spread deterministically so longs and shorts have similar
 * dollar distributions; |gross long − gross short| stays well under 20% of
 * total gross.
 */
object EquityLongShortScenario {

    const val BOOK_ID: String = "equity-ls"
    const val POSITIONS_PER_SECTOR: Int = 100

    private val USD: Currency = Currency.getInstance("USD")
    private val BASE_TIME: Instant = Instant.parse("2026-02-21T14:00:00Z")

    enum class Tilt { NET_LONG, NET_SHORT }

    enum class Sector(val prefix: String, val tilt: Tilt) {
        TECH("TCH", Tilt.NET_LONG),
        HEALTHCARE("HLT", Tilt.NET_LONG),
        INDUSTRIALS("IND", Tilt.NET_LONG),
        CONSUMER_DISC("CND", Tilt.NET_LONG),
        FINANCIALS("FIN", Tilt.NET_SHORT),
        ENERGY("ENE", Tilt.NET_SHORT),
        CONSUMER_STAPLES("CNS", Tilt.NET_SHORT),
        MATERIALS("MAT", Tilt.NET_SHORT),
    }

    data class InstrumentSpec(
        val id: InstrumentId,
        val sector: Sector,
        val side: Side,
        val typicalPrice: BigDecimal,
        val typicalQty: BigDecimal,
    )

    val INSTRUMENTS: List<InstrumentSpec> by lazy { buildInstruments() }

    val TRADES: List<BookTradeCommand> by lazy { buildTrades() }

    private fun longCountFor(tilt: Tilt): Int = if (tilt == Tilt.NET_LONG) 65 else 35

    private fun buildInstruments(): List<InstrumentSpec> {
        val out = ArrayList<InstrumentSpec>(Sector.values().size * POSITIONS_PER_SECTOR)
        Sector.values().forEach { sector ->
            val longCount = longCountFor(sector.tilt)
            for (i in 1..POSITIONS_PER_SECTOR) {
                val id = InstrumentId("${sector.prefix}${i.toString().padStart(3, '0')}")
                val side = if (i <= longCount) Side.BUY else Side.SELL
                out += InstrumentSpec(
                    id = id,
                    sector = sector,
                    side = side,
                    typicalPrice = priceFor(sector, i),
                    typicalQty = qtyFor(sector, i, side),
                )
            }
        }
        return out
    }

    /**
     * Price spread: $20–$500 by a stable hash of (sector, index).
     * Result is rounded to cents.
     */
    private fun priceFor(sector: Sector, i: Int): BigDecimal {
        val span = 480
        val base = 20
        val offset = ((sector.ordinal * 37 + i * 13) % span + span) % span
        val whole = base + offset
        val cents = ((sector.ordinal * 7 + i * 19) % 100 + 100) % 100
        return BigDecimal("${whole}.${cents.toString().padStart(2, '0')}")
    }

    /**
     * Quantity spread chosen so long-leg gross notional ≈ short-leg gross notional.
     * Long positions and short positions get distributions drawn from the same
     * deterministic ring, just at offset indices — keeps the book factor-neutral.
     */
    private fun qtyFor(sector: Sector, i: Int, side: Side): BigDecimal {
        val ring = intArrayOf(150, 220, 340, 480, 620, 780, 950, 1200, 1500, 1800, 2200, 2600, 3000, 3500, 4000)
        val ringIdx = ((sector.ordinal * 11 + i * 23) % ring.size + ring.size) % ring.size
        return BigDecimal(ring[ringIdx])
    }

    private fun buildTrades(): List<BookTradeCommand> {
        val traderId = TraderId(DemoTraderRoster.requirePrimaryTraderFor(BOOK_ID))
        return INSTRUMENTS.mapIndexed { idx, spec ->
            val tradedAt = BASE_TIME.minus((idx % 19).toLong(), ChronoUnit.DAYS)
            BookTradeCommand(
                tradeId = TradeId("seed-els-${spec.id.value.lowercase()}"),
                bookId = BookId(BOOK_ID),
                instrumentId = spec.id,
                assetClass = AssetClass.EQUITY,
                side = spec.side,
                quantity = spec.typicalQty,
                price = Money(spec.typicalPrice, USD),
                tradedAt = tradedAt,
                instrumentType = "CASH_EQUITY",
                traderId = traderId,
            )
        }
    }
}

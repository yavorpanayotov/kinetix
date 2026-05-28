package com.kinetix.common.model

import com.kinetix.common.model.instrument.InstrumentTypeCode
import java.math.BigDecimal
import java.math.MathContext
import java.util.Currency

data class Position(
    val bookId: BookId,
    val instrumentId: InstrumentId,
    val assetClass: AssetClass,
    val quantity: BigDecimal,
    val averageCost: Money,
    val marketPrice: Money,
    val realizedPnl: Money = Money.zero(marketPrice.currency),
    val instrumentType: InstrumentTypeCode,
    val strategyId: String? = null,
    val strategyType: String? = null,
    val strategyName: String? = null,
) {
    init {
        require(averageCost.currency == marketPrice.currency) {
            "Currency mismatch: averageCost=${averageCost.currency}, marketPrice=${marketPrice.currency}"
        }
        require(realizedPnl.currency == marketPrice.currency) {
            "Currency mismatch: realizedPnl=${realizedPnl.currency}, marketPrice=${marketPrice.currency}"
        }
    }

    val currency: Currency
        get() = marketPrice.currency

    val marketValue: Money
        get() = marketPrice * quantity

    val unrealizedPnl: Money
        get() = (marketPrice - averageCost) * quantity

    fun markToMarket(newMarketPrice: Money): Position {
        require(newMarketPrice.currency == currency) {
            "Cannot mark to market with different currency: expected $currency, got ${newMarketPrice.currency}"
        }
        return copy(marketPrice = newMarketPrice)
    }

    fun applyTrade(trade: Trade): Position {
        require(trade.bookId == bookId) { "Trade bookId mismatch" }
        require(trade.instrumentId == instrumentId) { "Trade instrumentId mismatch" }
        require(trade.price.currency == currency) { "Trade currency mismatch" }

        val tradeSignedQty = trade.signedQuantity
        val newQuantity = quantity + tradeSignedQty

        // Compute realized P&L when the trade reduces or closes the position
        val tradeRealizedPnl = when {
            // No existing position — nothing to realize
            quantity.signum() == 0 -> BigDecimal.ZERO

            // Trade increases position (same direction) — no realization
            quantity.signum() == tradeSignedQty.signum() -> BigDecimal.ZERO

            // Trade reduces or flips position — realize on the closed portion
            else -> {
                val closedQuantity = trade.quantity.min(quantity.abs())
                (trade.price.amount - averageCost.amount) * closedQuantity * quantity.signum().toBigDecimal()
            }
        }

        val newAverageCost = when {
            quantity.signum() == 0 -> trade.price

            quantity.signum() == tradeSignedQty.signum() -> {
                val totalCost = averageCost.amount * quantity.abs() + trade.price.amount * trade.quantity
                val totalQty = quantity.abs() + trade.quantity
                Money(totalCost.divide(totalQty, MathContext.DECIMAL128), currency)
            }

            newQuantity.signum() == quantity.signum() -> averageCost

            newQuantity.signum() != 0 -> trade.price

            else -> averageCost
        }

        // Seed marketPrice from the trade when the position has no prior
        // mark — i.e. this is the first trade arriving at a (book,
        // instrument) pair that has not yet received a PriceConsumer
        // update. Without this, the position sits at marketPrice = 0
        // until a price-update event happens to land for the instrument;
        // for asset classes the demo stack doesn't tick (notably
        // FIXED_INCOME / GOVERNMENT_BOND) that "until" is "never", so
        // the Risk → Position Risk Breakdown row renders $0.00 even
        // though there's real exposure. Trader-review P0 #4.
        //
        // Once the position has a non-zero marketPrice, mark-to-market
        // is owned by PriceUpdateService and we leave the existing
        // price alone here (the trade price is the wrong signal once
        // we have a live tick).
        val newMarketPrice = if (marketPrice.amount.signum() == 0) trade.price else marketPrice

        return copy(
            quantity = newQuantity,
            averageCost = newAverageCost,
            marketPrice = newMarketPrice,
            realizedPnl = realizedPnl + Money(tradeRealizedPnl, currency),
        )
    }

    companion object {
        /**
         * Construct a zero-quantity position seeded from the metadata of the first trade
         * being booked into a (bookId, instrumentId) pair. Replaces the previous
         * `empty()` factory: every position now carries a non-null instrumentType,
         * which we can only know once the first trade has arrived.
         */
        fun fromFirstTrade(trade: Trade): Position = Position(
            bookId = trade.bookId,
            instrumentId = trade.instrumentId,
            assetClass = trade.assetClass,
            quantity = BigDecimal.ZERO,
            averageCost = Money.zero(trade.price.currency),
            marketPrice = Money.zero(trade.price.currency),
            instrumentType = trade.instrumentType,
        )
    }
}

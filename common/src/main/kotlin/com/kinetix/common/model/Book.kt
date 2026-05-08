package com.kinetix.common.model

import java.util.Currency

data class Book(
    val id: BookId,
    val name: String,
    val positions: Map<InstrumentId, Position> = emptyMap(),
) {
    init {
        require(name.isNotBlank()) { "Book name must not be blank" }
    }

    fun totalMarketValue(currency: Currency): Money =
        positions.values
            .filter { it.currency == currency }
            .fold(Money.zero(currency)) { acc, pos -> acc + pos.marketValue }

    fun totalUnrealizedPnl(currency: Currency): Money =
        positions.values
            .filter { it.currency == currency }
            .fold(Money.zero(currency)) { acc, pos -> acc + pos.unrealizedPnl }

    fun bookTrade(trade: Trade): Book {
        require(trade.bookId == id) { "Trade bookId does not match book" }

        val existing = positions[trade.instrumentId]
            ?: Position.fromFirstTrade(trade)

        val updated = existing.applyTrade(trade)
        return copy(positions = positions + (trade.instrumentId to updated))
    }

    fun markToMarket(instrumentId: InstrumentId, newMarketPrice: Money): Book {
        val existing = positions[instrumentId] ?: return this
        val updated = existing.markToMarket(newMarketPrice)
        return copy(positions = positions + (instrumentId to updated))
    }
}

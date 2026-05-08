package com.kinetix.gateway.dtos

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Side
import com.kinetix.common.model.TradeId
import com.kinetix.gateway.client.BookTradeCommand
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

@Serializable
data class BookTradeRequest(
    val tradeId: String,
    val instrumentId: String,
    val assetClass: String,
    val side: String,
    val quantity: String,
    val priceAmount: String,
    val priceCurrency: String,
    val tradedAt: String,
    val instrumentType: String,
)

fun BookTradeRequest.toCommand(bookId: BookId): BookTradeCommand {
    val qty = BigDecimal(quantity)
    require(qty > BigDecimal.ZERO) { "Trade quantity must be positive, was $qty" }
    val priceAmt = BigDecimal(priceAmount)
    require(priceAmt >= BigDecimal.ZERO) { "Trade price must be non-negative, was $priceAmt" }
    return BookTradeCommand(
        tradeId = TradeId(tradeId),
        bookId = bookId,
        instrumentId = InstrumentId(instrumentId),
        assetClass = AssetClass.valueOf(assetClass),
        side = Side.valueOf(side),
        quantity = qty,
        price = Money(priceAmt, Currency.getInstance(priceCurrency)),
        tradedAt = Instant.parse(tradedAt),
        instrumentType = instrumentType,
    )
}

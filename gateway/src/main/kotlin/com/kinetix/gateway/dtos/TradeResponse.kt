package com.kinetix.gateway.dtos

import com.kinetix.common.model.Trade
import com.kinetix.gateway.client.InstrumentSummary
import kotlinx.serialization.Serializable

@Serializable
data class TradeResponse(
    val tradeId: String,
    val bookId: String,
    val instrumentId: String,
    val assetClass: String,
    val side: String,
    val quantity: String,
    val price: MoneyDto,
    val tradedAt: String,
    val status: String,
    val instrumentType: String? = null,
    val displayName: String? = null,
    val counterpartyId: String? = null,
)

fun Trade.toResponse(): TradeResponse = toResponse(emptyMap())

fun Trade.toResponse(instruments: Map<String, InstrumentSummary>): TradeResponse {
    val instrument = instruments[instrumentId.value]
    return TradeResponse(
        tradeId = tradeId.value,
        bookId = bookId.value,
        instrumentId = instrumentId.value,
        assetClass = assetClass.name,
        side = side.name,
        quantity = quantity.toPlainString(),
        price = price.toDto(),
        tradedAt = tradedAt.toString(),
        status = status.name,
        instrumentType = instrument?.instrumentType ?: instrumentType?.name,
        displayName = instrument?.displayName,
        counterpartyId = counterpartyId,
    )
}

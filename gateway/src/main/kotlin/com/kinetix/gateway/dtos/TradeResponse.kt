package com.kinetix.gateway.dtos

import com.kinetix.common.model.Trade
import com.kinetix.common.model.TradeStatus
import com.kinetix.gateway.client.InstrumentSummary
import com.kinetix.gateway.client.TradeBlotterRow
import kotlinx.serialization.Serializable
import java.math.BigDecimal

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
    // Trader-review P2 §21: FIX-style fill state visible on the blotter.
    // Derived from [status] for booked records (LIVE/AMENDED → FILLED,
    // CANCELLED → CANCELLED) unless the upstream supplies an explicit
    // projection (e.g. WORKING/PARTIAL/REJECTED on trades reconciled
    // from working orders).
    val fillStatus: String? = null,
    val qtyFilled: String? = null,
    val qtyOpen: String? = null,
)

fun Trade.toResponse(): TradeResponse = toResponse(emptyMap())

fun Trade.toResponse(instruments: Map<String, InstrumentSummary>): TradeResponse {
    val instrument = instruments[instrumentId.value]
    val fill = deriveDefaultFillProjection(this)
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
        fillStatus = fill.fillStatus,
        qtyFilled = fill.qtyFilled,
        qtyOpen = fill.qtyOpen,
    )
}

fun TradeBlotterRow.toResponse(): TradeResponse = toResponse(emptyMap())

fun TradeBlotterRow.toResponse(instruments: Map<String, InstrumentSummary>): TradeResponse {
    val base = trade.toResponse(instruments)
    // When upstream carries an explicit fill-state triple — typically for
    // trades reconciled from working orders — forward it verbatim. The
    // derived default already populated by Trade.toResponse() is replaced
    // only when fillStatus is explicitly present.
    return if (fillStatus != null) {
        base.copy(
            fillStatus = fillStatus,
            qtyFilled = qtyFilled?.toPlainString() ?: base.qtyFilled,
            qtyOpen = qtyOpen?.toPlainString() ?: base.qtyOpen,
        )
    } else {
        base
    }
}

private data class DefaultFillProjection(
    val fillStatus: String,
    val qtyFilled: String,
    val qtyOpen: String,
)

private fun deriveDefaultFillProjection(trade: Trade): DefaultFillProjection {
    val zero = BigDecimal.ZERO.toPlainString()
    return when (trade.status) {
        // Booked LIVE/AMENDED records are, by definition, fully filled —
        // the position-service only persists a trade once it has cleared.
        TradeStatus.LIVE, TradeStatus.AMENDED -> DefaultFillProjection(
            fillStatus = "FILLED",
            qtyFilled = trade.quantity.toPlainString(),
            qtyOpen = zero,
        )
        // A cancelled booking shows nothing filled and nothing open — the
        // exposure was reversed by the cancellation.
        TradeStatus.CANCELLED -> DefaultFillProjection(
            fillStatus = "CANCELLED",
            qtyFilled = zero,
            qtyOpen = zero,
        )
    }
}

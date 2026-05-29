package com.kinetix.gateway.dtos

import kotlinx.serialization.Serializable

/**
 * Body of `POST /api/v1/risk/pretrade-preview`. A single candidate trade
 * (the order being placed on the UI's Place Order ticket) plus the book
 * it would be booked against. `counterpartyId` is optional — bilateral
 * OTC derivatives carry it; exchange-cleared cash equities do not.
 *
 * Spec: execution.allium ComputePreTradeRiskPreview.
 */
@Serializable
data class PreTradeRiskPreviewRequest(
    val bookId: String,
    val instrumentId: String,
    val assetClass: String,
    val side: String,
    val quantity: String,
    val priceAmount: String,
    val priceCurrency: String,
    val instrumentType: String,
    val counterpartyId: String? = null,
)

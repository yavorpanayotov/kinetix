package com.kinetix.position.routes.dtos

import kotlinx.serialization.Serializable

@Serializable
data class PreTradeCheckRequest(
    val bookId: String,
    val instrumentId: String,
    val assetClass: String,
    val side: String,
    val quantity: String,
    val priceAmount: String,
    val priceCurrency: String,
    val instrumentType: String,
)

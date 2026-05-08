package com.kinetix.risk.routes.dtos

import kotlinx.serialization.Serializable

@Serializable
data class RebalancingTradeDto(
    val instrumentId: String,
    val assetClass: String,
    val side: String,
    val quantity: String,
    val priceAmount: String,
    val priceCurrency: String,
    val instrumentType: String,
    val bidAskSpreadBps: Double = 5.0,
)

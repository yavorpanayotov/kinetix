package com.kinetix.gateway.dtos

import kotlinx.serialization.Serializable

@Serializable
data class RebalancingGatewayTradeDto(
    val instrumentId: String,
    val assetClass: String,
    val side: String,
    val quantity: String,
    val priceAmount: String,
    val priceCurrency: String,
    val instrumentType: String,
    val bidAskSpreadBps: Double = 5.0,
)

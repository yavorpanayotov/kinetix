package com.kinetix.position.client.dtos

import kotlinx.serialization.Serializable

/**
 * Wire shape of price-service's `GET /api/v1/prices/{instrumentId}/latest`
 * response. Matches `PricePointResponse` in price-service.
 */
@Serializable
data class PricePointDto(
    val instrumentId: String,
    val price: MoneyDto,
    val timestamp: String,
    val source: String,
)

@Serializable
data class MoneyDto(
    val amount: String,
    val currency: String,
)

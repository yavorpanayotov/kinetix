package com.kinetix.position.routes.dtos

import kotlinx.serialization.Serializable

@Serializable
data class OrderResponse(
    val orderId: String,
    val bookId: String,
    val instrumentId: String,
    val side: String,
    val quantity: String,
    val orderType: String,
    val limitPrice: String?,
    val arrivalPrice: String,
    val submittedAt: String,
    val status: String,
    val fixSessionId: String?,
    val timeInForce: String = "DAY",
    val expiresAt: String? = null,
)

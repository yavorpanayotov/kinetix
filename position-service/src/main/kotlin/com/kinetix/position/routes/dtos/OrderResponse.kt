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
    /**
     * FIX tag 37 OrderID assigned by the venue at PENDING_NEW. Surfaced in the
     * REST response so traders see the venue's identifier on the confirmation
     * modal and can quote it when calling the venue. Null for orders that
     * never reached the venue (PENDING_RISK_CHECK / REJECTED / PENDING_FAILED)
     * and for legacy rows that pre-date ADR-0035 phase 4.
     */
    val venueOrderId: String? = null,
)

package com.kinetix.position.routes.dtos

import kotlinx.serialization.Serializable

/**
 * Trader-facing representation of a ghost fill (FIX 35=8 fill against an
 * already-terminal order). Surfaced via `GET /api/v1/orders/{id}/ghost-fills`
 * and rendered on the order detail panel with a CRITICAL RiskAlertBanner.
 */
@Serializable
data class GhostFillResponse(
    val orderId: String,
    val priorStatus: String,
    val venue: String,
    val fixExecId: String,
    val fillQty: String,
    val fillPrice: String,
    val cumulativeQty: String,
    val detectedAt: String,
)

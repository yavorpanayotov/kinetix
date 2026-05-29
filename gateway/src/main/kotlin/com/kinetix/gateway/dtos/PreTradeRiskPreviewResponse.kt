package com.kinetix.gateway.dtos

import kotlinx.serialization.Serializable

/**
 * Response of `POST /api/v1/risk/pretrade-preview`. The four-delta preview
 * surfaced on the UI's Place Order ticket on form-blur. All numeric fields
 * are decimal strings (the rest of the gateway risk surface follows the
 * same convention to avoid floating-point precision loss across the wire).
 *
 * Spec: execution.allium value PreTradeRiskPreview.
 */
@Serializable
data class PreTradeRiskPreviewResponse(
    val baseVaR: String,
    val hypotheticalVaR: String,
    val varChange: String,

    val baseDelta: String,
    val hypotheticalDelta: String,
    val deltaChange: String,

    val notionalChange: String,
    val counterpartyId: String? = null,
    val counterpartyExposureChange: String? = null,

    val calculatedAt: String,
)

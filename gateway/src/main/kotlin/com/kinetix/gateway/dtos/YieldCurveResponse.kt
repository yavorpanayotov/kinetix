package com.kinetix.gateway.dtos

import kotlinx.serialization.Serializable

/**
 * UI-facing yield curve aggregated by the gateway. Each canonical tenor
 * is present with an `interpolated` flag — true when the source node was
 * missing and the rates-service returned a linearly-interpolated value.
 */
@Serializable
data class YieldCurveResponse(
    val curveId: String,
    val currency: String,
    val asOfDate: String,
    val source: String,
    val points: List<YieldCurvePointResponse>,
)

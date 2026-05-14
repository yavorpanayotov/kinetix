package com.kinetix.gateway.dtos

import kotlinx.serialization.Serializable

/**
 * UI-facing yield curve point. Carries the `interpolated` flag so the
 * yield-curve chart can render hollow markers for points that were
 * filled in by linear interpolation (e.g. the seeded GBP 5Y anomaly).
 */
@Serializable
data class YieldCurvePointResponse(
    val label: String,
    val days: Int,
    val rate: String,
    val interpolated: Boolean,
)

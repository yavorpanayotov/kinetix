package com.kinetix.rates.routes.dtos

import kotlinx.serialization.Serializable

@Serializable
data class YieldCurveTenorResponse(
    val curveId: String,
    val tenor: String,
    val rate: String,
    val interpolated: Boolean,
)

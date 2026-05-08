package com.kinetix.gateway.dtos

import kotlinx.serialization.Serializable

@Serializable
data class StressWindowDto(
    val label: String,
    val start: String,
    val end: String,
)

@Serializable
data class StressWindowsResponse(
    val windows: List<StressWindowDto>,
)

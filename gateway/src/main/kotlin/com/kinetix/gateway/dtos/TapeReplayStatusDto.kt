package com.kinetix.gateway.dtos

import kotlinx.serialization.Serializable

@Serializable
data class TapeReplayStatusDto(
    val status: String,
)

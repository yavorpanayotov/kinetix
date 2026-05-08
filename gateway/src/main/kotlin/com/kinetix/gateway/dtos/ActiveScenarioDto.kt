package com.kinetix.gateway.dtos

import kotlinx.serialization.Serializable

@Serializable
data class ActiveScenarioDto(
    val scenario: String,
)

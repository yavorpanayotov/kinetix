package com.kinetix.price.routes.dtos

import kotlinx.serialization.Serializable

@Serializable
data class StaleInstrumentResponse(
    val instrumentId: String,
    val lastUpdated: String,
    val ageHours: Long,
    val status: String = "STALE",
)

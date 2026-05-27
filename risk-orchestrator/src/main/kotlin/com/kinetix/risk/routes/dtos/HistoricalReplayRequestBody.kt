package com.kinetix.risk.routes.dtos

import kotlinx.serialization.Serializable

@Serializable
data class InstrumentDailyReturnsDto(
    val instrumentId: String,
    val dailyReturns: List<Double>,
)

@Serializable
data class HistoricalReplayRequestBody(
    val instrumentReturns: List<InstrumentDailyReturnsDto> = emptyList(),
    val scenarioName: String? = null,
    val windowStart: String? = null,
    val windowEnd: String? = null,
)

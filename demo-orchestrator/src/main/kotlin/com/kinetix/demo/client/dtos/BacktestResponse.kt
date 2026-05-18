package com.kinetix.demo.client.dtos

import kotlinx.serialization.Serializable

/**
 * Wire shape of the response body returned by
 * `POST /api/v1/regulatory/backtest/{bookId}`.
 *
 * Only the fields the demo orchestrator actually consumes are modelled.
 * The upstream `regulatory-service` returns a richer
 * [com.kinetix.regulatory.dtos.BacktestResultResponse]; remaining fields are
 * ignored on decode via `ignoreUnknownKeys = true`.
 */
@Serializable
data class BacktestResponse(
    val violationCount: Int,
    val kupiecPass: Boolean,
    val trafficLightZone: String,
)

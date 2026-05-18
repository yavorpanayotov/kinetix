package com.kinetix.demo.client.dtos

import kotlinx.serialization.Serializable

/**
 * Wire shape of the response body returned by
 * `POST /api/v1/books/{bookId}/strategies/{strategyId}/trades`.
 *
 * Only the fields the demo orchestrator actually consumes are modelled.
 * The upstream `position-service` also returns `bookId`, `instrumentId`,
 * `side`, `quantity` and `strategyId` — those are ignored here, and the JSON
 * decoder is configured with `ignoreUnknownKeys = true` so extra fields do
 * not break parsing.
 */
@Serializable
data class StrategyTradeResponse(
    val tradeId: String,
)

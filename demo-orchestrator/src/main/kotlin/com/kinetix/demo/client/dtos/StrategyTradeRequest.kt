package com.kinetix.demo.client.dtos

import kotlinx.serialization.Serializable

/**
 * Wire shape of `POST /api/v1/books/{bookId}/strategies/{strategyId}/trades`
 * exposed by `position-service` (see `StrategyRoutes.kt`).
 *
 * Field names mirror the upstream `StrategyTradeRequest` exactly so the JSON
 * round-trips on the wire. Numeric values stay as strings to preserve precision
 * — `position-service` parses them back via `BigDecimal(...)`.
 *
 * Optional fields default to `null` and are omitted from the serialised JSON
 * (the upstream route generates a `tradeId` server-side when absent).
 */
@Serializable
data class StrategyTradeRequest(
    val tradeId: String? = null,
    val instrumentId: String,
    val assetClass: String,
    val side: String,
    val quantity: String,
    val priceAmount: String,
    val priceCurrency: String,
    val tradedAt: String,
    val instrumentType: String,
    val userId: String? = null,
    val userRole: String? = null,
    /**
     * Optional counterparty identifier. Demo orchestrators rotate this across
     * the seeded counterparties (kx-i72) so the Counterparty Exposure tile in
     * the UI has non-trivial concentration to render. `null` is accepted by
     * `position-service` for backwards compatibility.
     */
    val counterpartyId: String? = null,
)

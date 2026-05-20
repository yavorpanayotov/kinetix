package com.kinetix.demo.client.dtos

import kotlinx.serialization.Serializable

/**
 * Wire shape of `POST /api/v1/internal/execution/cost/{bookId}` exposed by
 * `position-service` (see its `RecordExecutionCostRequest` DTO).
 *
 * This is a demo/seed seam — the simulator posts one synthetic
 * execution-cost sample per simulated trade so the Trades > Execution Cost
 * subtab renders non-empty data. Numeric values stay as strings to preserve
 * `BigDecimal` precision on the wire, matching [StrategyTradeRequest].
 *
 * Optional fields default to `null` and are omitted from the serialised JSON.
 */
@Serializable
data class RecordExecutionCostRequest(
    val orderId: String,
    val instrumentId: String,
    val completedAt: String,
    val arrivalPrice: String,
    val averageFillPrice: String,
    val side: String,
    val totalQty: String,
    val slippageBps: String,
    val marketImpactBps: String? = null,
    val timingCostBps: String? = null,
    val totalCostBps: String,
)

package com.kinetix.position.routes.dtos

import kotlinx.serialization.Serializable

/**
 * Request body for `POST /api/v1/internal/execution/cost/{bookId}`.
 *
 * This is a demo/seed seam — the demo-orchestrator posts a synthetic
 * execution-cost sample after each simulated trade so the Trades > Execution
 * Cost subtab renders non-empty data. The route delegates straight to
 * [com.kinetix.position.fix.ExecutionCostRepository.save]; the fields here
 * mirror [com.kinetix.position.fix.ExecutionCostAnalysis] /
 * [com.kinetix.position.fix.ExecutionCostMetrics] one-to-one.
 *
 * Numeric values are carried as strings so `BigDecimal` precision survives
 * the wire without floating-point drift, consistent with
 * [ExecutionCostResponse] and [PrimeBrokerStatementRequest].
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

package com.kinetix.demo.client.dtos

import kotlinx.serialization.Serializable

/**
 * Wire shape of `POST /api/v1/regulatory/backtest/{bookId}` exposed by
 * `regulatory-service` (see `BacktestRoutes.kt`).
 *
 * Field names mirror the upstream `BacktestRequest` exactly so the JSON
 * round-trips on the wire. The demo orchestrator sources
 * [dailyVarPredictions] and [dailyPnl] from the last 30 days of
 * `daily_risk_snapshots`.
 */
@Serializable
data class BacktestRequest(
    val dailyVarPredictions: List<Double>,
    val dailyPnl: List<Double>,
    val confidenceLevel: Double = 0.99,
    val calculationType: String = "PARAMETRIC",
)

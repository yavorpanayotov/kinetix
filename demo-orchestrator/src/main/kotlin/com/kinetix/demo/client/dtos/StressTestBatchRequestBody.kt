package com.kinetix.demo.client.dtos

import kotlinx.serialization.Serializable

/**
 * Wire shape of the body sent to `POST /api/v1/risk/stress/{bookId}/batch`
 * exposed by `risk-orchestrator` (see `RiskRoutes.kt` and
 * `routes/dtos/StressTestBatchRequestBody.kt`).
 *
 * Used by [com.kinetix.demo.client.RiskOrchestratorClient.runAllStressScenarios]
 * to fire a full multi-scenario sweep at bootstrap so the Scenarios tab has a
 * "latest run" to render (issue kx-kjse). [scenarioNames] is the full set of
 * registered scenario names returned by `GET /api/v1/risk/stress/scenarios`.
 */
@Serializable
data class StressTestBatchRequestBody(
    val scenarioNames: List<String>,
    val calculationType: String? = null,
    val confidenceLevel: String? = null,
    val timeHorizonDays: String? = null,
)

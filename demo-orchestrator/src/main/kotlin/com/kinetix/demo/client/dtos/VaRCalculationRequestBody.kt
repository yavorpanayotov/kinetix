package com.kinetix.demo.client.dtos

import kotlinx.serialization.Serializable

/**
 * Wire shape of the body sent to `POST /api/v1/risk/var/{bookId}` exposed by
 * `risk-orchestrator` (see `RiskRoutes.kt` and
 * `routes/dtos/VaRCalculationRequestBody.kt`).
 *
 * All fields are nullable on the wire and upstream substitutes defaults
 * (`PARAMETRIC`, `CL_95`, `1`, `10000`). The demo orchestrator sets values
 * explicitly to make the close-time EOD snapshot deterministic.
 */
@Serializable
data class VaRCalculationRequestBody(
    val calculationType: String? = null,
    val confidenceLevel: String? = null,
    val timeHorizonDays: String? = null,
    val numSimulations: String? = null,
    val requestedOutputs: List<String>? = null,
)

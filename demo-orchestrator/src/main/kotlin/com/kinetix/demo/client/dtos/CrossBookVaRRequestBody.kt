package com.kinetix.demo.client.dtos

import kotlinx.serialization.Serializable

/**
 * Wire shape of the body sent to `POST /api/v1/risk/var/cross-book` exposed by
 * `risk-orchestrator` (see `CrossBookVaRRoutes.kt` and
 * `routes/dtos/CrossBookVaRCalculationRequestBody.kt`).
 *
 * Used by [com.kinetix.demo.schedule.DemoVaRBootstrapJob] to seed the
 * firm-level cross-book VaR aggregate in risk-orchestrator's
 * `CrossBookVaRCache` after per-book VaR has been calculated. Without this
 * call, `GET /api/v1/risk/var/cross-book/firm` returns 404 because the cache
 * is never populated.
 */
@Serializable
data class CrossBookVaRRequestBody(
    val bookIds: List<String>,
    val portfolioGroupId: String,
    val calculationType: String? = null,
    val confidenceLevel: String? = null,
    val timeHorizonDays: String? = null,
    val numSimulations: String? = null,
)

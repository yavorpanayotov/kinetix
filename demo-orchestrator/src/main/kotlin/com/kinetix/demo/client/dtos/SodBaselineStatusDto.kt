package com.kinetix.demo.client.dtos

import kotlinx.serialization.Serializable

/**
 * Wire shape of the response to
 * `GET /api/v1/risk/sod-snapshot/{bookId}/status` and
 * `POST /api/v1/risk/sod-snapshot/{bookId}` exposed by `risk-orchestrator`
 * (see `routes/dtos/SodBaselineStatusResponse.kt`).
 *
 * The demo orchestrator uses this DTO to decide whether the day-open
 * baseline has already been captured for a given book on the current
 * simulated trading day. `exists=true` is the idempotency signal.
 *
 * The Ktor JSON decoder is configured with `ignoreUnknownKeys=true`, so
 * additional upstream fields are silently tolerated.
 */
@Serializable
data class SodBaselineStatusDto(
    val exists: Boolean,
    val baselineDate: String? = null,
    val snapshotType: String? = null,
    val createdAt: String? = null,
    val sourceJobId: String? = null,
    val calculationType: String? = null,
)

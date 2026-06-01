package com.kinetix.risk.routes.dtos

import kotlinx.serialization.Serializable

/**
 * One row in the Reports tab "Recent Reports" panel (trader-review P2 #24).
 *
 * The gateway proxies `GET /api/v1/reports/recent` straight through to this
 * orchestrator endpoint, so the field names here are the contract the UI's
 * `RecentReport` type binds to: outputId / templateId / timestamp / user /
 * status / downloadUrl / rowCount.
 *
 * Report generation in this service is synchronous — `generateReport` persists
 * a completed output or throws — so a persisted output is always `COMPLETE`.
 * Outputs do not currently capture the requesting principal, so `user` is
 * reported as `SYSTEM` (matching the "SYSTEM for scheduled" contract note).
 */
@Serializable
data class RecentReportResponse(
    val outputId: String,
    val templateId: String,
    val timestamp: String,
    val user: String,
    val status: String,
    val downloadUrl: String,
    val rowCount: Int,
)

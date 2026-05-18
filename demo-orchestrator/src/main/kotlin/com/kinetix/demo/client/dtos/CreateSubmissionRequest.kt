package com.kinetix.demo.client.dtos

import kotlinx.serialization.Serializable

/**
 * Wire shape of `POST /api/v1/submissions` exposed by `regulatory-service`
 * (see `SubmissionRoutes.kt`).
 *
 * Field names mirror the upstream `CreateSubmissionRequest` exactly. The
 * demo orchestrator sends `reportType="DAILY_RISK_SUMMARY"`,
 * `preparerId="demo-orchestrator"`, and a `deadline` of T+1 17:00 UTC in
 * ISO-8601 format.
 */
@Serializable
data class CreateSubmissionRequest(
    val reportType: String,
    val preparerId: String,
    val deadline: String,
)

package com.kinetix.demo.client.dtos

import kotlinx.serialization.Serializable

/**
 * Wire shape of the response body returned by `POST /api/v1/submissions`.
 *
 * Only the fields the demo orchestrator consumes are modelled; remaining
 * fields are ignored on decode via `ignoreUnknownKeys = true`.
 */
@Serializable
data class SubmissionResponse(
    val id: String,
    val reportType: String,
    val status: String,
)

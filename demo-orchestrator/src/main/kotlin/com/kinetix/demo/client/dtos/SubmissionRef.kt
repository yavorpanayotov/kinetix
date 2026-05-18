package com.kinetix.demo.client.dtos

/**
 * Domain shape of a regulatory submission acknowledgement consumed by the
 * demo orchestrator. Holds only the identifiers the demo needs to surface
 * which draft submission was created.
 */
data class SubmissionRef(
    val id: String,
    val reportType: String,
    val status: String,
)

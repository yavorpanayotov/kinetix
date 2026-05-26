package com.kinetix.demo.routes.dtos

import kotlinx.serialization.Serializable

/**
 * Response payload for `GET /demo/bootstrap-status`.
 *
 * All count fields are null when the bootstrap has not yet completed
 * (state is [com.kinetix.demo.schedule.BootstrapState.NOT_STARTED] or
 * [com.kinetix.demo.schedule.BootstrapState.IN_PROGRESS]).
 */
@Serializable
data class BootstrapStatusResponse(
    val state: String,
    val successCount: Int? = null,
    val failureCount: Int? = null,
    val failedBooks: List<String>? = null,
    val sodSuccessCount: Int? = null,
    val sodFailureCount: Int? = null,
    val sodFailedBooks: List<String>? = null,
    val durationMillis: Long? = null,
)

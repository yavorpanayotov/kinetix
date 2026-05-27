package com.kinetix.audit.dlq

import kotlinx.serialization.Serializable

@Serializable
data class DlqReplayResult(
    val successCount: Int,
    val failureCount: Int,
    val total: Int,
    // Number of events skipped because an audit row already exists for the
    // same tradeId — i.e. the event has already been replayed. Defaults to
    // zero so existing call sites that only inspect success/failure/total
    // continue to compile.
    val skippedCount: Int = 0,
)

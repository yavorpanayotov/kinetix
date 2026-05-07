package com.kinetix.fix.kafka

import com.kinetix.common.execution.ExecutionReportEvent
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Test double for [ExecutionReportPublisher] that captures every published event and
 * exposes them via [published]. Thread-safe so concurrent FIX callback dispatch in
 * acceptance tests doesn't drop entries.
 */
class RecordingExecutionReportPublisher(
    private val onPublish: (ExecutionReportEvent) -> Unit = {},
) : ExecutionReportPublisher {
    private val _published = CopyOnWriteArrayList<ExecutionReportEvent>()
    val published: List<ExecutionReportEvent> get() = _published.toList()

    override suspend fun publish(event: ExecutionReportEvent) {
        onPublish(event)
        _published += event
    }
}

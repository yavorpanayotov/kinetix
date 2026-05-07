package com.kinetix.fix.kafka

import com.kinetix.common.execution.ExecutionReportEvent

/**
 * Publishes [ExecutionReportEvent]s to the `execution.reports` Kafka topic
 * (ADR-0035 phase 3 commit 2). Production implementation is
 * [KafkaExecutionReportPublisher]; tests substitute [RecordingExecutionReportPublisher]
 * to capture published events without an external broker.
 */
fun interface ExecutionReportPublisher {
    suspend fun publish(event: ExecutionReportEvent)
}

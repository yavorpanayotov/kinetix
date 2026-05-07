package com.kinetix.position.kafka

import com.kinetix.common.kafka.events.RiskBreakEvent
import org.slf4j.LoggerFactory

class NoOpRiskBreakPublisher : RiskBreakPublisher {
    private val logger = LoggerFactory.getLogger(NoOpRiskBreakPublisher::class.java)
    override suspend fun publish(event: RiskBreakEvent) {
        logger.info("RiskBreak (no-op): eventId={} breakType={} severity={}", event.eventId, event.breakType, event.severity)
    }
}

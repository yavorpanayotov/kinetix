package com.kinetix.position.kafka

import com.kinetix.common.kafka.events.RiskBreakEvent

interface RiskBreakPublisher {
    suspend fun publish(event: RiskBreakEvent)
}

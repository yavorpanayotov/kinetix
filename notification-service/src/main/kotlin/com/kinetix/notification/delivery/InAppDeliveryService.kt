package com.kinetix.notification.delivery

import com.kinetix.notification.model.AlertEvent
import com.kinetix.notification.model.AlertStatus
import com.kinetix.notification.model.DeliveryChannel
import com.kinetix.notification.persistence.AlertEventRepository
import org.slf4j.LoggerFactory

class InAppDeliveryService(
    val repository: AlertEventRepository,
    private val metrics: InAppDeliveryMetrics? = null,
) : DeliveryService {
    private val logger = LoggerFactory.getLogger(InAppDeliveryService::class.java)

    override val channel: DeliveryChannel = DeliveryChannel.IN_APP

    override suspend fun deliver(event: AlertEvent) {
        try {
            repository.save(event)
        } catch (e: Exception) {
            metrics?.recordFailure(event.severity.name)
            logger.error(
                "In-app alert delivery failed: rule={}, severity={}",
                event.ruleName,
                event.severity,
                e,
            )
            throw e
        }
        metrics?.recordDelivered(event.severity.name)
        logger.info("In-app alert delivered: rule={}, severity={}", event.ruleName, event.severity)
    }

    suspend fun getRecentAlerts(limit: Int = 50, status: AlertStatus? = null): List<AlertEvent> {
        return repository.findRecent(limit, status)
    }
}

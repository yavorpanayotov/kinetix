package com.kinetix.gateway.routes

import io.ktor.server.config.ApplicationConfig

/**
 * Builds the canonical map of downstream services monitored by the aggregated
 * system-health endpoint (GET /api/v1/system/health).
 *
 * Every service the platform depends on at runtime must appear here — the
 * System tab renders exactly this set, so a service missing from this map is
 * invisible to operators even when it is down (this is how a dead fix-gateway
 * shipped while the dashboard showed all-green).
 */
fun monitoredServiceUrls(servicesConfig: ApplicationConfig): Map<String, String> = mapOf(
    "position-service" to servicesConfig.property("position.url").getString(),
    "price-service" to servicesConfig.property("price.url").getString(),
    "risk-orchestrator" to servicesConfig.property("risk.url").getString(),
    "notification-service" to servicesConfig.property("notification.url").getString(),
    "rates-service" to servicesConfig.property("rates.url").getString(),
    "reference-data-service" to servicesConfig.property("referenceData.url").getString(),
    "volatility-service" to servicesConfig.property("volatility.url").getString(),
    "correlation-service" to servicesConfig.property("correlation.url").getString(),
    "regulatory-service" to servicesConfig.property("regulatory.url").getString(),
    "audit-service" to servicesConfig.property("audit.url").getString(),
    "ai-insights-service" to servicesConfig.property("insights.url").getString(),
    "fix-gateway" to servicesConfig.property("fix.url").getString(),
)

package com.kinetix.gateway.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainExactly
import io.ktor.server.config.MapApplicationConfig

class MonitoredServicesTest : FunSpec({

    test("includes every downstream service the platform depends on, including order routing and AI insights") {
        val config = MapApplicationConfig(
            "position.url" to "http://position:8081",
            "price.url" to "http://price:8082",
            "risk.url" to "http://risk:8083",
            "notification.url" to "http://notification:8086",
            "rates.url" to "http://rates:8088",
            "referenceData.url" to "http://refdata:8089",
            "volatility.url" to "http://volatility:8090",
            "correlation.url" to "http://correlation:8091",
            "regulatory.url" to "http://regulatory:8087",
            "audit.url" to "http://audit:8084",
            "insights.url" to "http://insights:8095",
            "fix.url" to "http://fix:8092",
        )

        monitoredServiceUrls(config) shouldContainExactly mapOf(
            "position-service" to "http://position:8081",
            "price-service" to "http://price:8082",
            "risk-orchestrator" to "http://risk:8083",
            "notification-service" to "http://notification:8086",
            "rates-service" to "http://rates:8088",
            "reference-data-service" to "http://refdata:8089",
            "volatility-service" to "http://volatility:8090",
            "correlation-service" to "http://correlation:8091",
            "regulatory-service" to "http://regulatory:8087",
            "audit-service" to "http://audit:8084",
            "ai-insights-service" to "http://insights:8095",
            "fix-gateway" to "http://fix:8092",
        )
    }
})

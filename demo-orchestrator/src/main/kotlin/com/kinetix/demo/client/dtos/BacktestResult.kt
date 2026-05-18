package com.kinetix.demo.client.dtos

/**
 * Domain shape of a regulatory backtest outcome consumed by the demo
 * orchestrator. Decoupled from the upstream wire DTO so callers don't depend
 * on `regulatory-service` JSON shape.
 */
data class BacktestResult(
    val violationCount: Int,
    val kupiecPass: Boolean,
    val trafficLightZone: String,
)

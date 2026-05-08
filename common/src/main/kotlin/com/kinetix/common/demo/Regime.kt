package com.kinetix.common.demo

enum class Regime(val volMultiplier: Double, val correlationLift: Double, val ratesDrift: Double) {
    CALM(volMultiplier = 1.0, correlationLift = 0.0, ratesDrift = 0.0),
    PRE_STRESS(volMultiplier = 1.4, correlationLift = 0.10, ratesDrift = 0.0),
    STRESS_2020_ANALOG(volMultiplier = 3.5, correlationLift = 0.45, ratesDrift = -0.0006),
    STRESS_2022_ANALOG(volMultiplier = 2.2, correlationLift = 0.25, ratesDrift = 0.0004),
    RECOVERY(volMultiplier = 1.6, correlationLift = 0.15, ratesDrift = 0.0001),
}

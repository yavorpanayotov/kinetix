pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "kinetix"

include(
    "proto",
    "common",
    "gateway",
    "position-service",
    "price-service",
    "rates-service",
    "risk-orchestrator",
    "regulatory-service",
    "notification-service",
    "audit-service",
    "fix-gateway",
    "reference-data-service",
    "volatility-service",
    "correlation-service",
    "demo-orchestrator",
    "end2end-tests",
    "schema-tests",
    "smoke-tests",
    "test-support",
)

plugins {
    id("kinetix.kotlin-common")
    id("kinetix.kotlin-testing")
    id("org.jetbrains.kotlin.plugin.serialization")
}

tasks.named<Test>("test") {
    filter {
        isFailOnNoMatchingTests = false
    }
}

dependencies {
    testImplementation(project(":common"))
    testImplementation(project(":proto"))
    testImplementation(libs.bundles.grpc)
    testImplementation(project(":risk-orchestrator"))
    testImplementation(project(":notification-service"))
    testImplementation(project(":position-service"))
    testImplementation(project(":price-service"))
    testImplementation(project(":audit-service"))
    testImplementation(libs.kotlinx.serialization.json)
}

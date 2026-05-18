plugins {
    id("kinetix.kotlin-library")
    id("kinetix.kotlin-testing")
}

dependencies {
    // `api` so consumers of :test-support can see Trade and its supporting
    // domain types when using fixture builders like TestTrade.
    api(project(":common"))

    api(libs.kafka.clients)
    api(libs.testcontainers.core)
    api(libs.testcontainers.kafka)
    api(libs.testcontainers.postgresql)
    api(libs.bundles.exposed)
    api(libs.flyway.core)
    api(libs.flyway.postgresql)
    api(libs.hikari)
    api(libs.grpc.netty)
    api(libs.grpc.stub)
}

plugins {
    id("kinetix.kotlin-service")
    id("kinetix.kotlin-testing")
}

application {
    mainClass.set("com.kinetix.demo.ApplicationKt")
}

dependencies {
    implementation(project(":common"))
    implementation(project(":proto"))
    implementation(libs.bundles.grpc)
    implementation(libs.grpc.netty)
    implementation(libs.bundles.exposed)
    implementation(libs.bundles.database)
    implementation(libs.kafka.clients)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.redis.lettuce)

    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.micrometer.prometheus)
}

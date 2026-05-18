plugins {
    id("kinetix.kotlin-service")
    id("kinetix.kotlin-testing")
}

application {
    mainClass.set("com.kinetix.referencedata.ApplicationKt")
}

dependencies {
    implementation(project(":common"))
    implementation(project(":proto"))
    implementation(libs.bundles.grpc)
    implementation(libs.grpc.netty)
    implementation(libs.bundles.exposed)
    implementation(libs.bundles.database)
    implementation(libs.kafka.clients)
    implementation(libs.redis.lettuce)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":test-support"))
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
}

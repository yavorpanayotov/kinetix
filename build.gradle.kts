plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.kover)
}

dependencies {
    subprojects
        .filter { it.name !in setOf("proto", "test-support") }
        .forEach { kover(project(":${it.name}")) }
}

kover {
    reports {
        filters {
            excludes {
                classes(
                    "*.dtos.*",
                    "*.routes.dtos.*",
                    "*Dto",
                    "*Dto\$*",
                    "*Event",
                    "*Event\$*",
                    "*Request",
                    "*Response",
                    "*ApplicationKt",
                    "com.kinetix.proto.*",
                )
                annotatedBy("kotlinx.serialization.Serializable")
            }
        }
    }
}

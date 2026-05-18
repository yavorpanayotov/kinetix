plugins {
    id("org.jetbrains.kotlinx.kover")
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
        verify {
            rule {
                disabled = true
            }
        }
        total {
            html {
                onCheck = false
            }
            xml {
                onCheck = false
            }
        }
    }
}

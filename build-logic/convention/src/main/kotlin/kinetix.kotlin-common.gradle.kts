plugins {
    id("org.jetbrains.kotlin.jvm")
    id("kinetix.kotlin-coverage")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

// PIT mutation testing — CLI-via-exec workaround.
//
// The official Gradle plugin `info.solidsoft.gradle.pitest:gradle-pitest-plugin`
// references `reporting.baseDir` which Gradle 9 removed (see
// .github/workflows/mutation.yml for the deferred history). Until a
// Gradle 9-compatible release ships, we invoke the PIT CLI directly
// via a `JavaExec` task wired against an isolated `pitestCli`
// configuration.
//
// Reports land at `build/reports/pitest/mutations.xml` (and the
// matching `index.html`) — `--timestampedReports=false` keeps the
// path stable for CI consumers and the work-plan acceptance check.
//
// Consumers must apply a plugin that brings in the `java`/`kotlin.jvm`
// plugins first (e.g. `kinetix.kotlin-service` or `kinetix.kotlin-library`).
// We resolve `sourceSets` inside `afterEvaluate` so the kotlin/java
// plugins are guaranteed to have registered their extensions before
// we read them.
//
// Important: PIT's CLI expects COMMA-separated lists for --classPath,
// --sourceDirs and --mutableCodePaths. Joining with `File.pathSeparator`
// (e.g. `FileCollection.asPath`) collapses everything into a single
// classpath entry and PIT silently reports zero mutable classes.
//
// Tuning per consuming module — pick whichever knob fits the change:
//   * `pitest { targetClasses.set("..."); targetTests.set("...") }`
//     in the module's build.gradle.kts (preferred for stable scope).
//   * `-Ppitest.targetClasses=... -Ppitest.targetTests=...` on the
//     CLI (one-off ad-hoc scopes).
// Both default to `com.kinetix.*` when unset — broad, slow, but
// always yields at least one mutation so the plumbing acceptance
// passes.

import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Property
import org.gradle.api.tasks.JavaExec

abstract class KinetixPitestExtension {
    abstract val targetClasses: Property<String>
    abstract val targetTests: Property<String>
}

val pitestExtension = extensions.create<KinetixPitestExtension>("pitest")
pitestExtension.targetClasses.convention(
    providers.gradleProperty("pitest.targetClasses").orElse("com.kinetix.*"),
)
pitestExtension.targetTests.convention(
    providers.gradleProperty("pitest.targetTests").orElse("com.kinetix.*"),
)

val libs = versionCatalogs.named("libs")

val pitestCli =
    configurations.create("pitestCli") {
        isCanBeResolved = true
        isCanBeConsumed = false
        description = "Classpath for the PIT mutation-testing command-line entrypoint."
    }

dependencies {
    "pitestCli"(libs.findLibrary("pitest-command-line").get())
    // Required so PIT can discover and execute JUnit 5 platform tests
    // (Kotest's FunSpec uses the kotest-runner-junit5 TestEngine).
    "pitestCli"(libs.findLibrary("pitest-junit5-plugin").get())
}

afterEvaluate {
    val sourceSets = the<JavaPluginExtension>().sourceSets
    val main = sourceSets.named("main").get()
    val test = sourceSets.named("test").get()

    // Resolve everything to plain Strings / Files at configuration
    // time so the registered JavaExec task does not capture references
    // back to the script (configuration cache cannot serialize those).
    val targetClasses = pitestExtension.targetClasses.get()
    val targetTests = pitestExtension.targetTests.get()
    val reportDir =
        layout.buildDirectory.dir("reports/pitest").get().asFile.absolutePath
    val analysisClasspath = files(main.runtimeClasspath, test.runtimeClasspath)
    val sourceDirsArg =
        main.allSource.srcDirs.filter { it.exists() }
            .joinToString(",") { it.absolutePath }
    val mainClassDirs = main.output.classesDirs

    tasks.register<JavaExec>("pitest") {
        group = "verification"
        description = "Runs PIT mutation testing via the pitest-command-line workaround."

        // PIT analyses compiled classes and runs the test suite under
        // mutation — we need both compile graphs ready before launching.
        dependsOn("classes", "testClasses")
        mustRunAfter("test")

        mainClass.set("org.pitest.mutationtest.commandline.MutationCoverageReport")
        classpath = pitestCli

        outputs.dir(reportDir)
        inputs.files(mainClassDirs).withPropertyName("mutableClassDirs")
        inputs.files(analysisClasspath).withPropertyName("analysisClasspath")

        doFirst {
            val classpathArg =
                analysisClasspath.files.filter { it.exists() }
                    .joinToString(",") { it.absolutePath }
            val mutableCodePathsArg =
                mainClassDirs.files.filter { it.exists() }
                    .joinToString(",") { it.absolutePath }
            args(
                "--reportDir", reportDir,
                "--targetClasses", targetClasses,
                "--targetTests", targetTests,
                "--sourceDirs", sourceDirsArg,
                "--classPath", classpathArg,
                "--mutableCodePaths", mutableCodePathsArg,
                "--outputFormats", "HTML,XML",
                "--timestampedReports", "false",
                "--threads", "2",
                "--testPlugin", "junit5",
            )
        }
    }
}

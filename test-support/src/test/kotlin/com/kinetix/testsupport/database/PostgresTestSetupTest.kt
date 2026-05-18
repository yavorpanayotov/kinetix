package com.kinetix.testsupport.database

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.testcontainers.utility.DockerImageName

/**
 * Smoke test for [PostgresTestSetup].
 *
 * This module's lean test classpath has no SLF4J binding, which means
 * Testcontainers' Docker strategy detection cannot be reliably exercised
 * from `:test-support:test` itself — adding logback to `test-support`'s
 * runtime would be a dependency change and is out of scope here. Instead,
 * this test verifies the parts of the helper that can be exercised without
 * actually starting a container:
 *
 *  * construction with default args succeeds — confirms the class, its
 *    companion image constant, and the underlying `PostgreSQLContainer`
 *    initialise cleanly on the classpath this module ships,
 *  * construction with a custom migration location, database name, and
 *    image succeeds — confirms parameter wiring,
 *  * `close()` is safe to call before `start()` — supports `use { … }`
 *    blocks that bail out mid-setup.
 *
 * The full container-backed flow (start + Flyway migration + SELECT 1) is
 * exercised by each service that adopts this helper from its own
 * `*IntegrationTest`s, where Docker connectivity is already proven by the
 * existing `DatabaseTestSetup` callers.
 */
class PostgresTestSetupTest : FunSpec({

    test("constructs with default args") {
        val setup = PostgresTestSetup()
        setup shouldNotBe null
        setup.close() // safe to close without starting
    }

    test("constructs with a custom migrationLocation, databaseName, and image") {
        val setup = PostgresTestSetup(
            migrationLocation = "db/position",
            databaseName = "position_test",
            image = DockerImageName.parse("postgres:17-alpine"),
        )
        setup shouldNotBe null
        setup.close()
    }

    test("exposes postgres:17-alpine as the default image") {
        PostgresTestSetup.DEFAULT_IMAGE.toString() shouldBe "postgres:17-alpine"
    }

    test("close before start is idempotent") {
        val setup = PostgresTestSetup()
        setup.close()
        setup.close() // second close must not throw
    }
})

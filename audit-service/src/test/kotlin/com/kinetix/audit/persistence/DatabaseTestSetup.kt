package com.kinetix.audit.persistence

import com.kinetix.testsupport.containers.TestcontainerCaps
import org.jetbrains.exposed.sql.Database
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

object DatabaseTestSetup {

    val postgres: PostgreSQLContainer<*> = TestcontainerCaps.tunePostgres(
        PostgreSQLContainer(
            DockerImageName.parse("timescale/timescaledb:latest-pg17")
                .asCompatibleSubstituteFor("postgres"),
        )
            .withDatabaseName("audit_test")
            .withUsername("test")
            .withPassword("test"),
        withTimescale = true,
    )

    // One connection pool shared across every spec in the JVM. Each spec
    // previously created its own Hikari pool (maxPoolSize = 8) against the
    // single shared container — with enough acceptance/integration specs
    // that exhausts Postgres's max_connections ("too many clients already").
    // The container and schema are identical for every spec, so a single
    // pool is correct and keeps the connection budget bounded.
    @Volatile
    private var database: Database? = null

    @Synchronized
    fun startAndMigrate(): Database {
        database?.let { return it }
        if (!postgres.isRunning) {
            postgres.start()
        }
        return DatabaseFactory.init(
            DatabaseConfig(
                jdbcUrl = postgres.jdbcUrl,
                username = postgres.username,
                password = postgres.password,
                maxPoolSize = 5,
            )
        ).also { database = it }
    }
}

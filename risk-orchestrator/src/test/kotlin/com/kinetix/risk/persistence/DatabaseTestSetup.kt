package com.kinetix.risk.persistence

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
            .withDatabaseName("risk_test")
            .withUsername("test")
            .withPassword("test"),
        withTimescale = true,
    )

    @Volatile
    private var database: Database? = null

    /**
     * Starts the shared Postgres container (once), runs migrations (once), and
     * returns a single shared [Database]. Every acceptance spec in the JVM
     * reuses the same Hikari connection pool — creating a fresh pool per spec
     * would exhaust the container's `max_connections` cap once enough
     * DB-backed acceptance classes run in one JVM.
     */
    @Synchronized
    fun startAndMigrate(): Database {
        database?.let { return it }
        if (!postgres.isRunning) {
            postgres.start()
        }
        return RiskDatabaseFactory.init(
            RiskDatabaseConfig(
                jdbcUrl = postgres.jdbcUrl,
                username = postgres.username,
                password = postgres.password,
            )
        ).also { database = it }
    }
}

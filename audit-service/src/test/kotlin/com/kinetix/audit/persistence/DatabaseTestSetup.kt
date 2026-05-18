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

    fun startAndMigrate(): Database {
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
        )
    }
}

package com.kinetix.position.persistence

import com.kinetix.testsupport.containers.TestcontainerCaps
import org.jetbrains.exposed.sql.Database
import org.testcontainers.containers.PostgreSQLContainer

object DatabaseTestSetup {

    val postgres: PostgreSQLContainer<*> = TestcontainerCaps.tunePostgres(
        PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("position_test")
            .withUsername("test")
            .withPassword("test"),
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

package com.kinetix.position.persistence

import com.kinetix.common.persistence.ConnectionPoolConfig
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

    // The Postgres test container is capped at `max_connections=20` by
    // `TestcontainerCaps.tunePostgres`. Each call to `DatabaseFactory.init`
    // creates a fresh HikariDataSource with `ConnectionPoolConfig.forService("position-service")`
    // (maxPoolSize=15) and overwrites the singleton without closing the prior
    // pool — so every acceptance test class would leak ~15 connections. With
    // ~30 test classes in this module that exhausts the container after a
    // handful of specs, producing `PSQLException: sorry, too many clients
    // already` at spec init.
    //
    // Tests share one schema (`beforeEach { TRUNCATE … }` clears state per
    // test) and the JVM runs them sequentially (`maxParallelForks = 1`), so
    // caching a single Database is the natural fix.
    @Volatile
    private var cached: Database? = null

    fun startAndMigrate(): Database {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: run {
                if (!postgres.isRunning) {
                    postgres.start()
                }
                val db = DatabaseFactory.init(
                    DatabaseConfig(
                        jdbcUrl = postgres.jdbcUrl,
                        username = postgres.username,
                        password = postgres.password,
                        // Keep the pool well under the container's
                        // `max_connections=20` ceiling so app-side fixtures
                        // (e.g. additional Hikari pools spun up inside a
                        // single spec) still have headroom.
                        poolConfig = ConnectionPoolConfig(maxPoolSize = 5, minIdle = 1),
                    )
                )
                cached = db
                db
            }
        }
    }
}

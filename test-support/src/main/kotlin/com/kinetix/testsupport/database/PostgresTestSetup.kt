package com.kinetix.testsupport.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Shared Testcontainers-backed Postgres setup. Each test module that needs a
 * real Postgres database should depend on test-support and use this helper
 * rather than duplicating the container + Flyway + Hikari plumbing.
 *
 * Each `PostgresTestSetup` instance owns one container. Per-service migration
 * scripts are selected via the `migrationLocation` parameter — defaults to
 * `db/migration` (the Flyway default), but services typically pass their own
 * path such as `db/position`, `db/correlation`, or `db/volatility`.
 *
 * The default image is `postgres:17-alpine`, matching the per-service
 * `DatabaseTestSetup` duplicates this helper is promoted from. Services that
 * need a TimescaleDB-compatible variant can pass an alternative image via
 * `image`.
 *
 * Lifecycle: call [start] once (it returns a [PostgresHandle] with
 * connection details + a pooled [javax.sql.DataSource]) and `close()` (or
 * use Kotlin's `use {}`) to stop the container and dispose of the pool.
 *
 * Example:
 * ```
 * val setup = PostgresTestSetup(migrationLocation = "db/position")
 * setup.use { container ->
 *     val handle = container.start()
 *     // handle.jdbcUrl, handle.dataSource, ...
 * }
 * ```
 */
class PostgresTestSetup(
    private val migrationLocation: String = "db/migration",
    private val databaseName: String = "kinetix_test",
    private val username: String = "test",
    private val password: String = "test",
    private val image: DockerImageName = DEFAULT_IMAGE,
    private val maxPoolSize: Int = 5,
) : AutoCloseable {

    private val container: PostgreSQLContainer<*> = PostgreSQLContainer(image)
        .withDatabaseName(databaseName)
        .withUsername(username)
        .withPassword(password)

    private var dataSource: HikariDataSource? = null

    /**
     * Starts the Postgres container (if not already running), applies Flyway
     * migrations from [migrationLocation], and returns a [PostgresHandle]
     * exposing connection details and a pooled `DataSource`.
     */
    fun start(): PostgresHandle {
        if (!container.isRunning) {
            container.start()
        }
        val ds = createDataSource()
        runMigrations(ds)
        dataSource = ds
        return PostgresHandle(
            jdbcUrl = container.jdbcUrl,
            username = container.username,
            password = container.password,
            host = container.host,
            port = container.firstMappedPort,
            dataSource = ds,
        )
    }

    private fun createDataSource(): HikariDataSource {
        val config = HikariConfig().apply {
            jdbcUrl = container.jdbcUrl
            username = container.username
            password = container.password
            maximumPoolSize = maxPoolSize
            validate()
        }
        return HikariDataSource(config)
    }

    private fun runMigrations(ds: HikariDataSource) {
        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:$migrationLocation")
            .load()
            .migrate()
    }

    override fun close() {
        dataSource?.close()
        dataSource = null
        if (container.isRunning) {
            container.stop()
        }
    }

    companion object {
        /** Default image — matches the majority of per-service duplicates. */
        val DEFAULT_IMAGE: DockerImageName = DockerImageName.parse("postgres:17-alpine")
    }
}

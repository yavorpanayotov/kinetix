package com.kinetix.testsupport.database

import javax.sql.DataSource

/**
 * Connection details and pooled [DataSource] for a running Postgres test
 * container started by [PostgresTestSetup]. Returned from
 * [PostgresTestSetup.start] after migrations have been applied.
 */
data class PostgresHandle(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val host: String,
    val port: Int,
    val dataSource: DataSource,
)

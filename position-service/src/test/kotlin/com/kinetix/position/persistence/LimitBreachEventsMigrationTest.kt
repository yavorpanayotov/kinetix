package com.kinetix.position.persistence

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Verifies that the limit breach events migration (V32) creates the
 * `limit_breach_events` table with the columns and indexes required by the
 * morning brief's `get_recent_breaches` MCP tool (see docs/plans/ai-v2.md §6).
 *
 * We validate the migration SQL content directly rather than running it against
 * a database: this keeps the check in the fast plain `test` task and decouples
 * it from a live Postgres container.
 */
class LimitBreachEventsMigrationTest : FunSpec({

    val resourcePath = "db/position/V32__create_limit_breach_events.sql"

    val migrationSql: String by lazy {
        val resource = Thread.currentThread().contextClassLoader
            .getResourceAsStream(resourcePath)
            ?: error("V32__create_limit_breach_events.sql not found on classpath")
        resource.bufferedReader().readText()
    }

    // The CREATE TABLE block — everything before the first CREATE INDEX.
    val createTableBlock: String by lazy {
        val indexAt = migrationSql.indexOf("CREATE INDEX")
        if (indexAt >= 0) migrationSql.substring(0, indexAt) else migrationSql
    }

    fun columnLine(column: String): String =
        createTableBlock.lines().first { it.trimStart().startsWith(column) }

    test("version is V32 — one past the current highest migration") {
        val resource = Thread.currentThread().contextClassLoader
            .getResourceAsStream(resourcePath)
        resource.shouldNotBeNull()
        resource.close()
    }

    test("migration creates the limit_breach_events table") {
        migrationSql shouldContain "CREATE TABLE IF NOT EXISTS limit_breach_events"
    }

    test("table has all nine required columns") {
        listOf(
            "id",
            "entity_id",
            "book_id",
            "limit_type",
            "severity",
            "current_value",
            "limit_value",
            "breached_at",
            "resolved_at",
        ).forEach { column ->
            createTableBlock shouldContain column
        }
    }

    test("id is the primary key") {
        val idLine = columnLine("id")
        idLine shouldContain "UUID"
        idLine shouldContain "PRIMARY KEY"
    }

    test("resolved_at is nullable") {
        columnLine("resolved_at") shouldNotContain "NOT NULL"
    }

    test("breached_at is NOT NULL with a default") {
        val breachedAtLine = columnLine("breached_at")
        breachedAtLine shouldContain "NOT NULL"
        breachedAtLine shouldContain "DEFAULT"
    }

    test("creates the (book_id, breached_at DESC) index") {
        migrationSql shouldContain "(book_id, breached_at DESC)"
    }

    test("creates the (severity, breached_at DESC) index") {
        migrationSql shouldContain "(severity, breached_at DESC)"
    }

    test("does not use CREATE INDEX CONCURRENTLY (incompatible with Flyway transactions)") {
        migrationSql.uppercase() shouldNotContain "CONCURRENTLY"
    }
})

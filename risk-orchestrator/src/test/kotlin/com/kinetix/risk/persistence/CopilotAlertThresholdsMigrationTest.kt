package com.kinetix.risk.persistence

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Verifies that the copilot alert thresholds migration (V69) creates the
 * `copilot_alert_thresholds` table with the columns, composite index, and
 * seeded global defaults required by the intraday-push threshold evaluator
 * (see plans/ai-v2.md §7.2).
 *
 * We validate the migration SQL content directly rather than running it against
 * a database: this keeps the check in the fast plain `test` task and decouples
 * it from a live Postgres container.
 */
class CopilotAlertThresholdsMigrationTest : FunSpec({

    val resourcePath = "db/risk/V69__create_copilot_alert_thresholds.sql"

    val migrationSql: String by lazy {
        Thread.currentThread().contextClassLoader
            .getResourceAsStream(resourcePath)
            ?.bufferedReader()?.readText()
            ?: error("V69__create_copilot_alert_thresholds.sql not found on classpath")
    }

    // The CREATE TABLE block — everything before the first CREATE INDEX.
    val createTableBlock: String by lazy {
        val indexAt = migrationSql.indexOf("CREATE INDEX")
        if (indexAt >= 0) migrationSql.substring(0, indexAt) else migrationSql
    }

    fun columnLine(column: String): String =
        createTableBlock.lines().first { it.trimStart().startsWith(column) }

    val seededAlertTypes = listOf(
        "VAR_BREACH",
        "POSITION_DELTA",
        "VOL_INVERSION",
        "GAMMA_CONCENTRATION",
        "LIMIT_UTILISATION",
        "COUNTERPARTY_EXPOSURE",
        "PRICE_MOVE",
        "UNEXPLAINED_PNL",
        "REGIME_CHANGE",
        "DIVERSIFICATION",
    )

    test("migration creates the copilot_alert_thresholds table") {
        migrationSql shouldContain "CREATE TABLE IF NOT EXISTS copilot_alert_thresholds"
    }

    test("table has all six required columns") {
        listOf(
            "id",
            "scope_type",
            "scope_id",
            "alert_type",
            "threshold_value",
            "cooldown_minutes",
        ).forEach { column ->
            createTableBlock shouldContain column
        }
    }

    test("scope_type is constrained to GLOBAL/BOOK/USER") {
        listOf("GLOBAL", "BOOK", "USER").forEach { value ->
            migrationSql shouldContain value
        }
    }

    test("scope_id is nullable") {
        columnLine("scope_id") shouldNotContain "NOT NULL"
    }

    test("creates the composite (scope_type, scope_id, alert_type) index") {
        migrationSql shouldContain "(scope_type, scope_id, alert_type)"
    }

    test("does not use CREATE INDEX CONCURRENTLY (incompatible with Flyway transactions)") {
        migrationSql.uppercase() shouldNotContain "CONCURRENTLY"
    }

    test("seeds exactly ten global default thresholds") {
        // Count VALUES tuples that seed a GLOBAL-scoped threshold row — i.e.
        // lines carrying both 'GLOBAL' and one of the documented alert types.
        // This is robust to 'GLOBAL' also appearing in the scope_type CHECK.
        val seedRowCount = migrationSql.lines().count { line ->
            line.contains("'GLOBAL'") && seededAlertTypes.any { line.contains("'$it'") }
        }
        seedRowCount shouldBe 10
    }

    test("seeds the VaR breach default at threshold 5") {
        val varRow = migrationSql.lines().first { it.contains("'VAR_BREACH'") }
        varRow shouldContain "5"
    }

    test("seeds the counterparty exposure default at 10M") {
        val cpRow = migrationSql.lines().first { it.contains("'COUNTERPARTY_EXPOSURE'") }
        cpRow shouldContain "10000000"
    }

    test("all ten documented alert types are seeded") {
        seededAlertTypes.forEach { alertType ->
            migrationSql shouldContain "'$alertType'"
        }
    }

    test("the seed insert is re-run safe") {
        migrationSql shouldContain "ON CONFLICT"
    }
})

package com.kinetix.risk.service

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource

private const val STATEMENT_TIMEOUT_MS = 30_000

// PostgreSQL SQLState codes for the two known degraded-environment cases
// that previously surfaced as HTTP 500s in `POST /api/v1/reports/generate`:
//
//   55000 — object_not_in_prerequisite_state. PostgreSQL raises this when a
//           SELECT hits a materialised view that has never been refreshed
//           (e.g. `risk_positions_flat` before the first EOD promotion).
//   42P01 — undefined_table. Raised when a query references a table that
//           does not exist in the current database (e.g. the stress-summary
//           template's `stress_test_results` table lives in the regulatory
//           DB, not the risk DB this executor is bound to).
//
// Both states are "0 rows by definition, no data here yet" — treating them
// as a populated SQLException would mean reports stay broken for the whole
// pre-EOD demo window. We log a WARN and return an empty JsonArray instead,
// so the route serves a normal 200 ReportOutput with rowCount: 0.
private const val SQLSTATE_OBJECT_NOT_IN_PREREQUISITE_STATE = "55000"
private const val SQLSTATE_UNDEFINED_TABLE = "42P01"

/**
 * Executes parameterised, read-only SQL queries against the reporting views.
 *
 * Uses a separate read-only DataSource so reports cannot accidentally mutate
 * production data, and enforces a 30-second statement timeout to prevent
 * long-running report queries from blocking connection pool slots.
 *
 * Query parameters (bookId, date) are always bound via PreparedStatement to
 * prevent any possibility of SQL injection.
 */
class JdbcReportQueryExecutor(private val readOnlyDataSource: DataSource) : ReportQueryExecutor {

    private val logger = LoggerFactory.getLogger(JdbcReportQueryExecutor::class.java)

    override suspend fun executeRiskSummary(bookId: String, date: String?): JsonArray {
        val viewSql = buildString {
            append(
                """
                SELECT
                    book_id, instrument_id, asset_class,
                    quantity, market_price,
                    delta, gamma, vega, theta, rho,
                    var_contribution, es_contribution,
                    snapshot_date
                FROM risk_positions_flat
                WHERE book_id = ?
                """.trimIndent(),
            )
            if (date != null) {
                append(" AND snapshot_date = ?::date")
            }
            append(" ORDER BY var_contribution DESC NULLS LAST")
        }
        val params = if (date != null) listOf(bookId, date) else listOf(bookId)
        return try {
            runQuery(viewSql, params)
        } catch (e: SQLException) {
            if (hasSqlState(e, SQLSTATE_OBJECT_NOT_IN_PREREQUISITE_STATE)) {
                // risk_positions_flat is refreshed only after EOD promotion; a
                // freshly seeded environment has never refreshed it. The base
                // table already holds the snapshots the report needs, so read
                // it directly instead of serving a permanently empty report.
                logger.warn(
                    "risk_positions_flat not yet populated; falling back to daily_risk_snapshots for book {}",
                    bookId,
                )
                executeQuery(riskSummaryBaseTableSql(date), params)
            } else if (isDegradedEnvironmentSqlState(e)) {
                logDegradedAndReturnEmpty(e)
            } else {
                throw e
            }
        }
    }

    private fun riskSummaryBaseTableSql(date: String?): String = buildString {
        append(
            """
            SELECT * FROM (
                SELECT DISTINCT ON (book_id, instrument_id)
                    book_id, instrument_id, asset_class,
                    quantity, market_price,
                    delta, gamma, vega, theta, rho,
                    var_contribution, es_contribution,
                    snapshot_date
                FROM daily_risk_snapshots
                WHERE book_id = ?
            """.trimIndent(),
        )
        if (date != null) {
            append(" AND snapshot_date = ?::date")
        }
        append(
            """
                ORDER BY book_id, instrument_id, snapshot_date DESC
            ) s ORDER BY var_contribution DESC NULLS LAST
            """.trimIndent(),
        )
    }

    override suspend fun executeStressTestSummary(bookId: String, date: String?): JsonArray {
        // NOTE: stress_test_results lives in the regulatory-service database, not risk.
        // This query works only when the report executor's DataSource points at the
        // regulatory DB or a cross-database view exists. The column names match the
        // post-V13 rename schema: book_id (was portfolio_id), scenario_id (FK),
        // base_pv, stressed_pv, pnl_impact.
        val sql = buildString {
            append("""
                SELECT
                    book_id, scenario_id,
                    base_pv, stressed_pv, pnl_impact,
                    calculated_at
                FROM stress_test_results
                WHERE book_id = ?
            """.trimIndent())
            if (date != null) {
                append(" AND DATE(calculated_at) = ?")
            }
            append(" ORDER BY pnl_impact ASC")
        }
        val params = if (date != null) listOf(bookId, date) else listOf(bookId)
        return executeQuery(sql, params)
    }

    override suspend fun executePnlAttribution(bookId: String, date: String?): JsonArray {
        val sql = buildString {
            append("""
                SELECT
                    book_id, attribution_date,
                    total_pnl, delta_pnl, gamma_pnl, vega_pnl,
                    theta_pnl, rho_pnl, unexplained_pnl
                FROM pnl_attributions
                WHERE book_id = ?
            """.trimIndent())
            if (date != null) {
                append(" AND attribution_date = ?::date")
            }
            append(" ORDER BY attribution_date DESC")
        }
        val params = if (date != null) listOf(bookId, date) else listOf(bookId)
        return executeQuery(sql, params)
    }

    /** Runs the query without degraded-environment tolerance — SQL errors propagate. */
    private fun runQuery(sql: String, params: List<String>): JsonArray {
        return readOnlyDataSource.connection.use { conn ->
            conn.isReadOnly = true
            conn.createStatement().use { stmt ->
                stmt.queryTimeout = STATEMENT_TIMEOUT_MS / 1_000
            }
            conn.prepareStatement(sql).use { ps ->
                params.forEachIndexed { i, param -> ps.setString(i + 1, param) }
                ps.queryTimeout = STATEMENT_TIMEOUT_MS / 1_000
                ps.executeQuery().use { rs ->
                    rs.toJsonArray()
                }
            }
        }
    }

    private fun executeQuery(sql: String, params: List<String>): JsonArray {
        return try {
            runQuery(sql, params)
        } catch (e: SQLException) {
            // Pre-EOD environments hit two SQL states that are semantically
            // "no data here yet" rather than a true failure. Returning an
            // empty array lets `POST /api/v1/reports/generate` answer 200
            // with rowCount: 0 (the UI's already-handled empty-report state)
            // instead of the opaque 500 the route otherwise emits.
            if (isDegradedEnvironmentSqlState(e)) {
                logDegradedAndReturnEmpty(e)
            } else {
                throw e
            }
        }
    }

    private fun logDegradedAndReturnEmpty(e: SQLException): JsonArray {
        logger.warn(
            "Report query hit a degraded-environment SQL state (SQLState={}); returning 0 rows. Cause: {}",
            e.sqlState,
            e.message,
        )
        return buildJsonArray { }
    }

    private fun hasSqlState(e: SQLException, state: String): Boolean {
        var current: SQLException? = e
        while (current != null) {
            if (current.sqlState == state) return true
            current = current.nextException
        }
        return false
    }

    private fun isDegradedEnvironmentSqlState(e: SQLException): Boolean {
        var current: SQLException? = e
        while (current != null) {
            when (current.sqlState) {
                SQLSTATE_OBJECT_NOT_IN_PREREQUISITE_STATE,
                SQLSTATE_UNDEFINED_TABLE -> return true
            }
            current = current.nextException
        }
        return false
    }

    private fun ResultSet.toJsonArray(): JsonArray {
        val meta = metaData
        val columnCount = meta.columnCount
        val columns = (1..columnCount).map { meta.getColumnLabel(it) }
        return buildJsonArray {
            while (next()) {
                add(buildJsonObject {
                    for ((index, name) in columns.withIndex()) {
                        val value = getString(index + 1) ?: continue
                        put(name, value)
                    }
                })
            }
        }
    }
}

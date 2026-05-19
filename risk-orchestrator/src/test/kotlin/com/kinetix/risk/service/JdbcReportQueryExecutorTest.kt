package com.kinetix.risk.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.throwable.shouldHaveMessage
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import javax.sql.DataSource

/**
 * Unit tests for `JdbcReportQueryExecutor`'s SQL-failure tolerance.
 *
 * Plan §4.2 — these pin the production-fix half of the Reports 500 bug:
 *   - SQLState 55000 (unpopulated materialised view, e.g. `risk_positions_flat`
 *     pre first EOD promotion) → return an empty `JsonArray`, log WARN.
 *   - SQLState 42P01 (table not in this DataSource, e.g. `stress_test_results`
 *     which lives in the regulatory DB rather than the risk DB) → same.
 *   - Any other `SQLException` (genuine bug, syntax error, …) must still
 *     propagate so the route surfaces a real 500 instead of silently
 *     hiding broken reports.
 *
 * The TDD pair to these tests is the gateway `ReportsGenerateAcceptanceTest`
 * which asserts the wire-level 200-rowCount-0 / 500-with-message contract.
 */
class JdbcReportQueryExecutorTest : FunSpec({

    fun mockDataSourceThatThrows(sqlState: String): DataSource {
        val rs = mockk<ResultSet>(relaxed = true)
        val ps = mockk<PreparedStatement>(relaxed = true)
        every { ps.executeQuery() } throws SQLException("simulated", sqlState)
        every { ps.close() } returns Unit

        val stmt = mockk<java.sql.Statement>(relaxed = true)
        val conn = mockk<Connection>(relaxed = true)
        every { conn.isReadOnly = any() } returns Unit
        every { conn.createStatement() } returns stmt
        every { conn.prepareStatement(any<String>()) } returns ps
        every { conn.close() } returns Unit

        val ds = mockk<DataSource>()
        every { ds.connection } returns conn
        return ds
    }

    fun mockDataSourceWithRows(): DataSource {
        val meta = mockk<ResultSetMetaData>()
        every { meta.columnCount } returns 2
        every { meta.getColumnLabel(1) } returns "book_id"
        every { meta.getColumnLabel(2) } returns "instrument_id"

        val rs = mockk<ResultSet>(relaxed = true)
        // Single row to prove "happy path still works"
        every { rs.next() } returnsMany listOf(true, false)
        every { rs.metaData } returns meta
        every { rs.getString(1) } returns "BOOK-1"
        every { rs.getString(2) } returns "AAPL"

        val ps = mockk<PreparedStatement>(relaxed = true)
        every { ps.executeQuery() } returns rs

        val stmt = mockk<java.sql.Statement>(relaxed = true)
        val conn = mockk<Connection>(relaxed = true)
        every { conn.isReadOnly = any() } returns Unit
        every { conn.createStatement() } returns stmt
        every { conn.prepareStatement(any<String>()) } returns ps

        val ds = mockk<DataSource>()
        every { ds.connection } returns conn
        return ds
    }

    test("executeRiskSummary returns an empty JsonArray when the materialised view is not populated (SQLState 55000)") {
        val executor = JdbcReportQueryExecutor(mockDataSourceThatThrows("55000"))

        val result = runBlocking { executor.executeRiskSummary("balanced-income", null) }

        result.size shouldBe 0
    }

    test("executeStressTestSummary returns an empty JsonArray when the target table is undefined in this DataSource (SQLState 42P01)") {
        val executor = JdbcReportQueryExecutor(mockDataSourceThatThrows("42P01"))

        val result = runBlocking { executor.executeStressTestSummary("balanced-income", null) }

        result.size shouldBe 0
    }

    test("executePnlAttribution returns an empty JsonArray when the materialised view is not populated (SQLState 55000)") {
        // Defensive: even though pnl_attributions is a plain table today, the
        // executor's tolerance must apply uniformly so future migrations to a
        // materialised view don't reintroduce the same 500.
        val executor = JdbcReportQueryExecutor(mockDataSourceThatThrows("55000"))

        val result = runBlocking { executor.executePnlAttribution("balanced-income", null) }

        result.size shouldBe 0
    }

    test("SQL errors with other SQLStates propagate so genuine bugs are not hidden as empty reports") {
        // 22023 is invalid_parameter_value — a real bug class, not a degraded
        // environment. The executor must NOT swallow it.
        val executor = JdbcReportQueryExecutor(mockDataSourceThatThrows("22023"))

        val ex = shouldThrow<SQLException> {
            runBlocking { executor.executeRiskSummary("balanced-income", null) }
        }
        ex shouldHaveMessage "simulated"
    }

    test("happy path: rows from the result set are mapped into JsonObjects keyed by column label") {
        val executor = JdbcReportQueryExecutor(mockDataSourceWithRows())

        val result = runBlocking { executor.executeRiskSummary("BOOK-1", null) }

        result.size shouldBe 1
    }
})

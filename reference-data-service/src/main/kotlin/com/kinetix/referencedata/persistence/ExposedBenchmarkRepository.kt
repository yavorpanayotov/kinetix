package com.kinetix.referencedata.persistence

import com.kinetix.referencedata.model.Benchmark
import com.kinetix.referencedata.model.BenchmarkConstituent
import com.kinetix.referencedata.model.BenchmarkDailyReturn
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ExposedBenchmarkRepository(
    private val db: Database? = null,
) : BenchmarkRepository {

    override suspend fun save(benchmark: Benchmark): Unit = newSuspendedTransaction(db = db) {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val existing = BenchmarksTable
            .selectAll()
            .where { BenchmarksTable.benchmarkId eq benchmark.benchmarkId }
            .singleOrNull()

        if (existing == null) {
            BenchmarksTable.insert {
                it[benchmarkId] = benchmark.benchmarkId
                it[name] = benchmark.name
                it[description] = benchmark.description
                it[createdAt] = now
            }
        }
    }

    override suspend fun findById(benchmarkId: String): Benchmark? =
        newSuspendedTransaction(db = db) {
            BenchmarksTable
                .selectAll()
                .where { BenchmarksTable.benchmarkId eq benchmarkId }
                .singleOrNull()
                ?.let { row ->
                    Benchmark(
                        benchmarkId = row[BenchmarksTable.benchmarkId],
                        name = row[BenchmarksTable.name],
                        description = row[BenchmarksTable.description],
                        createdAt = row[BenchmarksTable.createdAt].toInstant(),
                    )
                }
        }

    override suspend fun findAll(): List<Benchmark> =
        newSuspendedTransaction(db = db) {
            BenchmarksTable
                .selectAll()
                .orderBy(BenchmarksTable.name, SortOrder.ASC)
                .map { row ->
                    Benchmark(
                        benchmarkId = row[BenchmarksTable.benchmarkId],
                        name = row[BenchmarksTable.name],
                        description = row[BenchmarksTable.description],
                        createdAt = row[BenchmarksTable.createdAt].toInstant(),
                    )
                }
        }

    override suspend fun replaceConstituents(
        benchmarkId: String,
        constituents: List<BenchmarkConstituent>,
    ): Unit = newSuspendedTransaction(db = db) {
        if (constituents.isEmpty()) return@newSuspendedTransaction
        val kxDate = constituents.first().asOfDate.toKotlinxDate()

        BenchmarkConstituentsTable.deleteWhere {
            (BenchmarkConstituentsTable.benchmarkId eq benchmarkId) and
                (BenchmarkConstituentsTable.asOfDate eq kxDate)
        }

        for (constituent in constituents) {
            BenchmarkConstituentsTable.insert {
                it[BenchmarkConstituentsTable.benchmarkId] = constituent.benchmarkId
                it[instrumentId] = constituent.instrumentId
                it[weight] = constituent.weight
                it[BenchmarkConstituentsTable.asOfDate] = constituent.asOfDate.toKotlinxDate()
            }
        }
    }

    override suspend fun findConstituents(
        benchmarkId: String,
        asOfDate: LocalDate,
    ): List<BenchmarkConstituent> =
        newSuspendedTransaction(db = db) {
            val kxDate = asOfDate.toKotlinxDate()
            // Constituents are point-in-time snapshots. Resolve the most recent
            // snapshot on or before the requested date rather than requiring an
            // exact match, so callers that default asOfDate to "today" still get
            // the latest known composition.
            val snapshotDate = BenchmarkConstituentsTable
                .selectAll()
                .where {
                    (BenchmarkConstituentsTable.benchmarkId eq benchmarkId) and
                        (BenchmarkConstituentsTable.asOfDate lessEq kxDate)
                }
                .orderBy(BenchmarkConstituentsTable.asOfDate, SortOrder.DESC)
                .limit(1)
                .singleOrNull()
                ?.get(BenchmarkConstituentsTable.asOfDate)
                ?: return@newSuspendedTransaction emptyList()

            BenchmarkConstituentsTable
                .selectAll()
                .where {
                    (BenchmarkConstituentsTable.benchmarkId eq benchmarkId) and
                        (BenchmarkConstituentsTable.asOfDate eq snapshotDate)
                }
                .orderBy(BenchmarkConstituentsTable.instrumentId, SortOrder.ASC)
                .map { row ->
                    BenchmarkConstituent(
                        benchmarkId = row[BenchmarkConstituentsTable.benchmarkId],
                        instrumentId = row[BenchmarkConstituentsTable.instrumentId],
                        weight = row[BenchmarkConstituentsTable.weight],
                        asOfDate = row[BenchmarkConstituentsTable.asOfDate].toJavaLocalDate(),
                    )
                }
        }

    override suspend fun saveReturn(benchmarkReturn: BenchmarkDailyReturn): Unit =
        newSuspendedTransaction(db = db) {
            val kxDate = benchmarkReturn.returnDate.toKotlinxDate()
            val existing = BenchmarkReturnsTable
                .selectAll()
                .where {
                    (BenchmarkReturnsTable.benchmarkId eq benchmarkReturn.benchmarkId) and
                        (BenchmarkReturnsTable.returnDate eq kxDate)
                }
                .singleOrNull()

            if (existing == null) {
                BenchmarkReturnsTable.insert {
                    it[benchmarkId] = benchmarkReturn.benchmarkId
                    it[returnDate] = kxDate
                    it[dailyReturn] = benchmarkReturn.dailyReturn
                }
            }
        }

    override suspend fun findReturns(
        benchmarkId: String,
        from: LocalDate,
        to: LocalDate,
    ): List<BenchmarkDailyReturn> =
        newSuspendedTransaction(db = db) {
            val kxFrom = from.toKotlinxDate()
            val kxTo = to.toKotlinxDate()
            BenchmarkReturnsTable
                .selectAll()
                .where {
                    (BenchmarkReturnsTable.benchmarkId eq benchmarkId) and
                        (BenchmarkReturnsTable.returnDate greaterEq kxFrom) and
                        (BenchmarkReturnsTable.returnDate lessEq kxTo)
                }
                .orderBy(BenchmarkReturnsTable.returnDate, SortOrder.ASC)
                .map { row ->
                    BenchmarkDailyReturn(
                        benchmarkId = row[BenchmarkReturnsTable.benchmarkId],
                        returnDate = row[BenchmarkReturnsTable.returnDate].toJavaLocalDate(),
                        dailyReturn = row[BenchmarkReturnsTable.dailyReturn],
                    )
                }
        }

    private fun LocalDate.toKotlinxDate() =
        kotlinx.datetime.LocalDate(year, monthValue, dayOfMonth)

    private fun kotlinx.datetime.LocalDate.toJavaLocalDate() =
        LocalDate.of(year, monthNumber, dayOfMonth)
}

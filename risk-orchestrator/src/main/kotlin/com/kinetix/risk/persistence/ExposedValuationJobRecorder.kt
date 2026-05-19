package com.kinetix.risk.persistence

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.InstrumentId
import com.kinetix.risk.model.*
import com.kinetix.risk.service.ValuationJobRecorder
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate

class ExposedValuationJobRecorder(private val db: Database? = null) : ValuationJobRecorder {

    override suspend fun save(job: ValuationJob): Unit = newSuspendedTransaction(db = db) {
        ValuationJobsTable.insert {
            it[jobId] = job.jobId
            it[bookId] = job.bookId
            it[triggerType] = job.triggerType.name
            it[status] = job.status.name
            it[valuationDate] = job.valuationDate.toKotlinLocalDate()
            it[startedAt] = OffsetDateTime.ofInstant(job.startedAt, ZoneOffset.UTC)
            it[completedAt] = job.completedAt?.let { ts -> OffsetDateTime.ofInstant(ts, ZoneOffset.UTC) }
            it[durationMs] = job.durationMs
            it[calculationType] = job.calculationType
            it[confidenceLevel] = job.confidenceLevel
            it[varValue] = job.varValue
            it[expectedShortfall] = job.expectedShortfall
            it[pvValue] = job.pvValue
            it[delta] = job.delta
            it[gamma] = job.gamma
            it[vega] = job.vega
            it[theta] = job.theta
            it[rho] = job.rho
            it[positionRisk] = job.positionRiskSnapshot.takeIf { s -> s.isNotEmpty() }?.map { pr -> pr.toJson() }
            it[componentBreakdown] = job.componentBreakdownSnapshot.takeIf { s -> s.isNotEmpty() }?.map { cb -> cb.toJson() }
            it[computedOutputs] = job.computedOutputsSnapshot.takeIf { s -> s.isNotEmpty() }?.map { o -> o.name }
            it[assetClassGreeks] = job.assetClassGreeksSnapshot.takeIf { s -> s.isNotEmpty() }?.map { g -> g.toJson() }
            it[phases] = job.phases.map { phase -> phase.toJson() }
            it[currentPhase] = job.currentPhase?.name
            it[error] = job.error
            it[triggeredBy] = job.triggeredBy
            it[runLabel] = job.runLabel?.name
            it[promotedAt] = job.promotedAt?.let { ts -> OffsetDateTime.ofInstant(ts, ZoneOffset.UTC) }
            it[promotedBy] = job.promotedBy
            it[marketDataSnapshotId] = job.marketDataSnapshotId
            it[manifestId] = job.manifestId
            it[timeHorizonDays] = job.timeHorizonDays
            it[requestedCalculationType] = job.requestedCalculationType
            it[requestedConfidenceLevel] = job.requestedConfidenceLevel
            it[requestedTimeHorizonDays] = job.requestedTimeHorizonDays
            it[correlationId] = job.correlationId
        }
    }

    override suspend fun update(job: ValuationJob): Unit = newSuspendedTransaction(db = db) {
        val existing = ValuationJobsTable
            .selectAll()
            .where { ValuationJobsTable.jobId eq job.jobId }
            .firstOrNull()
        if (existing != null && existing[ValuationJobsTable.promotedAt] != null) {
            throw IllegalStateException("Cannot modify promoted Official EOD job ${job.jobId}")
        }
        ValuationJobsTable.update({ ValuationJobsTable.jobId eq job.jobId }) {
            it[status] = job.status.name
            it[completedAt] = job.completedAt?.let { ts -> OffsetDateTime.ofInstant(ts, ZoneOffset.UTC) }
            it[durationMs] = job.durationMs
            it[calculationType] = job.calculationType
            it[confidenceLevel] = job.confidenceLevel
            it[varValue] = job.varValue
            it[expectedShortfall] = job.expectedShortfall
            it[pvValue] = job.pvValue
            it[delta] = job.delta
            it[gamma] = job.gamma
            it[vega] = job.vega
            it[theta] = job.theta
            it[rho] = job.rho
            it[positionRisk] = job.positionRiskSnapshot.takeIf { s -> s.isNotEmpty() }?.map { pr -> pr.toJson() }
            it[componentBreakdown] = job.componentBreakdownSnapshot.takeIf { s -> s.isNotEmpty() }?.map { cb -> cb.toJson() }
            it[computedOutputs] = job.computedOutputsSnapshot.takeIf { s -> s.isNotEmpty() }?.map { o -> o.name }
            it[assetClassGreeks] = job.assetClassGreeksSnapshot.takeIf { s -> s.isNotEmpty() }?.map { g -> g.toJson() }
            it[phases] = job.phases.map { phase -> phase.toJson() }
            it[currentPhase] = null
            it[error] = job.error
            it[runLabel] = job.runLabel?.name
            it[manifestId] = job.manifestId
            it[timeHorizonDays] = job.timeHorizonDays
            it[requestedCalculationType] = job.requestedCalculationType
            it[requestedConfidenceLevel] = job.requestedConfidenceLevel
            it[requestedTimeHorizonDays] = job.requestedTimeHorizonDays
            it[correlationId] = job.correlationId
        }
    }

    override suspend fun updateCurrentPhase(jobId: UUID, phase: JobPhaseName): Unit = newSuspendedTransaction(db = db) {
        val existing = ValuationJobsTable
            .selectAll()
            .where { ValuationJobsTable.jobId eq jobId }
            .firstOrNull()
        if (existing != null && existing[ValuationJobsTable.promotedAt] != null) {
            throw IllegalStateException("Cannot modify promoted Official EOD job $jobId")
        }
        ValuationJobsTable.update({ ValuationJobsTable.jobId eq jobId }) {
            it[currentPhase] = phase.name
        }
    }

    override suspend fun findByBookId(
        bookId: String,
        limit: Int,
        offset: Int,
        from: Instant?,
        to: Instant?,
        valuationDate: LocalDate?,
        runLabel: RunLabel?,
    ): List<ValuationJob> = newSuspendedTransaction(db = db) {
        ValuationJobsTable
            .selectAll()
            .where {
                var condition = ValuationJobsTable.bookId eq bookId
                if (from != null) {
                    condition = condition and (ValuationJobsTable.startedAt greaterEq OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                }
                if (to != null) {
                    condition = condition and (ValuationJobsTable.startedAt lessEq OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                }
                if (valuationDate != null) {
                    condition = condition and (ValuationJobsTable.valuationDate eq valuationDate.toKotlinLocalDate())
                }
                if (runLabel != null) {
                    condition = condition and (ValuationJobsTable.runLabel eq runLabel.name)
                }
                condition
            }
            .orderBy(ValuationJobsTable.startedAt, SortOrder.DESC)
            .limit(limit)
            .offset(offset.toLong())
            .map { it.toValuationJob() }
    }

    override suspend fun countByBookId(
        bookId: String,
        from: Instant?,
        to: Instant?,
        valuationDate: LocalDate?,
        runLabel: RunLabel?,
    ): Long = newSuspendedTransaction(db = db) {
        ValuationJobsTable
            .selectAll()
            .where {
                var condition = ValuationJobsTable.bookId eq bookId
                if (from != null) {
                    condition = condition and (ValuationJobsTable.startedAt greaterEq OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                }
                if (to != null) {
                    condition = condition and (ValuationJobsTable.startedAt lessEq OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                }
                if (valuationDate != null) {
                    condition = condition and (ValuationJobsTable.valuationDate eq valuationDate.toKotlinLocalDate())
                }
                if (runLabel != null) {
                    condition = condition and (ValuationJobsTable.runLabel eq runLabel.name)
                }
                condition
            }
            .count()
    }

    override suspend fun findByJobId(jobId: UUID): ValuationJob? = newSuspendedTransaction(db = db) {
        ValuationJobsTable
            .selectAll()
            .where { ValuationJobsTable.jobId eq jobId }
            .firstOrNull()
            ?.toValuationJob()
    }

    override suspend fun findDistinctBookIds(): List<String> = newSuspendedTransaction(db = db) {
        ValuationJobsTable
            .select(ValuationJobsTable.bookId)
            .withDistinct()
            .map { it[ValuationJobsTable.bookId] }
    }

    override suspend fun findLatestCompletedByDate(
        bookId: String,
        valuationDate: LocalDate,
    ): ValuationJob? = newSuspendedTransaction(db = db) {
        ValuationJobsTable
            .selectAll()
            .where {
                (ValuationJobsTable.bookId eq bookId) and
                    (ValuationJobsTable.valuationDate eq valuationDate.toKotlinLocalDate()) and
                    (ValuationJobsTable.status eq "COMPLETED")
            }
            .orderBy(ValuationJobsTable.startedAt, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.toValuationJob()
    }

    override suspend fun findLatestCompleted(bookId: String): ValuationJob? = newSuspendedTransaction(db = db) {
        ValuationJobsTable
            .selectAll()
            .where {
                (ValuationJobsTable.bookId eq bookId) and
                    (ValuationJobsTable.status eq "COMPLETED")
            }
            .orderBy(ValuationJobsTable.startedAt, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.toValuationJob()
    }

    override suspend fun findLatestCompletedBeforeDate(
        bookId: String,
        beforeDate: LocalDate,
    ): ValuationJob? = newSuspendedTransaction(db = db) {
        ValuationJobsTable
            .selectAll()
            .where {
                (ValuationJobsTable.bookId eq bookId) and
                    (ValuationJobsTable.valuationDate less beforeDate.toKotlinLocalDate()) and
                    (ValuationJobsTable.status eq "COMPLETED")
            }
            .orderBy(ValuationJobsTable.valuationDate, SortOrder.DESC)
            .orderBy(ValuationJobsTable.startedAt, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.toValuationJob()
    }

    override suspend fun findOfficialEodByDate(
        bookId: String,
        valuationDate: LocalDate,
    ): ValuationJob? = newSuspendedTransaction(db = db) {
        val designation = OfficialEodDesignationsTable
            .selectAll()
            .where {
                (OfficialEodDesignationsTable.bookId eq bookId) and
                    (OfficialEodDesignationsTable.valuationDate eq valuationDate.toKotlinLocalDate())
            }
            .firstOrNull() ?: return@newSuspendedTransaction null

        val jobId = designation[OfficialEodDesignationsTable.jobId]
        ValuationJobsTable
            .selectAll()
            .where { ValuationJobsTable.jobId eq jobId }
            .firstOrNull()
            ?.toValuationJob()
    }

    override suspend fun findOfficialEodRange(
        bookId: String,
        from: LocalDate,
        to: LocalDate,
    ): List<ValuationJob> = newSuspendedTransaction(db = db) {
        val jobIds = OfficialEodDesignationsTable
            .selectAll()
            .where {
                (OfficialEodDesignationsTable.bookId eq bookId) and
                    (OfficialEodDesignationsTable.valuationDate greaterEq from.toKotlinLocalDate()) and
                    (OfficialEodDesignationsTable.valuationDate lessEq to.toKotlinLocalDate())
            }
            .map { it[OfficialEodDesignationsTable.jobId] }

        if (jobIds.isEmpty()) return@newSuspendedTransaction emptyList()

        val scalarColumns = listOf(
            ValuationJobsTable.jobId,
            ValuationJobsTable.bookId,
            ValuationJobsTable.triggerType,
            ValuationJobsTable.status,
            ValuationJobsTable.valuationDate,
            ValuationJobsTable.startedAt,
            ValuationJobsTable.completedAt,
            ValuationJobsTable.durationMs,
            ValuationJobsTable.calculationType,
            ValuationJobsTable.confidenceLevel,
            ValuationJobsTable.varValue,
            ValuationJobsTable.expectedShortfall,
            ValuationJobsTable.pvValue,
            ValuationJobsTable.delta,
            ValuationJobsTable.gamma,
            ValuationJobsTable.vega,
            ValuationJobsTable.theta,
            ValuationJobsTable.rho,
            ValuationJobsTable.error,
            ValuationJobsTable.triggeredBy,
            ValuationJobsTable.runLabel,
            ValuationJobsTable.promotedAt,
            ValuationJobsTable.promotedBy,
            ValuationJobsTable.marketDataSnapshotId,
            ValuationJobsTable.manifestId,
        )

        ValuationJobsTable
            .select(scalarColumns)
            .where { ValuationJobsTable.jobId inList jobIds }
            .orderBy(ValuationJobsTable.valuationDate, SortOrder.ASC)
            .map { row ->
                ValuationJob(
                    jobId = row[ValuationJobsTable.jobId],
                    bookId = row[ValuationJobsTable.bookId],
                    triggerType = TriggerType.valueOf(row[ValuationJobsTable.triggerType]),
                    status = RunStatus.valueOf(row[ValuationJobsTable.status]),
                    startedAt = row[ValuationJobsTable.startedAt].toInstant(),
                    valuationDate = row[ValuationJobsTable.valuationDate].toJavaLocalDate(),
                    completedAt = row[ValuationJobsTable.completedAt]?.toInstant(),
                    durationMs = row[ValuationJobsTable.durationMs],
                    calculationType = row[ValuationJobsTable.calculationType],
                    confidenceLevel = row[ValuationJobsTable.confidenceLevel],
                    varValue = row[ValuationJobsTable.varValue],
                    expectedShortfall = row[ValuationJobsTable.expectedShortfall],
                    pvValue = row[ValuationJobsTable.pvValue],
                    delta = row[ValuationJobsTable.delta],
                    gamma = row[ValuationJobsTable.gamma],
                    vega = row[ValuationJobsTable.vega],
                    theta = row[ValuationJobsTable.theta],
                    rho = row[ValuationJobsTable.rho],
                    error = row[ValuationJobsTable.error],
                    triggeredBy = row[ValuationJobsTable.triggeredBy],
                    runLabel = row[ValuationJobsTable.runLabel]?.let { RunLabel.valueOf(it) },
                    promotedAt = row[ValuationJobsTable.promotedAt]?.toInstant(),
                    promotedBy = row[ValuationJobsTable.promotedBy],
                    marketDataSnapshotId = row[ValuationJobsTable.marketDataSnapshotId],
                    manifestId = row[ValuationJobsTable.manifestId],
                )
            }
    }

    override suspend fun promoteToOfficialEod(
        jobId: UUID,
        promotedBy: String,
        promotedAt: Instant,
    ): ValuationJob = newSuspendedTransaction(db = db) {
        val job = ValuationJobsTable
            .selectAll()
            .where { ValuationJobsTable.jobId eq jobId }
            .firstOrNull()
            ?.toValuationJob()
            ?: throw EodPromotionException.JobNotFound(jobId)

        try {
            OfficialEodDesignationsTable.insert {
                it[OfficialEodDesignationsTable.bookId] = job.bookId
                it[OfficialEodDesignationsTable.valuationDate] = job.valuationDate.toKotlinLocalDate()
                it[OfficialEodDesignationsTable.jobId] = jobId
                it[OfficialEodDesignationsTable.promotedAt] = OffsetDateTime.ofInstant(promotedAt, ZoneOffset.UTC)
                it[OfficialEodDesignationsTable.promotedBy] = promotedBy
            }
        } catch (e: org.jetbrains.exposed.exceptions.ExposedSQLException) {
            if (e.message?.contains("duplicate key") == true || e.message?.contains("unique constraint") == true) {
                throw EodPromotionException.ConflictingOfficialEod(job.bookId, job.valuationDate.toString())
            }
            throw e
        }

        ValuationJobsTable.update({ ValuationJobsTable.jobId eq jobId }) {
            it[runLabel] = RunLabel.OFFICIAL_EOD.name
            it[ValuationJobsTable.promotedAt] = OffsetDateTime.ofInstant(promotedAt, ZoneOffset.UTC)
            it[ValuationJobsTable.promotedBy] = promotedBy
        }

        job.copy(
            runLabel = RunLabel.OFFICIAL_EOD,
            promotedAt = promotedAt,
            promotedBy = promotedBy,
        )
    }

    override suspend fun demoteOfficialEod(jobId: UUID): ValuationJob = newSuspendedTransaction(db = db) {
        val job = ValuationJobsTable
            .selectAll()
            .where { ValuationJobsTable.jobId eq jobId }
            .firstOrNull()
            ?.toValuationJob()
            ?: throw EodPromotionException.JobNotFound(jobId)

        OfficialEodDesignationsTable.deleteWhere {
            (OfficialEodDesignationsTable.bookId eq job.bookId) and
                (OfficialEodDesignationsTable.valuationDate eq job.valuationDate.toKotlinLocalDate())
        }

        ValuationJobsTable.update({ ValuationJobsTable.jobId eq jobId }) {
            it[runLabel] = null
            it[promotedAt] = null
            it[promotedBy] = null
        }

        job.copy(runLabel = null, promotedAt = null, promotedBy = null)
    }

    override suspend fun supersedeOfficialEod(jobId: UUID): ValuationJob = newSuspendedTransaction(db = db) {
        val job = ValuationJobsTable
            .selectAll()
            .where { ValuationJobsTable.jobId eq jobId }
            .firstOrNull()
            ?.toValuationJob()
            ?: throw EodPromotionException.JobNotFound(jobId)

        OfficialEodDesignationsTable.deleteWhere {
            (OfficialEodDesignationsTable.bookId eq job.bookId) and
                (OfficialEodDesignationsTable.valuationDate eq job.valuationDate.toKotlinLocalDate())
        }

        ValuationJobsTable.update({ ValuationJobsTable.jobId eq jobId }) {
            it[runLabel] = RunLabel.SUPERSEDED_EOD.name
            it[promotedAt] = null
            it[promotedBy] = null
        }

        job.copy(runLabel = RunLabel.SUPERSEDED_EOD, promotedAt = null, promotedBy = null)
    }

    private fun JobPhase.toJson(): JobPhaseJson = JobPhaseJson(
        name = name.name,
        status = status.name,
        startedAt = startedAt.toString(),
        completedAt = completedAt?.toString(),
        durationMs = durationMs,
        details = details.mapValues { (_, v) -> v?.toString() ?: "" },
        error = error,
    )

    private fun parsePhaseName(raw: String): JobPhaseName = when (raw) {
        "CALCULATE_VAR" -> JobPhaseName.VALUATION
        else -> JobPhaseName.valueOf(raw)
    }

    private fun JobPhaseJson.toDomain(): JobPhase = JobPhase(
        name = parsePhaseName(name),
        status = RunStatus.valueOf(status),
        startedAt = Instant.parse(startedAt),
        completedAt = completedAt?.let { Instant.parse(it) },
        durationMs = durationMs,
        details = details,
        error = error,
    )

    private fun PositionRisk.toJson(): PositionRiskJson = PositionRiskJson(
        instrumentId = instrumentId.value,
        assetClass = assetClass.name,
        marketValue = marketValue.toPlainString(),
        delta = delta,
        gamma = gamma,
        vega = vega,
        varContribution = varContribution.toPlainString(),
        esContribution = esContribution.toPlainString(),
        percentageOfTotal = percentageOfTotal.toPlainString(),
    )

    private fun PositionRiskJson.toDomain(): PositionRisk = PositionRisk(
        instrumentId = InstrumentId(instrumentId),
        assetClass = AssetClass.valueOf(assetClass),
        marketValue = BigDecimal(marketValue),
        delta = delta,
        gamma = gamma,
        vega = vega,
        varContribution = BigDecimal(varContribution),
        esContribution = BigDecimal(esContribution),
        percentageOfTotal = BigDecimal(percentageOfTotal),
    )

    private fun ComponentBreakdown.toJson(): ComponentBreakdownJson = ComponentBreakdownJson(
        assetClass = assetClass.name,
        varContribution = varContribution,
        percentageOfTotal = percentageOfTotal,
    )

    private fun ComponentBreakdownJson.toDomain(): ComponentBreakdown = ComponentBreakdown(
        assetClass = AssetClass.valueOf(assetClass),
        varContribution = varContribution,
        percentageOfTotal = percentageOfTotal,
    )

    private fun GreekValues.toJson(): AssetClassGreeksJson = AssetClassGreeksJson(
        assetClass = assetClass.name,
        delta = delta,
        gamma = gamma,
        vega = vega,
    )

    private fun AssetClassGreeksJson.toDomain(): GreekValues = GreekValues(
        assetClass = AssetClass.valueOf(assetClass),
        delta = delta,
        gamma = gamma,
        vega = vega,
    )

    private fun ResultRow.toValuationJob(): ValuationJob = ValuationJob(
        jobId = this[ValuationJobsTable.jobId],
        bookId = this[ValuationJobsTable.bookId],
        triggerType = TriggerType.valueOf(this[ValuationJobsTable.triggerType]),
        status = RunStatus.valueOf(this[ValuationJobsTable.status]),
        startedAt = this[ValuationJobsTable.startedAt].toInstant(),
        valuationDate = this[ValuationJobsTable.valuationDate].toJavaLocalDate(),
        completedAt = this[ValuationJobsTable.completedAt]?.toInstant(),
        durationMs = this[ValuationJobsTable.durationMs],
        calculationType = this[ValuationJobsTable.calculationType],
        confidenceLevel = this[ValuationJobsTable.confidenceLevel],
        varValue = this[ValuationJobsTable.varValue],
        expectedShortfall = this[ValuationJobsTable.expectedShortfall],
        pvValue = this[ValuationJobsTable.pvValue],
        delta = this[ValuationJobsTable.delta],
        gamma = this[ValuationJobsTable.gamma],
        vega = this[ValuationJobsTable.vega],
        theta = this[ValuationJobsTable.theta],
        rho = this[ValuationJobsTable.rho],
        positionRiskSnapshot = this[ValuationJobsTable.positionRisk]?.map { it.toDomain() } ?: emptyList(),
        componentBreakdownSnapshot = this[ValuationJobsTable.componentBreakdown]?.map { it.toDomain() } ?: emptyList(),
        computedOutputsSnapshot = this[ValuationJobsTable.computedOutputs]?.map { ValuationOutput.valueOf(it) }?.toSet() ?: emptySet(),
        assetClassGreeksSnapshot = this[ValuationJobsTable.assetClassGreeks]?.map { it.toDomain() } ?: emptyList(),
        phases = this[ValuationJobsTable.phases].map { it.toDomain() },
        currentPhase = this[ValuationJobsTable.currentPhase]?.let { parsePhaseName(it) },
        error = this[ValuationJobsTable.error],
        triggeredBy = this[ValuationJobsTable.triggeredBy],
        runLabel = this[ValuationJobsTable.runLabel]?.let { RunLabel.valueOf(it) },
        promotedAt = this[ValuationJobsTable.promotedAt]?.toInstant(),
        promotedBy = this[ValuationJobsTable.promotedBy],
        marketDataSnapshotId = this[ValuationJobsTable.marketDataSnapshotId],
        manifestId = this[ValuationJobsTable.manifestId],
        timeHorizonDays = this[ValuationJobsTable.timeHorizonDays],
        requestedCalculationType = this[ValuationJobsTable.requestedCalculationType],
        requestedConfidenceLevel = this[ValuationJobsTable.requestedConfidenceLevel],
        requestedTimeHorizonDays = this[ValuationJobsTable.requestedTimeHorizonDays],
        correlationId = this[ValuationJobsTable.correlationId],
    )

    override suspend fun findChartData(
        bookId: String,
        from: Instant,
        to: Instant,
        bucketInterval: String,
    ): List<ChartBucketRow> = newSuspendedTransaction(db = db) {
        val fromTs = OffsetDateTime.ofInstant(from, ZoneOffset.UTC)
        val toTs = OffsetDateTime.ofInstant(to, ZoneOffset.UTC)

        val sql = """
            WITH counts AS (
                SELECT time_bucket(?::interval, started_at) AS bucket,
                       COUNT(*)::int AS job_count,
                       COUNT(*) FILTER (WHERE status = 'COMPLETED')::int AS completed_count,
                       COUNT(*) FILTER (WHERE status = 'FAILED')::int AS failed_count,
                       COUNT(*) FILTER (WHERE status = 'RUNNING')::int AS running_count
                FROM valuation_jobs
                WHERE book_id = ?
                  AND started_at >= ?
                  AND started_at < ?
                GROUP BY bucket
            ),
            latest_completed AS (
                SELECT time_bucket(?::interval, started_at) AS bucket,
                       var_value, expected_shortfall, confidence_level,
                       delta, gamma, vega, theta, rho, pv_value,
                       ROW_NUMBER() OVER (
                           PARTITION BY time_bucket(?::interval, started_at)
                           ORDER BY started_at DESC
                       ) AS rn
                FROM valuation_jobs
                WHERE book_id = ?
                  AND started_at >= ?
                  AND started_at < ?
                  AND status = 'COMPLETED'
            )
            SELECT c.bucket,
                   lc.var_value, lc.expected_shortfall, lc.confidence_level,
                   lc.delta, lc.gamma, lc.vega, lc.theta, lc.rho, lc.pv_value,
                   c.job_count, c.completed_count, c.failed_count, c.running_count
            FROM counts c
            LEFT JOIN latest_completed lc ON lc.bucket = c.bucket AND lc.rn = 1
            ORDER BY c.bucket ASC
        """.trimIndent()

        val rows = mutableListOf<ChartBucketRow>()
        @Suppress("UNCHECKED_CAST")
        val conn = this.connection.connection as java.sql.Connection
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, bucketInterval)
            stmt.setString(2, bookId)
            stmt.setObject(3, fromTs)
            stmt.setObject(4, toTs)
            stmt.setString(5, bucketInterval)
            stmt.setString(6, bucketInterval)
            stmt.setString(7, bookId)
            stmt.setObject(8, fromTs)
            stmt.setObject(9, toTs)

            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    rows.add(mapChartBucketRow(rs))
                }
            }
        }

        rows
    }

    private fun mapChartBucketRow(rs: ResultSet): ChartBucketRow {
        val bucket = rs.getObject("bucket", OffsetDateTime::class.java).toInstant()
        return ChartBucketRow(
            bucket = bucket,
            varValue = rs.getObject("var_value") as? Double,
            expectedShortfall = rs.getObject("expected_shortfall") as? Double,
            confidenceLevel = rs.getString("confidence_level"),
            delta = rs.getObject("delta") as? Double,
            gamma = rs.getObject("gamma") as? Double,
            vega = rs.getObject("vega") as? Double,
            theta = rs.getObject("theta") as? Double,
            rho = rs.getObject("rho") as? Double,
            pvValue = rs.getObject("pv_value") as? Double,
            jobCount = rs.getInt("job_count"),
            completedCount = rs.getInt("completed_count"),
            failedCount = rs.getInt("failed_count"),
            runningCount = rs.getInt("running_count"),
        )
    }

    override suspend fun resetOrphanedRunningJobs(): Int = newSuspendedTransaction(db = db) {
        val cutoff = OffsetDateTime.ofInstant(Instant.now().minus(Duration.ofMinutes(10)), ZoneOffset.UTC)
        ValuationJobsTable.update({
            (ValuationJobsTable.status eq "RUNNING") and
                (ValuationJobsTable.startedAt less cutoff)
        }) {
            it[status] = "FAILED"
            it[completedAt] = OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC)
            it[error] = "Orphaned RUNNING job reset on startup"
        }
    }

    override suspend fun findByTriggeredBy(triggeredBy: String, limit: Int): List<ValuationJob> =
        newSuspendedTransaction(db = db) {
            ValuationJobsTable
                .selectAll()
                .where { ValuationJobsTable.triggeredBy eq triggeredBy }
                .limit(limit)
                .map { it.toValuationJob() }
        }

    override suspend fun deleteByTriggeredBy(triggeredBy: String): Int =
        newSuspendedTransaction(db = db) {
            ValuationJobsTable.deleteWhere { ValuationJobsTable.triggeredBy eq triggeredBy }
        }

    override suspend fun deleteOfficialEodDesignationsByPromotedBy(promotedBy: String): Int =
        newSuspendedTransaction(db = db) {
            OfficialEodDesignationsTable.deleteWhere {
                OfficialEodDesignationsTable.promotedBy eq promotedBy
            }
        }

    companion object {
        fun bucketInterval(from: Instant, to: Instant): String {
            val durationMs = Duration.between(from, to).toMillis()
            return when {
                durationMs <= 3_600_000L -> "1 minute"
                durationMs <= 21_600_000L -> "5 minutes"
                durationMs <= 86_400_000L -> "15 minutes"
                durationMs <= 604_800_000L -> "1 hour"
                else -> "4 hours"
            }
        }

        fun bucketSizeMs(from: Instant, to: Instant): Long {
            val durationMs = Duration.between(from, to).toMillis()
            return when {
                durationMs <= 3_600_000L -> 60_000L
                durationMs <= 21_600_000L -> 300_000L
                durationMs <= 86_400_000L -> 900_000L
                durationMs <= 604_800_000L -> 3_600_000L
                else -> 14_400_000L
            }
        }
    }
}

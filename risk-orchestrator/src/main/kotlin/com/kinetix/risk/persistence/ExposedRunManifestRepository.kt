package com.kinetix.risk.persistence

import com.kinetix.risk.model.*
import com.kinetix.risk.service.RunManifestRepository
import java.time.Instant
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class ExposedRunManifestRepository(private val db: Database? = null) : RunManifestRepository {

    override suspend fun save(manifest: RunManifest): Unit = newSuspendedTransaction(db = db) {
        RunManifestsTable.insert {
            it[manifestId] = manifest.manifestId
            it[jobId] = manifest.jobId
            it[bookId] = manifest.bookId
            it[valuationDate] = manifest.valuationDate.toKotlinLocalDate()
            it[capturedAt] = OffsetDateTime.ofInstant(manifest.capturedAt, ZoneOffset.UTC)
            it[modelVersion] = manifest.modelVersion
            it[calculationType] = manifest.calculationType
            it[confidenceLevel] = manifest.confidenceLevel
            it[timeHorizonDays] = manifest.timeHorizonDays
            it[numSimulations] = manifest.numSimulations
            it[monteCarloSeed] = manifest.monteCarloSeed
            it[positionCount] = manifest.positionCount
            it[positionDigest] = manifest.positionDigest
            it[marketDataDigest] = manifest.marketDataDigest
            it[inputDigest] = manifest.inputDigest
            it[status] = manifest.status.name
        }
    }

    override suspend fun savePositionSnapshot(
        manifestId: UUID,
        entries: List<PositionSnapshotEntry>,
    ): Unit = newSuspendedTransaction(db = db) {
        RunPositionSnapshotsTable.batchInsert(entries) { entry ->
            this[RunPositionSnapshotsTable.manifestId] = manifestId
            this[RunPositionSnapshotsTable.instrumentId] = entry.instrumentId
            this[RunPositionSnapshotsTable.assetClass] = entry.assetClass
            this[RunPositionSnapshotsTable.quantity] = entry.quantity
            this[RunPositionSnapshotsTable.avgCostAmount] = entry.averageCostAmount
            this[RunPositionSnapshotsTable.marketPriceAmount] = entry.marketPriceAmount
            this[RunPositionSnapshotsTable.currency] = entry.currency
            this[RunPositionSnapshotsTable.marketValueAmount] = entry.marketValueAmount
            this[RunPositionSnapshotsTable.unrealizedPnlAmount] = entry.unrealizedPnlAmount
            this[RunPositionSnapshotsTable.instrumentType] = entry.instrumentType
        }
    }

    override suspend fun saveMarketDataRefs(
        manifestId: UUID,
        refs: List<MarketDataRef>,
    ): Unit = newSuspendedTransaction(db = db) {
        val deduplicated = refs.distinctBy { it.dataType to it.instrumentId }
        RunManifestMarketDataTable.batchInsert(deduplicated) { ref ->
            this[RunManifestMarketDataTable.manifestId] = manifestId
            this[RunManifestMarketDataTable.contentHash] = ref.contentHash
            this[RunManifestMarketDataTable.dataType] = ref.dataType
            this[RunManifestMarketDataTable.instrumentId] = ref.instrumentId
            this[RunManifestMarketDataTable.assetClass] = ref.assetClass
            this[RunManifestMarketDataTable.status] = ref.status.name
            this[RunManifestMarketDataTable.sourceService] = ref.sourceService
            this[RunManifestMarketDataTable.sourcedAt] = OffsetDateTime.ofInstant(ref.sourcedAt, ZoneOffset.UTC)
        }
    }

    override suspend fun findByJobId(jobId: UUID): RunManifest? = newSuspendedTransaction(db = db) {
        RunManifestsTable
            .selectAll()
            .where { RunManifestsTable.jobId eq jobId }
            .firstOrNull()
            ?.toRunManifest()
    }

    override suspend fun findByManifestId(manifestId: UUID): RunManifest? = newSuspendedTransaction(db = db) {
        RunManifestsTable
            .selectAll()
            .where { RunManifestsTable.manifestId eq manifestId }
            .firstOrNull()
            ?.toRunManifest()
    }

    override suspend fun findPositionSnapshot(manifestId: UUID): List<PositionSnapshotEntry> =
        newSuspendedTransaction(db = db) {
            RunPositionSnapshotsTable
                .selectAll()
                .where { RunPositionSnapshotsTable.manifestId eq manifestId }
                .map { row ->
                    PositionSnapshotEntry(
                        instrumentId = row[RunPositionSnapshotsTable.instrumentId],
                        assetClass = row[RunPositionSnapshotsTable.assetClass],
                        quantity = row[RunPositionSnapshotsTable.quantity],
                        averageCostAmount = row[RunPositionSnapshotsTable.avgCostAmount],
                        marketPriceAmount = row[RunPositionSnapshotsTable.marketPriceAmount],
                        currency = row[RunPositionSnapshotsTable.currency],
                        marketValueAmount = row[RunPositionSnapshotsTable.marketValueAmount],
                        unrealizedPnlAmount = row[RunPositionSnapshotsTable.unrealizedPnlAmount],
                        instrumentType = row[RunPositionSnapshotsTable.instrumentType],
                    )
                }
        }

    override suspend fun findMarketDataRefs(manifestId: UUID): List<MarketDataRef> =
        newSuspendedTransaction(db = db) {
            RunManifestMarketDataTable
                .selectAll()
                .where { RunManifestMarketDataTable.manifestId eq manifestId }
                .map { row ->
                    MarketDataRef(
                        dataType = row[RunManifestMarketDataTable.dataType],
                        instrumentId = row[RunManifestMarketDataTable.instrumentId],
                        assetClass = row[RunManifestMarketDataTable.assetClass],
                        contentHash = row[RunManifestMarketDataTable.contentHash],
                        status = MarketDataSnapshotStatus.valueOf(row[RunManifestMarketDataTable.status]),
                        sourceService = row[RunManifestMarketDataTable.sourceService],
                        sourcedAt = row[RunManifestMarketDataTable.sourcedAt].toInstant(),
                    )
                }
        }

    override suspend fun finaliseManifest(
        manifestId: UUID,
        modelVersion: String,
        varValue: Double?,
        expectedShortfall: Double?,
        outputDigest: String?,
        inputDigest: String,
        status: ManifestStatus,
    ): Unit = newSuspendedTransaction(db = db) {
        RunManifestsTable.update({ RunManifestsTable.manifestId eq manifestId }) {
            it[RunManifestsTable.modelVersion] = modelVersion
            it[RunManifestsTable.varValue] = varValue
            it[RunManifestsTable.expectedShortfall] = expectedShortfall
            it[RunManifestsTable.outputDigest] = outputDigest
            it[RunManifestsTable.inputDigest] = inputDigest
            it[RunManifestsTable.status] = status.name
        }
    }

    private fun ResultRow.toRunManifest(): RunManifest = RunManifest(
        manifestId = this[RunManifestsTable.manifestId],
        jobId = this[RunManifestsTable.jobId],
        bookId = this[RunManifestsTable.bookId],
        valuationDate = this[RunManifestsTable.valuationDate].toJavaLocalDate(),
        capturedAt = this[RunManifestsTable.capturedAt].toInstant(),
        modelVersion = this[RunManifestsTable.modelVersion],
        calculationType = this[RunManifestsTable.calculationType],
        confidenceLevel = this[RunManifestsTable.confidenceLevel],
        timeHorizonDays = this[RunManifestsTable.timeHorizonDays],
        numSimulations = this[RunManifestsTable.numSimulations],
        monteCarloSeed = this[RunManifestsTable.monteCarloSeed],
        positionCount = this[RunManifestsTable.positionCount],
        positionDigest = this[RunManifestsTable.positionDigest],
        marketDataDigest = this[RunManifestsTable.marketDataDigest],
        inputDigest = this[RunManifestsTable.inputDigest],
        status = ManifestStatus.valueOf(this[RunManifestsTable.status]),
        varValue = this[RunManifestsTable.varValue],
        expectedShortfall = this[RunManifestsTable.expectedShortfall],
        outputDigest = this[RunManifestsTable.outputDigest],
    )
}

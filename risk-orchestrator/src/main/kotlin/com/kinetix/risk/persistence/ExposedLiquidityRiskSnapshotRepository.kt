package com.kinetix.risk.persistence

import com.kinetix.common.model.LiquidityRiskResult
import com.kinetix.common.model.LiquidityTier
import com.kinetix.common.model.PositionLiquidityRisk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ExposedLiquidityRiskSnapshotRepository(
    private val db: Database? = null,
) : LiquidityRiskSnapshotRepository {

    override suspend fun save(result: LiquidityRiskResult): Unit = newSuspendedTransaction(db = db) {
        val positionsJson = buildJsonArray {
            result.positionRisks.forEach { pos ->
                add(buildJsonObject {
                    put("instrumentId", pos.instrumentId)
                    put("assetClass", pos.assetClass)
                    put("marketValue", pos.marketValue)
                    put("tier", pos.tier.name)
                    put("horizonDays", pos.horizonDays)
                    put("adv", pos.adv)
                    put("advPct", pos.advPct)
                    put("advMissing", pos.advMissing)
                    put("advStale", pos.advStale)
                    put("lvarContribution", pos.lvarContribution)
                    put("stressedLiquidationValue", pos.stressedLiquidationValue)
                    put("concentrationStatus", pos.concentrationStatus)
                })
            }
        }

        val calculatedAt = try {
            Instant.parse(result.calculatedAt).atOffset(ZoneOffset.UTC)
        } catch (_: Exception) {
            OffsetDateTime.now(ZoneOffset.UTC)
        }

        val advAsOf = result.advDataAsOf?.let {
            try { Instant.parse(it).atOffset(ZoneOffset.UTC) } catch (_: Exception) { null }
        }

        LiquidityRiskSnapshotsTable.insert {
            it[bookId] = result.bookId
            it[this.calculatedAt] = calculatedAt
            it[portfolioLvar] = BigDecimal.valueOf(result.portfolioLvar)
            it[dataCompleteness] = BigDecimal.valueOf(result.dataCompleteness)
            it[portfolioConcentrationStatus] = result.portfolioConcentrationStatus
            it[positionRisksJson] = positionsJson
            it[var1day] = BigDecimal.valueOf(result.var1day)
            it[lvarRatio] = BigDecimal.valueOf(result.lvarRatio)
            it[weightedAvgHorizon] = BigDecimal.valueOf(result.weightedAvgHorizon)
            it[maxHorizon] = BigDecimal.valueOf(result.maxHorizon)
            it[concentrationCount] = result.concentrationCount
            it[advDataAsOf] = advAsOf
        }
    }

    override suspend fun findLatestByBookId(bookId: String): LiquidityRiskResult? =
        newSuspendedTransaction(db = db) {
            LiquidityRiskSnapshotsTable
                .selectAll()
                .where { LiquidityRiskSnapshotsTable.bookId eq bookId }
                .orderBy(LiquidityRiskSnapshotsTable.calculatedAt, SortOrder.DESC)
                .limit(1)
                .singleOrNull()
                ?.let { row ->
                    row.toLiquidityRiskResult()
                }
        }

    override suspend fun findAllByBookId(bookId: String, limit: Int): List<LiquidityRiskResult> =
        newSuspendedTransaction(db = db) {
            LiquidityRiskSnapshotsTable
                .selectAll()
                .where { LiquidityRiskSnapshotsTable.bookId eq bookId }
                .orderBy(LiquidityRiskSnapshotsTable.calculatedAt, SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    row.toLiquidityRiskResult()
                }
        }

    private fun org.jetbrains.exposed.sql.ResultRow.toLiquidityRiskResult() = LiquidityRiskResult(
        bookId = this[LiquidityRiskSnapshotsTable.bookId],
        var1day = this[LiquidityRiskSnapshotsTable.var1day].toDouble(),
        portfolioLvar = this[LiquidityRiskSnapshotsTable.portfolioLvar].toDouble(),
        lvarRatio = this[LiquidityRiskSnapshotsTable.lvarRatio].toDouble(),
        weightedAvgHorizon = this[LiquidityRiskSnapshotsTable.weightedAvgHorizon].toDouble(),
        maxHorizon = this[LiquidityRiskSnapshotsTable.maxHorizon].toDouble(),
        concentrationCount = this[LiquidityRiskSnapshotsTable.concentrationCount],
        dataCompleteness = this[LiquidityRiskSnapshotsTable.dataCompleteness].toDouble(),
        advDataAsOf = this[LiquidityRiskSnapshotsTable.advDataAsOf]?.toInstant()?.toString(),
        portfolioConcentrationStatus = this[LiquidityRiskSnapshotsTable.portfolioConcentrationStatus],
        positionRisks = parsePositionRisks(this[LiquidityRiskSnapshotsTable.positionRisksJson]),
        calculatedAt = this[LiquidityRiskSnapshotsTable.calculatedAt].toInstant().toString(),
    )

    // JsonElement.jsonPrimitive.double throws on JsonNull (its content is the
    // string "null"). The save path serialises null Double fields as JsonNull,
    // so reads must short-circuit before calling .double.
    private fun JsonElement?.nullableDouble(): Double? =
        if (this == null || this is JsonNull) null else this.jsonPrimitive.double

    private fun parsePositionRisks(json: kotlinx.serialization.json.JsonElement): List<PositionLiquidityRisk> {
        val arr = json as? JsonArray ?: return emptyList()
        return arr.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            PositionLiquidityRisk(
                instrumentId = obj["instrumentId"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                assetClass = obj["assetClass"]?.jsonPrimitive?.content ?: "EQUITY",
                marketValue = obj["marketValue"]?.jsonPrimitive?.double ?: 0.0,
                tier = LiquidityTier.valueOf(obj["tier"]?.jsonPrimitive?.content ?: "ILLIQUID"),
                horizonDays = obj["horizonDays"]?.jsonPrimitive?.int ?: 10,
                adv = obj["adv"].nullableDouble(),
                advPct = obj["advPct"].nullableDouble(),
                advMissing = obj["advMissing"]?.jsonPrimitive?.boolean ?: true,
                advStale = obj["advStale"]?.jsonPrimitive?.boolean ?: false,
                lvarContribution = obj["lvarContribution"]?.jsonPrimitive?.double ?: 0.0,
                stressedLiquidationValue = obj["stressedLiquidationValue"]?.jsonPrimitive?.double ?: 0.0,
                concentrationStatus = obj["concentrationStatus"]?.jsonPrimitive?.content ?: "OK",
            )
        }
    }
}

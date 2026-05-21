package com.kinetix.risk.persistence

import com.kinetix.risk.model.FactorContribution
import com.kinetix.risk.model.FactorDecompositionSnapshot
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ExposedFactorDecompositionRepository(
    private val db: Database? = null,
) : FactorDecompositionRepository {

    override suspend fun save(snapshot: FactorDecompositionSnapshot): Unit =
        newSuspendedTransaction(db = db) {
            val factorsJson = buildJsonArray {
                snapshot.factors.forEach { factor ->
                    add(buildJsonObject {
                        put("factorType", factor.factorType)
                        put("factorExposure", factor.factorExposure)
                        put("varContribution", factor.varContribution)
                        put("pnlAttribution", factor.pnlAttribution)
                        put("pctOfTotal", factor.pctOfTotal)
                        put("loading", factor.loading)
                        put("loadingMethod", factor.loadingMethod)
                    })
                }
            }

            FactorDecompositionSnapshotsTable.insert {
                it[bookId] = snapshot.bookId
                it[calculatedAt] = snapshot.calculatedAt.atOffset(ZoneOffset.UTC)
                it[totalVar] = BigDecimal.valueOf(snapshot.totalVar)
                it[systematicVar] = BigDecimal.valueOf(snapshot.systematicVar)
                it[idiosyncraticVar] = BigDecimal.valueOf(snapshot.idiosyncraticVar)
                it[rSquared] = BigDecimal.valueOf(snapshot.rSquared)
                it[concentrationWarning] = snapshot.concentrationWarning
                it[this.factorsJson] = factorsJson
            }
        }

    override suspend fun findLatestByBookId(bookId: String): FactorDecompositionSnapshot? =
        newSuspendedTransaction(db = db) {
            FactorDecompositionSnapshotsTable
                .selectAll()
                .where { FactorDecompositionSnapshotsTable.bookId eq bookId }
                .orderBy(FactorDecompositionSnapshotsTable.calculatedAt, SortOrder.DESC)
                .limit(1)
                .singleOrNull()
                ?.let { row ->
                    FactorDecompositionSnapshot(
                        bookId = row[FactorDecompositionSnapshotsTable.bookId],
                        calculatedAt = row[FactorDecompositionSnapshotsTable.calculatedAt].toInstant(),
                        totalVar = row[FactorDecompositionSnapshotsTable.totalVar].toDouble(),
                        systematicVar = row[FactorDecompositionSnapshotsTable.systematicVar].toDouble(),
                        idiosyncraticVar = row[FactorDecompositionSnapshotsTable.idiosyncraticVar].toDouble(),
                        rSquared = row[FactorDecompositionSnapshotsTable.rSquared].toDouble(),
                        concentrationWarning = row[FactorDecompositionSnapshotsTable.concentrationWarning],
                        factors = parseFactors(row[FactorDecompositionSnapshotsTable.factorsJson]),
                    )
                }
        }

    override suspend fun findAllByBookId(bookId: String, limit: Int): List<FactorDecompositionSnapshot> =
        newSuspendedTransaction(db = db) {
            FactorDecompositionSnapshotsTable
                .selectAll()
                .where { FactorDecompositionSnapshotsTable.bookId eq bookId }
                .orderBy(FactorDecompositionSnapshotsTable.calculatedAt, SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    FactorDecompositionSnapshot(
                        bookId = row[FactorDecompositionSnapshotsTable.bookId],
                        calculatedAt = row[FactorDecompositionSnapshotsTable.calculatedAt].toInstant(),
                        totalVar = row[FactorDecompositionSnapshotsTable.totalVar].toDouble(),
                        systematicVar = row[FactorDecompositionSnapshotsTable.systematicVar].toDouble(),
                        idiosyncraticVar = row[FactorDecompositionSnapshotsTable.idiosyncraticVar].toDouble(),
                        rSquared = row[FactorDecompositionSnapshotsTable.rSquared].toDouble(),
                        concentrationWarning = row[FactorDecompositionSnapshotsTable.concentrationWarning],
                        factors = parseFactors(row[FactorDecompositionSnapshotsTable.factorsJson]),
                    )
                }
        }

    private fun parseFactors(json: kotlinx.serialization.json.JsonElement): List<FactorContribution> {
        val arr = json as? JsonArray ?: return emptyList()
        return arr.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            FactorContribution(
                factorType = obj["factorType"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                factorExposure = obj["factorExposure"]?.jsonPrimitive?.double ?: 0.0,
                varContribution = obj["varContribution"]?.jsonPrimitive?.double ?: 0.0,
                pnlAttribution = obj["pnlAttribution"]?.jsonPrimitive?.double ?: 0.0,
                pctOfTotal = obj["pctOfTotal"]?.jsonPrimitive?.double ?: 0.0,
                loading = obj["loading"]?.jsonPrimitive?.double ?: 0.0,
                loadingMethod = obj["loadingMethod"]?.jsonPrimitive?.content ?: "ANALYTICAL",
            )
        }
    }

    private fun Instant.atOffset(zone: ZoneOffset): OffsetDateTime =
        OffsetDateTime.ofInstant(this, zone)
}

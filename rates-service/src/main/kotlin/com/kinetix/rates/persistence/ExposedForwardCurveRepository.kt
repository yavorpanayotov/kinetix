package com.kinetix.rates.persistence

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.CurvePoint
import com.kinetix.common.model.ForwardCurve
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.RateSource
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ExposedForwardCurveRepository(private val db: Database? = null) : ForwardCurveRepository {

    override suspend fun save(curve: ForwardCurve): Unit = newSuspendedTransaction(db = db) {
        val asOfOffset = curve.asOfDate.atOffset(ZoneOffset.UTC)
        ForwardCurveTable.insert {
            it[instrumentId] = curve.instrumentId.value
            it[asOfDate] = asOfOffset
            it[assetClass] = curve.assetClass.name
            it[dataSource] = curve.source.name
            it[createdAt] = OffsetDateTime.now(ZoneOffset.UTC)
        }
        for (point in curve.points) {
            ForwardCurvePointTable.insert {
                it[instrumentId] = curve.instrumentId.value
                it[asOfDate] = asOfOffset
                it[tenor] = point.tenor
                it[value] = point.value.toBigDecimal()
            }
        }
    }

    override suspend fun findLatest(instrumentId: InstrumentId): ForwardCurve? =
        newSuspendedTransaction(db = db) {
            val latestHeader = ForwardCurveTable
                .selectAll()
                .where { ForwardCurveTable.instrumentId eq instrumentId.value }
                .orderBy(ForwardCurveTable.asOfDate, SortOrder.DESC)
                .limit(1)
                .singleOrNull() ?: return@newSuspendedTransaction null

            val asOfDate = latestHeader[ForwardCurveTable.asOfDate]
            val pointRows = ForwardCurvePointTable
                .selectAll()
                .where {
                    (ForwardCurvePointTable.instrumentId eq instrumentId.value) and
                        (ForwardCurvePointTable.asOfDate eq asOfDate)
                }
                .toList()

            toForwardCurve(latestHeader, pointRows)
        }

    override suspend fun findByTimeRange(
        instrumentId: InstrumentId,
        from: Instant,
        to: Instant,
    ): List<ForwardCurve> = newSuspendedTransaction(db = db) {
        val fromOffset = from.atOffset(ZoneOffset.UTC)
        val toOffset = to.atOffset(ZoneOffset.UTC)

        val headers = ForwardCurveTable
            .selectAll()
            .where {
                (ForwardCurveTable.instrumentId eq instrumentId.value) and
                    (ForwardCurveTable.asOfDate greaterEq fromOffset) and
                    (ForwardCurveTable.asOfDate lessEq toOffset)
            }
            .orderBy(ForwardCurveTable.asOfDate, SortOrder.ASC)
            .toList()

        headers.map { header ->
            val pointRows = ForwardCurvePointTable
                .selectAll()
                .where {
                    (ForwardCurvePointTable.instrumentId eq header[ForwardCurveTable.instrumentId]) and
                        (ForwardCurvePointTable.asOfDate eq header[ForwardCurveTable.asOfDate])
                }
                .toList()
            toForwardCurve(header, pointRows)
        }
    }

    private fun toForwardCurve(header: ResultRow, pointRows: List<ResultRow>): ForwardCurve {
        val points = pointRows.map { row ->
            CurvePoint(
                tenor = row[ForwardCurvePointTable.tenor],
                value = row[ForwardCurvePointTable.value].toDouble(),
            )
        }
        return ForwardCurve(
            instrumentId = InstrumentId(header[ForwardCurveTable.instrumentId]),
            assetClass = AssetClass.valueOf(header[ForwardCurveTable.assetClass]),
            points = points,
            asOfDate = header[ForwardCurveTable.asOfDate].toInstant(),
            source = RateSource.valueOf(header[ForwardCurveTable.dataSource]),
        )
    }
}

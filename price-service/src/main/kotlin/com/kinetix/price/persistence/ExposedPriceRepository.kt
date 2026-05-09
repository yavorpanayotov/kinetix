package com.kinetix.price.persistence

import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.PricePoint
import com.kinetix.common.model.PriceSource
import com.kinetix.common.model.Money
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Currency

class ExposedPriceRepository(private val db: Database? = null) : PriceRepository {

    override suspend fun save(point: PricePoint): Unit = newSuspendedTransaction(db = db) {
        PriceTable.insert {
            it[instrumentId] = point.instrumentId.value
            it[priceAmount] = point.price.amount
            it[priceCurrency] = point.price.currency.currencyCode
            it[timestamp] = point.timestamp.atOffset(ZoneOffset.UTC)
            it[dataSource] = point.source.name
            it[createdAt] = OffsetDateTime.now(ZoneOffset.UTC)
        }
    }

    override suspend fun findLatest(instrumentId: InstrumentId): PricePoint? =
        newSuspendedTransaction(db = db) {
            PriceTable
                .selectAll()
                .where { PriceTable.instrumentId eq instrumentId.value }
                .orderBy(PriceTable.timestamp, SortOrder.DESC)
                .limit(1)
                .singleOrNull()
                ?.toPricePoint()
        }

    override suspend fun findByInstrumentId(
        instrumentId: InstrumentId,
        from: Instant,
        to: Instant,
    ): List<PricePoint> = newSuspendedTransaction(db = db) {
        val fromOffset = from.atOffset(ZoneOffset.UTC)
        val toOffset = to.atOffset(ZoneOffset.UTC)
        PriceTable
            .selectAll()
            .where {
                (PriceTable.instrumentId eq instrumentId.value) and
                    (PriceTable.timestamp greaterEq fromOffset) and
                    (PriceTable.timestamp lessEq toOffset)
            }
            .orderBy(PriceTable.timestamp, SortOrder.DESC)
            .map { it.toPricePoint() }
    }

    override suspend fun findDailyCloseByInstrumentId(
        instrumentId: InstrumentId,
        from: Instant,
        to: Instant,
    ): List<PricePoint> = newSuspendedTransaction(db = db) {
        val fromOffset = from.atOffset(ZoneOffset.UTC)
        val toOffset = to.atOffset(ZoneOffset.UTC)
        val sql = """
            SELECT instrument_id,
                   close_price_amount AS price_amount,
                   close_price_currency AS price_currency,
                   close_timestamp AS timestamp,
                   close_source AS source
            FROM daily_close_prices
            WHERE instrument_id = ? AND bucket >= ? AND bucket <= ?
            ORDER BY bucket DESC
        """.trimIndent()
        val conn = this.connection.connection as java.sql.Connection
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, instrumentId.value)
            stmt.setObject(2, fromOffset)
            stmt.setObject(3, toOffset)
            val rs = stmt.executeQuery()
            val results = mutableListOf<PricePoint>()
            while (rs.next()) {
                results.add(
                    PricePoint(
                        instrumentId = InstrumentId(rs.getString("instrument_id")),
                        price = Money(
                            rs.getBigDecimal("price_amount"),
                            Currency.getInstance(rs.getString("price_currency")),
                        ),
                        timestamp = rs.getObject("timestamp", java.time.OffsetDateTime::class.java).toInstant(),
                        source = PriceSource.valueOf(rs.getString("source")),
                    )
                )
            }
            results
        }
    }

    override suspend fun findStaleInstruments(thresholdHours: Long): List<StaleInstrument> =
        newSuspendedTransaction(db = db) {
            val sql = """
                WITH latest_per_instrument AS (
                    SELECT instrument_id, MAX(timestamp) AS last_updated
                    FROM prices
                    GROUP BY instrument_id
                ),
                platform_latest AS (
                    SELECT MAX(last_updated) AS global_max FROM latest_per_instrument
                )
                SELECT lpi.instrument_id,
                       lpi.last_updated,
                       EXTRACT(EPOCH FROM (pl.global_max - lpi.last_updated)) / 3600.0 AS age_hours
                FROM latest_per_instrument lpi
                CROSS JOIN platform_latest pl
                WHERE EXTRACT(EPOCH FROM (pl.global_max - lpi.last_updated)) / 3600.0 > ?
                ORDER BY lpi.last_updated ASC
            """.trimIndent()
            val conn = this.connection.connection as java.sql.Connection
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, thresholdHours)
                val rs = stmt.executeQuery()
                val results = mutableListOf<StaleInstrument>()
                while (rs.next()) {
                    val ageHoursDouble = rs.getDouble("age_hours")
                    results.add(
                        StaleInstrument(
                            instrumentId = rs.getString("instrument_id"),
                            lastUpdated = rs.getObject("last_updated", java.time.OffsetDateTime::class.java).toInstant(),
                            ageHours = ageHoursDouble.toLong(),
                        )
                    )
                }
                results
            }
        }

    private fun ResultRow.toPricePoint(): PricePoint = PricePoint(
        instrumentId = InstrumentId(this[PriceTable.instrumentId]),
        price = Money(
            this[PriceTable.priceAmount],
            Currency.getInstance(this[PriceTable.priceCurrency]),
        ),
        timestamp = this[PriceTable.timestamp].toInstant(),
        source = PriceSource.valueOf(this[PriceTable.dataSource]),
    )
}

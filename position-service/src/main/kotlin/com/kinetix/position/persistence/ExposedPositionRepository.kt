package com.kinetix.position.persistence

import com.kinetix.common.model.*
import com.kinetix.common.model.instrument.InstrumentTypeCode
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Join
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.batchUpsert
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.upsert
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Currency

class ExposedPositionRepository(private val db: Database? = null) : PositionRepository {

    override suspend fun save(position: Position): Unit = newSuspendedTransaction(db = db) {
        PositionsTable.upsert(PositionsTable.bookId, PositionsTable.instrumentId) {
            it[bookId] = position.bookId.value
            it[instrumentId] = position.instrumentId.value
            it[assetClass] = position.assetClass.name
            it[quantity] = position.quantity
            it[avgCostAmount] = position.averageCost.amount
            it[marketPriceAmount] = position.marketPrice.amount
            it[currency] = position.currency.currencyCode
            it[updatedAt] = OffsetDateTime.now(ZoneOffset.UTC)
            it[realizedPnlAmount] = position.realizedPnl.amount
            it[instrumentType] = position.instrumentType.name
            it[strategyId] = position.strategyId
        }
    }

    override suspend fun saveAll(positions: List<Position>): Unit = newSuspendedTransaction(db = db) {
        PositionsTable.batchUpsert(
            positions,
            PositionsTable.bookId,
            PositionsTable.instrumentId,
            shouldReturnGeneratedValues = false,
        ) { position ->
            this[PositionsTable.bookId] = position.bookId.value
            this[PositionsTable.instrumentId] = position.instrumentId.value
            this[PositionsTable.assetClass] = position.assetClass.name
            this[PositionsTable.quantity] = position.quantity
            this[PositionsTable.avgCostAmount] = position.averageCost.amount
            this[PositionsTable.marketPriceAmount] = position.marketPrice.amount
            this[PositionsTable.currency] = position.currency.currencyCode
            this[PositionsTable.updatedAt] = OffsetDateTime.now(ZoneOffset.UTC)
            this[PositionsTable.realizedPnlAmount] = position.realizedPnl.amount
            this[PositionsTable.instrumentType] = position.instrumentType?.name ?: "UNKNOWN"
            this[PositionsTable.strategyId] = position.strategyId
        }
    }

    override suspend fun findByBookId(bookId: BookId): List<Position> = newSuspendedTransaction(db = db) {
        positionsWithStrategiesJoin()
            .selectAll()
            .where { PositionsTable.bookId eq bookId.value }
            .map { it.toPosition() }
    }

    override suspend fun findByInstrumentId(instrumentId: InstrumentId): List<Position> = newSuspendedTransaction(db = db) {
        positionsWithStrategiesJoin()
            .selectAll()
            .where { PositionsTable.instrumentId eq instrumentId.value }
            .map { it.toPosition() }
    }

    override suspend fun findByKey(
        bookId: BookId,
        instrumentId: InstrumentId,
    ): Position? = newSuspendedTransaction(db = db) {
        positionsWithStrategiesJoin()
            .selectAll()
            .where {
                (PositionsTable.bookId eq bookId.value) and
                    (PositionsTable.instrumentId eq instrumentId.value)
            }
            .singleOrNull()
            ?.toPosition()
    }

    override suspend fun delete(
        bookId: BookId,
        instrumentId: InstrumentId,
    ): Unit = newSuspendedTransaction(db = db) {
        PositionsTable.deleteWhere {
            (PositionsTable.bookId eq bookId.value) and
                (PositionsTable.instrumentId eq instrumentId.value)
        }
    }

    override suspend fun findDistinctBookIds(): List<BookId> = newSuspendedTransaction(db = db) {
        PositionsTable
            .select(PositionsTable.bookId)
            .withDistinct()
            .map { BookId(it[PositionsTable.bookId]) }
    }

    private fun positionsWithStrategiesJoin(): Join =
        PositionsTable.join(
            TradeStrategiesTable,
            JoinType.LEFT,
            onColumn = PositionsTable.strategyId,
            otherColumn = TradeStrategiesTable.strategyId,
        )

    private fun ResultRow.toPosition(): Position = Position(
        bookId = BookId(this[PositionsTable.bookId]),
        instrumentId = InstrumentId(this[PositionsTable.instrumentId]),
        assetClass = AssetClass.valueOf(this[PositionsTable.assetClass]),
        quantity = this[PositionsTable.quantity],
        averageCost = Money(
            this[PositionsTable.avgCostAmount],
            Currency.getInstance(this[PositionsTable.currency]),
        ),
        marketPrice = Money(
            this[PositionsTable.marketPriceAmount],
            Currency.getInstance(this[PositionsTable.currency]),
        ),
        realizedPnl = Money(
            this[PositionsTable.realizedPnlAmount],
            Currency.getInstance(this[PositionsTable.currency]),
        ),
        instrumentType = InstrumentTypeCode.fromString(this[PositionsTable.instrumentType]),
        strategyId = this[PositionsTable.strategyId],
        strategyType = this.getOrNull(TradeStrategiesTable.strategyType),
        strategyName = this.getOrNull(TradeStrategiesTable.name),
    )
}

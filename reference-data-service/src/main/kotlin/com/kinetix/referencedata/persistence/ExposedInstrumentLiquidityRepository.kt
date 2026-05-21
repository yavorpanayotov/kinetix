package com.kinetix.referencedata.persistence

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.LiquidityTier
import com.kinetix.referencedata.model.InstrumentLiquidity
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.upsert
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ExposedInstrumentLiquidityRepository(
    private val db: Database? = null,
) : InstrumentLiquidityRepository {

    override suspend fun findById(instrumentId: String): InstrumentLiquidity? =
        newSuspendedTransaction(db = db) {
            InstrumentLiquidityTable
                .selectAll()
                .where { InstrumentLiquidityTable.instrumentId eq instrumentId }
                .singleOrNull()
                ?.toInstrumentLiquidity()
        }

    override suspend fun findByIds(instrumentIds: List<String>): List<InstrumentLiquidity> =
        newSuspendedTransaction(db = db) {
            if (instrumentIds.isEmpty()) return@newSuspendedTransaction emptyList()
            InstrumentLiquidityTable
                .selectAll()
                .where { InstrumentLiquidityTable.instrumentId inList instrumentIds }
                .map { it.toInstrumentLiquidity() }
        }

    override suspend fun upsert(liquidity: InstrumentLiquidity): Unit =
        newSuspendedTransaction(db = db) {
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            InstrumentLiquidityTable.upsert {
                it[instrumentId] = liquidity.instrumentId
                it[adv] = BigDecimal.valueOf(liquidity.adv)
                it[bidAskSpreadBps] = BigDecimal.valueOf(liquidity.bidAskSpreadBps)
                it[assetClass] = liquidity.assetClass.name
                it[liquidityTier] = liquidity.liquidityTier.name
                it[advUpdatedAt] = liquidity.advUpdatedAt.atOffset(ZoneOffset.UTC)
                it[createdAt] = now
                it[updatedAt] = now
                it[advShares] = liquidity.advShares?.let { v -> BigDecimal.valueOf(v) }
                it[marketDepthScore] = liquidity.marketDepthScore?.let { v -> BigDecimal.valueOf(v) }
                it[dataSource] = liquidity.source
                it[hedgingEligible] = liquidity.hedgingEligible
            }
        }

    override suspend fun findAll(): List<InstrumentLiquidity> =
        newSuspendedTransaction(db = db) {
            InstrumentLiquidityTable
                .selectAll()
                .orderBy(InstrumentLiquidityTable.instrumentId)
                .map { it.toInstrumentLiquidity() }
        }

    private fun org.jetbrains.exposed.sql.ResultRow.toInstrumentLiquidity() = InstrumentLiquidity(
        instrumentId = this[InstrumentLiquidityTable.instrumentId],
        adv = this[InstrumentLiquidityTable.adv].toDouble(),
        bidAskSpreadBps = this[InstrumentLiquidityTable.bidAskSpreadBps].toDouble(),
        assetClass = AssetClass.valueOf(this[InstrumentLiquidityTable.assetClass]),
        liquidityTier = LiquidityTier.valueOf(this[InstrumentLiquidityTable.liquidityTier]),
        advUpdatedAt = this[InstrumentLiquidityTable.advUpdatedAt].toInstant(),
        createdAt = this[InstrumentLiquidityTable.createdAt].toInstant(),
        updatedAt = this[InstrumentLiquidityTable.updatedAt].toInstant(),
        advShares = this[InstrumentLiquidityTable.advShares]?.toDouble(),
        marketDepthScore = this[InstrumentLiquidityTable.marketDepthScore]?.toDouble(),
        source = this[InstrumentLiquidityTable.dataSource],
        hedgingEligible = this[InstrumentLiquidityTable.hedgingEligible],
    )
}

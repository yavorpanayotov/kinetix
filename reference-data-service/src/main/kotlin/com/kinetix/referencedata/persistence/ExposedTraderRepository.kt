package com.kinetix.referencedata.persistence

import com.kinetix.common.model.DeskId
import com.kinetix.common.model.Trader
import com.kinetix.common.model.TraderId
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ExposedTraderRepository(
    private val db: Database? = null,
) : TraderRepository {

    override suspend fun save(trader: Trader): Unit = newSuspendedTransaction(db = db) {
        val now = OffsetDateTime.now(ZoneOffset.UTC)

        val existing = TradersTable
            .selectAll()
            .where { TradersTable.id eq trader.id.value }
            .singleOrNull()

        if (existing != null) {
            TradersTable.update({ TradersTable.id eq trader.id.value }) {
                it[name] = trader.name
                it[deskId] = trader.deskId.value
                it[email] = trader.email
                it[notionalLimitUsd] = trader.notionalLimitUsd
                it[updatedAt] = now
            }
        } else {
            TradersTable.insert {
                it[id] = trader.id.value
                it[name] = trader.name
                it[deskId] = trader.deskId.value
                it[email] = trader.email
                it[notionalLimitUsd] = trader.notionalLimitUsd
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
    }

    override suspend fun findById(id: TraderId): Trader? =
        newSuspendedTransaction(db = db) {
            TradersTable
                .selectAll()
                .where { TradersTable.id eq id.value }
                .singleOrNull()
                ?.toTrader()
        }

    override suspend fun findAll(): List<Trader> =
        newSuspendedTransaction(db = db) {
            TradersTable
                .selectAll()
                .orderBy(TradersTable.name, SortOrder.ASC)
                .map { it.toTrader() }
        }

    override suspend fun findByDeskId(deskId: DeskId): List<Trader> =
        newSuspendedTransaction(db = db) {
            TradersTable
                .selectAll()
                .where { TradersTable.deskId eq deskId.value }
                .orderBy(TradersTable.name, SortOrder.ASC)
                .map { it.toTrader() }
        }

    private fun ResultRow.toTrader() = Trader(
        id = TraderId(this[TradersTable.id]),
        name = this[TradersTable.name],
        deskId = DeskId(this[TradersTable.deskId]),
        email = this[TradersTable.email],
        notionalLimitUsd = this[TradersTable.notionalLimitUsd],
        createdAt = this[TradersTable.createdAt].toInstant(),
        updatedAt = this[TradersTable.updatedAt].toInstant(),
    )
}

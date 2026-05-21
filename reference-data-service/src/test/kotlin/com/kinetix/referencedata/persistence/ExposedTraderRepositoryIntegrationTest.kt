package com.kinetix.referencedata.persistence

import com.kinetix.common.model.DeskId
import com.kinetix.common.model.Trader
import com.kinetix.common.model.TraderId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

private suspend fun seedDivision(id: String) {
    val now = OffsetDateTime.now(ZoneOffset.UTC)
    newSuspendedTransaction {
        DivisionsTable.insert {
            it[DivisionsTable.id] = id
            it[name] = "$id Division"
            it[createdAt] = now
            it[updatedAt] = now
        }
    }
}

private suspend fun seedDesk(id: String, divisionId: String) {
    val now = OffsetDateTime.now(ZoneOffset.UTC)
    newSuspendedTransaction {
        DesksTable.insert {
            it[DesksTable.id] = id
            it[name] = "$id Desk"
            it[DesksTable.divisionId] = divisionId
            it[createdAt] = now
            it[updatedAt] = now
        }
    }
}

private fun trader(
    id: String,
    deskId: String,
    name: String = "Trader $id",
    notionalLimitUsd: BigDecimal? = BigDecimal("5000000.00"),
) = Trader(
    id = TraderId(id),
    name = name,
    deskId = DeskId(deskId),
    email = "$id@kinetix.test",
    notionalLimitUsd = notionalLimitUsd,
)

class ExposedTraderRepositoryIntegrationTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val repository = ExposedTraderRepository(db)

    beforeEach {
        newSuspendedTransaction(db = db) {
            TradersTable.deleteAll()
            DesksTable.deleteAll()
            DivisionsTable.deleteAll()
        }
        seedDivision("equities")
        seedDesk("desk-momentum", "equities")
        seedDesk("desk-statarb", "equities")
    }

    test("save persists a trader and findById round-trips all fields") {
        repository.save(
            trader(id = "t-001", deskId = "desk-momentum", name = "Alice Cohen"),
        )

        val retrieved = repository.findById(TraderId("t-001"))
        retrieved.shouldNotBeNull()
        retrieved.name shouldBe "Alice Cohen"
        retrieved.deskId shouldBe DeskId("desk-momentum")
        retrieved.email shouldBe "t-001@kinetix.test"
        retrieved.notionalLimitUsd shouldBe BigDecimal("5000000.0000")
    }

    test("findById surfaces createdAt and updatedAt populated by the database") {
        repository.save(trader(id = "t-001", deskId = "desk-momentum"))

        val retrieved = repository.findById(TraderId("t-001"))!!
        retrieved.createdAt shouldBeGreaterThan Instant.EPOCH
        retrieved.updatedAt shouldBeGreaterThan Instant.EPOCH
    }

    test("findById returns null for an unknown trader id") {
        repository.findById(TraderId("DOES-NOT-EXIST")).shouldBeNull()
    }

    test("findByDeskId returns every trader on that desk") {
        repository.save(trader(id = "t-001", deskId = "desk-momentum"))
        repository.save(trader(id = "t-002", deskId = "desk-momentum"))
        repository.save(trader(id = "t-003", deskId = "desk-statarb"))

        val momentum = repository.findByDeskId(DeskId("desk-momentum"))
        momentum shouldHaveSize 2
        momentum.map { it.id.value }.toSet() shouldBe setOf("t-001", "t-002")
    }

    test("save overwrites an existing trader (upsert semantics)") {
        repository.save(trader(id = "t-001", deskId = "desk-momentum", name = "Alice"))
        repository.save(trader(id = "t-001", deskId = "desk-statarb", name = "Alice Cohen"))

        val retrieved = repository.findById(TraderId("t-001"))!!
        retrieved.name shouldBe "Alice Cohen"
        retrieved.deskId shouldBe DeskId("desk-statarb")
        repository.findAll() shouldHaveSize 1
    }

    test("null notionalLimitUsd round-trips as null") {
        repository.save(
            trader(id = "t-001", deskId = "desk-momentum", notionalLimitUsd = null),
        )
        repository.findById(TraderId("t-001"))!!.notionalLimitUsd.shouldBeNull()
    }
})

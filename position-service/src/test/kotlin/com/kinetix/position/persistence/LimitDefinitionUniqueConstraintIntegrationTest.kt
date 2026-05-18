package com.kinetix.position.persistence

import com.kinetix.position.model.LimitDefinition
import com.kinetix.position.model.LimitLevel
import com.kinetix.position.model.LimitType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset

class LimitDefinitionUniqueConstraintIntegrationTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val repository = ExposedLimitDefinitionRepository(db)

    beforeEach {
        newSuspendedTransaction(db = db) { LimitDefinitionsTable.deleteAll() }
    }

    test("upsert by id allows updating an existing (level, entity_id, limit_type) row") {
        val original = LimitDefinition(
            id = "lim-1",
            level = LimitLevel.BOOK,
            entityId = "book-1",
            limitType = LimitType.POSITION,
            limitValue = BigDecimal("1000"),
            intradayLimit = null,
            overnightLimit = null,
            active = true,
        )
        repository.save(original)

        val updated = original.copy(limitValue = BigDecimal("2000"))
        repository.save(updated)

        repository.findByEntityAndType("book-1", LimitLevel.BOOK, LimitType.POSITION)
            ?.limitValue shouldBe BigDecimal("2000.000000000000")
    }

    test("inserting a second row with the same (level, entity_id, limit_type) is rejected by the unique constraint") {
        repository.save(
            LimitDefinition(
                id = "lim-1",
                level = LimitLevel.BOOK,
                entityId = "book-1",
                limitType = LimitType.POSITION,
                limitValue = BigDecimal("1000"),
                intradayLimit = null,
                overnightLimit = null,
                active = true,
            )
        )

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val ex = shouldThrow<ExposedSQLException> {
            transaction(db) {
                maxAttempts = 1
                LimitDefinitionsTable.insert {
                    it[id] = "lim-2"
                    it[level] = LimitLevel.BOOK.name
                    it[entityId] = "book-1"
                    it[limitType] = LimitType.POSITION.name
                    it[limitValue] = BigDecimal("5000")
                    it[active] = true
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }
        }
        ex.message!!.lowercase() shouldContain "limit_definitions"
    }

    test("a different limit_type for the same entity is allowed") {
        repository.save(
            LimitDefinition(
                id = "lim-1",
                level = LimitLevel.BOOK,
                entityId = "book-1",
                limitType = LimitType.POSITION,
                limitValue = BigDecimal("1000"),
                intradayLimit = null,
                overnightLimit = null,
                active = true,
            )
        )
        repository.save(
            LimitDefinition(
                id = "lim-2",
                level = LimitLevel.BOOK,
                entityId = "book-1",
                limitType = LimitType.NOTIONAL,
                limitValue = BigDecimal("100000"),
                intradayLimit = null,
                overnightLimit = null,
                active = true,
            )
        )

        repository.findAll().map { it.id }.toSet() shouldBe setOf("lim-1", "lim-2")
    }
})

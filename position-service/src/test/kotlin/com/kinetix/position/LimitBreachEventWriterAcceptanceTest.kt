package com.kinetix.position

import com.kinetix.position.model.LimitBreachSeverity
import com.kinetix.position.model.LimitCheckStatus
import com.kinetix.position.model.LimitDefinition
import com.kinetix.position.model.LimitLevel
import com.kinetix.position.model.LimitType
import com.kinetix.position.persistence.DatabaseTestSetup
import com.kinetix.position.persistence.ExposedLimitBreachEventWriter
import com.kinetix.position.persistence.ExposedLimitDefinitionRepository
import com.kinetix.position.persistence.ExposedTemporaryLimitIncreaseRepository
import com.kinetix.position.persistence.LimitBreachEventsTable
import com.kinetix.position.persistence.LimitDefinitionsTable
import com.kinetix.position.service.LimitHierarchyService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class LimitBreachEventWriterAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val writer = ExposedLimitBreachEventWriter(db)
    val limitDefinitionRepo = ExposedLimitDefinitionRepository(db)
    val temporaryLimitIncreaseRepo = ExposedTemporaryLimitIncreaseRepository(db)

    beforeEach {
        newSuspendedTransaction(db = db) {
            LimitBreachEventsTable.deleteAll()
            LimitDefinitionsTable.deleteAll()
        }
    }

    test("recordBreach persists a new breach row") {
        writer.recordBreach(
            entityId = "BOOK-EQ-US",
            bookId = "BOOK-EQ-US",
            limitType = LimitType.NOTIONAL.name,
            severity = LimitBreachSeverity.HARD,
            currentValue = BigDecimal("1200000"),
            limitValue = BigDecimal("1000000"),
            breachedAt = Instant.now(),
        )

        val events = writer.findByBook("BOOK-EQ-US")
        events shouldHaveSize 1
        val event = events.single()
        event.entityId shouldBe "BOOK-EQ-US"
        event.bookId shouldBe "BOOK-EQ-US"
        event.limitType shouldBe LimitType.NOTIONAL.name
        event.severity shouldBe LimitBreachSeverity.HARD
        event.currentValue.compareTo(BigDecimal("1200000")) shouldBe 0
        event.limitValue.compareTo(BigDecimal("1000000")) shouldBe 0
        event.resolvedAt.shouldBeNull()
    }

    test("recordBreach is idempotent for an ongoing breach") {
        val args = arrayOf("ENTITY-1", "BOOK-A", LimitType.NOTIONAL.name)
        writer.recordBreach(
            entityId = args[0], bookId = args[1], limitType = args[2],
            severity = LimitBreachSeverity.HARD,
            currentValue = BigDecimal("1200000"), limitValue = BigDecimal("1000000"),
            breachedAt = Instant.now(),
        )
        writer.recordBreach(
            entityId = args[0], bookId = args[1], limitType = args[2],
            severity = LimitBreachSeverity.HARD,
            currentValue = BigDecimal("1300000"), limitValue = BigDecimal("1000000"),
            breachedAt = Instant.now(),
        )

        writer.findByBook("BOOK-A") shouldHaveSize 1
    }

    test("recordBreach after resolution creates a new row") {
        writer.recordBreach(
            entityId = "ENTITY-1", bookId = "BOOK-A", limitType = LimitType.NOTIONAL.name,
            severity = LimitBreachSeverity.HARD,
            currentValue = BigDecimal("1200000"), limitValue = BigDecimal("1000000"),
            breachedAt = Instant.now(),
        )
        writer.recordResolution("ENTITY-1", "BOOK-A", LimitType.NOTIONAL.name, Instant.now())
        writer.recordBreach(
            entityId = "ENTITY-1", bookId = "BOOK-A", limitType = LimitType.NOTIONAL.name,
            severity = LimitBreachSeverity.HARD,
            currentValue = BigDecimal("1400000"), limitValue = BigDecimal("1000000"),
            breachedAt = Instant.now(),
        )

        val events = writer.findByBook("BOOK-A")
        events shouldHaveSize 2
        events.count { it.resolvedAt == null } shouldBe 1
        events.count { it.resolvedAt != null } shouldBe 1
    }

    test("recordResolution closes an open breach") {
        writer.recordBreach(
            entityId = "ENTITY-1", bookId = "BOOK-A", limitType = LimitType.NOTIONAL.name,
            severity = LimitBreachSeverity.HARD,
            currentValue = BigDecimal("1200000"), limitValue = BigDecimal("1000000"),
            breachedAt = Instant.now(),
        )

        val resolved = writer.recordResolution("ENTITY-1", "BOOK-A", LimitType.NOTIONAL.name, Instant.now())

        resolved shouldBe 1
        writer.findByBook("BOOK-A").single().resolvedAt.shouldNotBeNull()
    }

    test("recordResolution is a no-op when there is no open breach") {
        val resolved = writer.recordResolution("ENTITY-1", "BOOK-A", LimitType.NOTIONAL.name, Instant.now())

        resolved shouldBe 0
        writer.findByBook("BOOK-A") shouldHaveSize 0
    }

    test("recordResolution resolves all open breaches for the key") {
        writer.recordBreach(
            entityId = "ENTITY-1", bookId = "BOOK-A", limitType = LimitType.NOTIONAL.name,
            severity = LimitBreachSeverity.HARD,
            currentValue = BigDecimal("1200000"), limitValue = BigDecimal("1000000"),
            breachedAt = Instant.now(),
        )

        val resolved = writer.recordResolution("ENTITY-1", "BOOK-A", LimitType.NOTIONAL.name, Instant.now())

        resolved shouldBe 1
        writer.findByBook("BOOK-A").count { it.resolvedAt == null } shouldBe 0
    }

    test("findByBook returns events newest-first") {
        val older = Instant.now().minusSeconds(3600)
        val newer = Instant.now()
        writer.recordBreach(
            entityId = "ENTITY-OLD", bookId = "BOOK-A", limitType = LimitType.NOTIONAL.name,
            severity = LimitBreachSeverity.HARD,
            currentValue = BigDecimal("1200000"), limitValue = BigDecimal("1000000"),
            breachedAt = older,
        )
        writer.recordBreach(
            entityId = "ENTITY-NEW", bookId = "BOOK-A", limitType = LimitType.NOTIONAL.name,
            severity = LimitBreachSeverity.HARD,
            currentValue = BigDecimal("1300000"), limitValue = BigDecimal("1000000"),
            breachedAt = newer,
        )

        val events = writer.findByBook("BOOK-A")
        events shouldHaveSize 2
        events.map { it.entityId } shouldBe listOf("ENTITY-NEW", "ENTITY-OLD")
    }

    test("findByBook isolates by book") {
        writer.recordBreach(
            entityId = "ENTITY-A", bookId = "BOOK-A", limitType = LimitType.NOTIONAL.name,
            severity = LimitBreachSeverity.HARD,
            currentValue = BigDecimal("1200000"), limitValue = BigDecimal("1000000"),
            breachedAt = Instant.now(),
        )
        writer.recordBreach(
            entityId = "ENTITY-B", bookId = "BOOK-B", limitType = LimitType.NOTIONAL.name,
            severity = LimitBreachSeverity.HARD,
            currentValue = BigDecimal("1200000"), limitValue = BigDecimal("1000000"),
            breachedAt = Instant.now(),
        )

        val bookA = writer.findByBook("BOOK-A")
        bookA shouldHaveSize 1
        bookA.single().bookId shouldBe "BOOK-A"
    }

    test("LimitHierarchyService persists a breach when checkLimit detects one") {
        limitDefinitionRepo.save(
            LimitDefinition(
                id = UUID.randomUUID().toString(),
                level = LimitLevel.BOOK,
                entityId = "BOOK-EQ-US",
                limitType = LimitType.NOTIONAL,
                limitValue = BigDecimal("1000000"),
                intradayLimit = null,
                overnightLimit = null,
                active = true,
            ),
        )
        val service = LimitHierarchyService(
            limitDefinitionRepo,
            temporaryLimitIncreaseRepo,
            breachEventWriter = writer,
        )

        val result = service.checkLimit(
            entityId = "BOOK-EQ-US",
            level = LimitLevel.BOOK,
            limitType = LimitType.NOTIONAL,
            currentExposure = BigDecimal("1500000"),
        )

        result.status shouldBe LimitCheckStatus.BREACHED
        val events = writer.findByBook("BOOK-EQ-US")
        events shouldHaveSize 1
        events.single().severity shouldBe LimitBreachSeverity.HARD
        events.single().resolvedAt.shouldBeNull()
    }

    test("LimitHierarchyService records a resolution when a previously-breached limit returns to OK") {
        limitDefinitionRepo.save(
            LimitDefinition(
                id = UUID.randomUUID().toString(),
                level = LimitLevel.BOOK,
                entityId = "BOOK-EQ-US",
                limitType = LimitType.NOTIONAL,
                limitValue = BigDecimal("1000000"),
                intradayLimit = null,
                overnightLimit = null,
                active = true,
            ),
        )
        val service = LimitHierarchyService(
            limitDefinitionRepo,
            temporaryLimitIncreaseRepo,
            breachEventWriter = writer,
        )

        service.checkLimit(
            entityId = "BOOK-EQ-US",
            level = LimitLevel.BOOK,
            limitType = LimitType.NOTIONAL,
            currentExposure = BigDecimal("1500000"),
        )
        service.checkLimit(
            entityId = "BOOK-EQ-US",
            level = LimitLevel.BOOK,
            limitType = LimitType.NOTIONAL,
            currentExposure = BigDecimal("100000"),
        )

        val events = writer.findByBook("BOOK-EQ-US")
        events shouldHaveSize 1
        events.single().resolvedAt.shouldNotBeNull()
    }
})

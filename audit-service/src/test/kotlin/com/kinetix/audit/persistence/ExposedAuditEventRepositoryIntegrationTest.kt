package com.kinetix.audit.persistence

import com.kinetix.audit.model.AuditEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant

private val NOW = Instant.parse("2026-01-15T10:00:00Z")

private fun auditEvent(
    tradeId: String = "t-1",
    bookId: String = "port-1",
    instrumentId: String = "AAPL",
    assetClass: String = "EQUITY",
    side: String = "BUY",
    quantity: String = "100",
    priceAmount: String = "150.00",
    priceCurrency: String = "USD",
    tradedAt: String = "2026-01-15T10:00:00Z",
    receivedAt: Instant = NOW,
    correlationId: String? = null,
) = AuditEvent(
    tradeId = tradeId,
    bookId = bookId,
    instrumentId = instrumentId,
    assetClass = assetClass,
    side = side,
    quantity = quantity,
    priceAmount = priceAmount,
    priceCurrency = priceCurrency,
    tradedAt = tradedAt,
    receivedAt = receivedAt,
    correlationId = correlationId,
)

class ExposedAuditEventRepositoryIntegrationTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val repository: AuditEventRepository = ExposedAuditEventRepository()

    beforeEach {
        newSuspendedTransaction {
            exec("TRUNCATE TABLE audit_events RESTART IDENTITY")
        }
    }

    test("save and findAll returns the event") {
        repository.save(auditEvent())

        val found = repository.findAll()
        found shouldHaveSize 1
        found[0].tradeId shouldBe "t-1"
        found[0].bookId shouldBe "port-1"
        found[0].instrumentId shouldBe "AAPL"
        found[0].side shouldBe "BUY"
        found[0].quantity shouldBe "100"
        found[0].priceAmount shouldBe "150"
        found[0].priceCurrency shouldBe "USD"
    }

    test("findByBookId returns only matching events") {
        repository.save(auditEvent(tradeId = "t-1", bookId = "port-1"))
        repository.save(auditEvent(tradeId = "t-2", bookId = "port-2"))
        repository.save(auditEvent(tradeId = "t-3", bookId = "port-1"))

        val results = repository.findByBookId("port-1")
        results shouldHaveSize 2
        results.forEach { it.bookId shouldBe "port-1" }
    }

    test("findByBookId returns empty list for unknown portfolio") {
        repository.findByBookId("unknown") shouldHaveSize 0
    }

    test("findAll returns events in insertion order") {
        repository.save(auditEvent(tradeId = "t-1"))
        repository.save(auditEvent(tradeId = "t-2"))
        repository.save(auditEvent(tradeId = "t-3"))

        val found = repository.findAll()
        found shouldHaveSize 3
        found[0].tradeId shouldBe "t-1"
        found[1].tradeId shouldBe "t-2"
        found[2].tradeId shouldBe "t-3"
    }

    test("save round-trips a correlationId") {
        repository.save(auditEvent(tradeId = "t-corr", correlationId = "corr-abc-123"))

        val found = repository.findAll()
        found shouldHaveSize 1
        found[0].correlationId shouldBe "corr-abc-123"
    }

    test("save accepts a null correlationId") {
        repository.save(auditEvent(tradeId = "t-no-corr", correlationId = null))

        val found = repository.findAll()
        found shouldHaveSize 1
        found[0].correlationId shouldBe null
    }

    test("save preserves all fields accurately") {
        val event = auditEvent(
            tradeId = "t-precise",
            bookId = "port-precise",
            instrumentId = "TSLA",
            assetClass = "EQUITY",
            side = "SELL",
            quantity = "500.5",
            priceAmount = "199.99",
            priceCurrency = "EUR",
            tradedAt = "2026-06-15T14:30:00Z",
        )
        repository.save(event)

        val found = repository.findAll()
        found shouldHaveSize 1
        found[0].tradeId shouldBe "t-precise"
        found[0].instrumentId shouldBe "TSLA"
        found[0].side shouldBe "SELL"
        found[0].quantity shouldBe "500.5"
        found[0].priceAmount shouldBe "199.99"
        found[0].priceCurrency shouldBe "EUR"
    }
})

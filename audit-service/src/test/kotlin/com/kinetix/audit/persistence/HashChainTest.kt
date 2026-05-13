package com.kinetix.audit.persistence

import com.kinetix.audit.model.AuditEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveLength
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import java.time.Instant

private val NOW = Instant.parse("2026-01-15T10:00:00Z")

private fun auditEvent(
    id: Long = 0,
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
    userId: String? = null,
    userRole: String? = null,
    traderId: String? = null,
    eventType: String = "TRADE_BOOKED",
) = AuditEvent(
    id = id,
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
    userId = userId,
    userRole = userRole,
    traderId = traderId,
    eventType = eventType,
)

class HashChainTest : FunSpec({

    test("first event should have null previousHash") {
        val event = auditEvent(id = 1)
        val hash = AuditHasher.computeHash(event, previousHash = null)

        hash.shouldNotBeNull()
        hash shouldHaveLength 64 // SHA-256 hex string
    }

    test("second event should reference first event hash") {
        val firstEvent = auditEvent(id = 1)
        val firstHash = AuditHasher.computeHash(firstEvent, previousHash = null)

        val secondEvent = auditEvent(id = 2, tradeId = "t-2")
        val secondHash = AuditHasher.computeHash(secondEvent, previousHash = firstHash)

        secondHash.shouldNotBeNull()
        secondHash shouldHaveLength 64
        // The second hash should differ from the first because the inputs differ
        (secondHash != firstHash) shouldBe true
    }

    test("hash computation should be deterministic") {
        val event = auditEvent(id = 1)
        val hash1 = AuditHasher.computeHash(event, previousHash = null)
        val hash2 = AuditHasher.computeHash(event, previousHash = null)

        hash1 shouldBe hash2
    }

    test("chain verification should pass for valid chain") {
        val event1 = auditEvent(id = 1, tradeId = "t-1")
        val hash1 = AuditHasher.computeHash(event1, previousHash = null)
        val chained1 = event1.copy(previousHash = null, recordHash = hash1)

        val event2 = auditEvent(id = 2, tradeId = "t-2")
        val hash2 = AuditHasher.computeHash(event2, previousHash = hash1)
        val chained2 = event2.copy(previousHash = hash1, recordHash = hash2)

        val events = listOf(chained1, chained2)
        val result = AuditHasher.verifyChain(events)
        result.valid shouldBe true
        result.eventCount shouldBe 2
    }

    test("chain verification should fail for tampered event") {
        val event1 = auditEvent(id = 1, tradeId = "t-1")
        val hash1 = AuditHasher.computeHash(event1, previousHash = null)
        val chained1 = event1.copy(previousHash = null, recordHash = hash1)

        val event2 = auditEvent(id = 2, tradeId = "t-2")
        val hash2 = AuditHasher.computeHash(event2, previousHash = hash1)
        // Tamper: change tradeId after hash was computed
        val tampered2 = event2.copy(tradeId = "t-tampered", previousHash = hash1, recordHash = hash2)

        val events = listOf(chained1, tampered2)
        val result = AuditHasher.verifyChain(events)
        result.valid shouldBe false
    }

    test("chain verification should pass for empty chain") {
        val result = AuditHasher.verifyChain(emptyList())
        result.valid shouldBe true
        result.eventCount shouldBe 0
    }

    test("traderId is included in the hash input") {
        val withTrader = auditEvent(id = 1, traderId = "tr-eg-001")
        val withoutTrader = auditEvent(id = 1, traderId = null)
        AuditHasher.computeHash(withTrader, previousHash = null) shouldNotBe
            AuditHasher.computeHash(withoutTrader, previousHash = null)
    }

    test("changing traderId breaks chain verification") {
        val event1 = auditEvent(id = 1, tradeId = "t-1", traderId = "tr-eg-001")
        val hash1 = AuditHasher.computeHash(event1, previousHash = null)
        val chained1 = event1.copy(previousHash = null, recordHash = hash1)
        val tampered = chained1.copy(traderId = "tr-eg-999")

        AuditHasher.verifyChain(listOf(tampered)).valid shouldBe false
    }
})

private infix fun String.shouldNotBe(other: String) {
    if (this == other) {
        throw AssertionError("Expected hashes to differ but both were $this")
    }
}

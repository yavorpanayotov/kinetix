package com.kinetix.audit

import com.kinetix.audit.model.AuditEvent
import com.kinetix.audit.persistence.AuditEventRepository
import com.kinetix.audit.persistence.AuditHasher
import com.kinetix.audit.persistence.DatabaseTestSetup
import com.kinetix.audit.persistence.ExposedAuditEventRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant

private val BASE_TIME: Instant = Instant.parse("2026-01-15T10:00:00Z")

private fun tradeEvent(tradeId: String, receivedAt: Instant): AuditEvent = AuditEvent(
    tradeId = tradeId,
    bookId = "port-1",
    instrumentId = "AAPL",
    assetClass = "EQUITY",
    side = "BUY",
    quantity = "100",
    priceAmount = "150.00",
    priceCurrency = "USD",
    tradedAt = "2026-01-15T10:00:00Z",
    receivedAt = receivedAt,
    eventType = "TRADE_BOOKED",
)

class AuditHashChainConcurrencyAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val repository: AuditEventRepository = ExposedAuditEventRepository(db)

    beforeEach {
        newSuspendedTransaction(db = db) {
            exec("TRUNCATE TABLE audit_events RESTART IDENTITY")
        }
    }

    test("50 trade events appended concurrently — every event chains to the previous one and verifyChain returns valid=true") {
        val concurrency = 50

        coroutineScope {
            (1..concurrency).map { i ->
                async(Dispatchers.IO) {
                    repository.save(
                        tradeEvent(
                            tradeId = "t-$i",
                            receivedAt = BASE_TIME.plusMillis(i.toLong()),
                        )
                    )
                }
            }.awaitAll()
        }

        val events = repository.findAll()
        events.size shouldBe concurrency

        // No two events may share the same previousHash — the chain is intrinsically serial.
        val nonNullPreviousHashes = events.mapNotNull { it.previousHash }
        nonNullPreviousHashes.toSet().size shouldBe nonNullPreviousHashes.size

        // Exactly one genesis event (previousHash = null).
        events.count { it.previousHash == null } shouldBe 1

        // Each subsequent event's previousHash must equal the prior event's recordHash (ordered by id).
        for (i in 1 until events.size) {
            events[i].previousHash shouldBe events[i - 1].recordHash
        }

        AuditHasher.verifyChain(events).valid shouldBe true
    }
})

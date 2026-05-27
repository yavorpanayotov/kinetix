package com.kinetix.audit.dlq

import com.kinetix.audit.model.AuditEvent
import com.kinetix.audit.persistence.AuditEventRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import java.time.Instant

/**
 * Replaying the same DLQ event twice must be idempotent:
 *
 *   - the first replay extends the audit chain by exactly one row,
 *   - the second replay of the same event is a no-op — no duplicate audit
 *     row, no hash chain extension.
 *
 * Idempotency is keyed on `tradeId`: if the repository already has an audit
 * event for that trade, a replay attempt is dropped (counted as a skip, not
 * a success and not a failure). Operators routinely re-run DLQ replays when
 * triaging — without this guarantee, a triple-click would silently
 * duplicate every event and corrupt the audit trail.
 */
class DlqReplayIdempotencyTest : FunSpec({

    test("replaying the same event twice saves it exactly once") {
        val repository = mockk<AuditEventRepository>()
        val savedEvents = mutableListOf<AuditEvent>()
        // findByTradeId returns null the first time, then the previously-saved
        // event the second time — mirroring real repository semantics.
        coEvery { repository.findByTradeId("t-1") } answers {
            savedEvents.firstOrNull { it.tradeId == "t-1" }
        }
        coEvery { repository.save(any()) } answers {
            savedEvents += firstArg<AuditEvent>()
        }

        val messages = listOf(DlqMessage(key = "k1", value = tradeEventJson("t-1")))
        val service = DlqReplayService(repository, messageSource = { messages })

        val first = service.replay()
        val second = service.replay()

        first.successCount shouldBe 1
        first.skippedCount shouldBe 0
        first.total shouldBe 1

        second.successCount shouldBe 0
        second.skippedCount shouldBe 1
        second.total shouldBe 1

        // save called exactly once across both replays
        coVerify(exactly = 1) { repository.save(any()) }
        savedEvents.size shouldBe 1
    }

    test("a duplicate event is reported as skipped, not as a failure") {
        val repository = mockk<AuditEventRepository>()
        val existing = AuditEvent(
            tradeId = "t-2",
            bookId = "book-1",
            receivedAt = Instant.parse("2026-01-15T10:00:00Z"),
        )
        coEvery { repository.findByTradeId("t-2") } returns existing
        coEvery { repository.save(any()) } just runs

        val messages = listOf(DlqMessage(key = "k1", value = tradeEventJson("t-2")))
        val service = DlqReplayService(repository, messageSource = { messages })

        val result = service.replay()

        result.successCount shouldBe 0
        result.failureCount shouldBe 0
        result.skippedCount shouldBe 1
        result.total shouldBe 1

        // save is never called when the event is already in the audit trail
        coVerify(exactly = 0) { repository.save(any()) }
    }

    test("a mixed batch of new and duplicate events processes the new ones and skips the duplicates") {
        val repository = mockk<AuditEventRepository>()
        coEvery { repository.findByTradeId("t-3") } returns AuditEvent(
            tradeId = "t-3",
            receivedAt = Instant.parse("2026-01-15T10:00:00Z"),
        )
        coEvery { repository.findByTradeId("t-4") } returns null
        val captured = slot<AuditEvent>()
        coEvery { repository.save(capture(captured)) } just runs

        val messages = listOf(
            DlqMessage(key = "k1", value = tradeEventJson("t-3")),
            DlqMessage(key = "k2", value = tradeEventJson("t-4")),
        )
        val service = DlqReplayService(repository, messageSource = { messages })

        val result = service.replay()

        result.successCount shouldBe 1
        result.skippedCount shouldBe 1
        result.failureCount shouldBe 0
        result.total shouldBe 2

        captured.captured.tradeId shouldBe "t-4"
    }
})

private fun tradeEventJson(tradeId: String) = """
{
  "tradeId": "$tradeId",
  "bookId": "book-1",
  "instrumentId": "AAPL",
  "assetClass": "EQUITY",
  "side": "BUY",
  "quantity": "100",
  "priceAmount": "150.00",
  "priceCurrency": "USD",
  "tradedAt": "2026-01-15T10:00:00Z",
  "eventType": "NEW",
  "status": "LIVE",
  "auditEventType": "TRADE_BOOKED"
}
""".trimIndent()

package com.kinetix.audit.dlq

import com.kinetix.audit.model.AuditEvent
import com.kinetix.audit.persistence.AuditEventRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import java.time.Instant

class DlqReplayServiceTest : FunSpec({

    val repository = mockk<AuditEventRepository>()

    // Default the idempotency lookup to "no prior row" so each existing test
    // exercises the happy-path replay flow. Tests for skip behaviour live in
    // DlqReplayIdempotencyTest.
    beforeEach {
        clearMocks(repository)
        coEvery { repository.findByTradeId(any()) } returns null
    }

    test("replays DLQ events through the audit pipeline and reports success count") {
        coEvery { repository.save(any()) } just runs

        val messages = listOf(
            DlqMessage(key = "k1", value = tradeEventJson("t-1")),
            DlqMessage(key = "k2", value = tradeEventJson("t-2")),
        )
        val service = DlqReplayService(repository, messageSource = { messages })

        val result = service.replay()

        result.successCount shouldBe 2
        result.failureCount shouldBe 0
        result.total shouldBe 2

        coVerify(exactly = 2) { repository.save(any()) }
    }

    test("counts failures when an event fails to parse") {
        coEvery { repository.save(any()) } just runs

        val messages = listOf(
            DlqMessage(key = "k1", value = """{"invalid":"json"}"""),
            DlqMessage(key = "k2", value = tradeEventJson("t-2")),
        )
        val service = DlqReplayService(repository, messageSource = { messages })

        val result = service.replay()

        result.successCount shouldBe 1
        result.failureCount shouldBe 1
        result.total shouldBe 2
    }

    test("counts failures when the repository throws on save") {
        coEvery { repository.save(any()) } throws RuntimeException("DB unavailable")

        val messages = listOf(
            DlqMessage(key = "k1", value = tradeEventJson("t-1")),
        )
        val service = DlqReplayService(repository, messageSource = { messages })

        val result = service.replay()

        result.successCount shouldBe 0
        result.failureCount shouldBe 1
        result.total shouldBe 1
    }

    test("replayed events are saved with DLQ_REPLAY marker in details") {
        val savedEvents = mutableListOf<AuditEvent>()
        coEvery { repository.save(capture(savedEvents)) } just runs

        val messages = listOf(
            DlqMessage(key = "k1", value = tradeEventJson("t-1")),
        )
        val service = DlqReplayService(repository, messageSource = { messages })

        service.replay()

        savedEvents.size shouldBe 1
        savedEvents[0].details shouldBe "DLQ_REPLAY"
    }

    test("returns zero counts when the DLQ is empty") {
        val service = DlqReplayService(repository, messageSource = { emptyList() })

        val result = service.replay()

        result.successCount shouldBe 0
        result.failureCount shouldBe 0
        result.total shouldBe 0

        coVerify(exactly = 0) { repository.save(any()) }
    }

    test("continues processing remaining events after a single failure") {
        var callCount = 0
        coEvery { repository.save(any()) } answers {
            callCount++
            if (callCount == 1) throw RuntimeException("transient failure")
        }

        val messages = listOf(
            DlqMessage(key = "k1", value = tradeEventJson("t-1")),
            DlqMessage(key = "k2", value = tradeEventJson("t-2")),
            DlqMessage(key = "k3", value = tradeEventJson("t-3")),
        )
        val service = DlqReplayService(repository, messageSource = { messages })

        val result = service.replay()

        result.successCount shouldBe 2
        result.failureCount shouldBe 1
        result.total shouldBe 3
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

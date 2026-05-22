package com.kinetix.audit.persistence

import com.kinetix.audit.metrics.AuditMetrics
import com.kinetix.audit.model.AuditEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Instant

/**
 * Contract for [MeteredAuditEventRepository] — the metrics-recording decorator
 * that drives the audit-trail meters on the `overview/audit-service.json`
 * Grafana dashboard (checkbox 4.7 of plans/grafana-v2.md).
 *
 * The decorator wraps any [AuditEventRepository] and, on every `save`, records:
 *  - one increment of the `audit_records_appended_total` append counter,
 *  - one sample into the `audit_record_write_seconds` write-latency timer,
 *  - the resulting chain length into the `audit_chain_length` gauge.
 *
 * All other reads pass straight through to the delegate untouched.
 */
class MeteredAuditEventRepositoryTest : FunSpec({

    fun event(tradeId: String) = AuditEvent(
        tradeId = tradeId,
        eventType = "TRADE_BOOKED",
        receivedAt = Instant.parse("2026-05-21T10:00:00Z"),
    )

    test("save increments the append counter on every persisted record") {
        val registry = SimpleMeterRegistry()
        val repository = MeteredAuditEventRepository(
            FakeAuditEventRepository(),
            AuditMetrics(registry),
        )

        repository.save(event("t-1"))
        repository.save(event("t-2"))

        registry.counter("audit_records_appended_total").count() shouldBe 2.0
    }

    test("save records a write-latency sample on every persisted record") {
        val registry = SimpleMeterRegistry()
        val repository = MeteredAuditEventRepository(
            FakeAuditEventRepository(),
            AuditMetrics(registry),
        )

        repository.save(event("t-1"))

        registry.timer("audit_record_write_seconds").count() shouldBe 1L
    }

    test("save updates the chain-length gauge to the post-append count") {
        val registry = SimpleMeterRegistry()
        val repository = MeteredAuditEventRepository(
            FakeAuditEventRepository(),
            AuditMetrics(registry),
        )

        repository.save(event("t-1"))
        repository.save(event("t-2"))
        repository.save(event("t-3"))

        registry.find("audit_chain_length").gauge()!!.value() shouldBe 3.0
    }

    test("save delegates persistence to the wrapped repository") {
        val delegate = FakeAuditEventRepository()
        val repository = MeteredAuditEventRepository(delegate, AuditMetrics(SimpleMeterRegistry()))

        repository.save(event("t-1"))

        delegate.findAll().map { it.tradeId } shouldBe listOf("t-1")
    }

    test("read operations pass through to the delegate") {
        val delegate = FakeAuditEventRepository()
        delegate.save(event("t-1"))
        val repository = MeteredAuditEventRepository(delegate, AuditMetrics(SimpleMeterRegistry()))

        repository.countAll() shouldBe 1L
        repository.findByTradeId("t-1")?.tradeId shouldBe "t-1"
    }
})

/**
 * Minimal in-memory [AuditEventRepository] used only to exercise the
 * [MeteredAuditEventRepository] decorator in isolation — the decorator's
 * metric behaviour is independent of the persistence backend.
 */
private class FakeAuditEventRepository : AuditEventRepository {
    private val events = mutableListOf<AuditEvent>()

    override suspend fun save(event: AuditEvent) {
        events.add(event)
    }

    override suspend fun findAll(): List<AuditEvent> = events.toList()

    override suspend fun findByBookId(bookId: String): List<AuditEvent> =
        events.filter { it.bookId == bookId }

    override suspend fun findPage(afterId: Long, limit: Int): List<AuditEvent> =
        events.take(limit)

    override suspend fun findPage(
        afterId: Long,
        limit: Int,
        bookId: String?,
        tradeId: String?,
        eventType: String?,
        from: Instant?,
        to: Instant?,
    ): List<AuditEvent> = events.take(limit)

    override suspend fun countAll(): Long = events.size.toLong()

    override suspend fun countSince(since: Instant): Long =
        events.count { it.receivedAt >= since }.toLong()

    override suspend fun findByTradeId(tradeId: String): AuditEvent? =
        events.firstOrNull { it.tradeId == tradeId }

    override suspend fun nextSequenceNumber(): Long = events.size.toLong() + 1L
}

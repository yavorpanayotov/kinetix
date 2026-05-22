package com.kinetix.audit.persistence

import com.kinetix.audit.metrics.AuditMetrics
import com.kinetix.audit.model.AuditEvent
import java.time.Duration
import java.time.Instant

/**
 * An [AuditEventRepository] decorator that records audit-trail metrics around
 * the real append path, leaving persistence entirely to the wrapped [delegate].
 *
 * On every [save] it:
 *  - times the delegate's persist into the `audit_record_write_seconds` timer,
 *  - increments the `audit_records_appended_total` append counter,
 *  - refreshes the `audit_chain_length` gauge to the post-append record count.
 *
 * All read operations pass straight through — the metrics describe the write
 * path only, so reads carry no instrumentation overhead.
 *
 * Keeping instrumentation in a decorator preserves the
 * service/repository/mapper separation: [ExposedAuditEventRepository] stays a
 * pure persistence component, and the metrics concern is composed in at the
 * boundary.
 */
class MeteredAuditEventRepository(
    private val delegate: AuditEventRepository,
    private val metrics: AuditMetrics,
) : AuditEventRepository {

    override suspend fun save(event: AuditEvent) {
        val start = System.nanoTime()
        delegate.save(event)
        metrics.recordWrite(Duration.ofNanos(System.nanoTime() - start))
        metrics.recordAppend()
        metrics.setChainLength(delegate.countAll())
    }

    override suspend fun findAll(): List<AuditEvent> = delegate.findAll()

    override suspend fun findByBookId(bookId: String): List<AuditEvent> =
        delegate.findByBookId(bookId)

    override suspend fun findPage(afterId: Long, limit: Int): List<AuditEvent> =
        delegate.findPage(afterId, limit)

    override suspend fun findPage(
        afterId: Long,
        limit: Int,
        bookId: String?,
        tradeId: String?,
        eventType: String?,
        from: Instant?,
        to: Instant?,
    ): List<AuditEvent> = delegate.findPage(afterId, limit, bookId, tradeId, eventType, from, to)

    override suspend fun countAll(): Long = delegate.countAll()

    override suspend fun countSince(since: Instant): Long = delegate.countSince(since)

    override suspend fun findByTradeId(tradeId: String): AuditEvent? =
        delegate.findByTradeId(tradeId)

    override suspend fun nextSequenceNumber(): Long = delegate.nextSequenceNumber()
}

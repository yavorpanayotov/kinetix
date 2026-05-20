package com.kinetix.position.persistence

import com.kinetix.position.model.LimitBreachEvent
import com.kinetix.position.model.LimitBreachSeverity
import java.math.BigDecimal
import java.time.Instant

/**
 * Persists limit breach events to `limit_breach_events`. Hooked into
 * [com.kinetix.position.service.LimitHierarchyService] so every detected
 * breach — and its later resolution — is recorded for the AI v2 morning
 * brief breach history.
 */
interface LimitBreachEventWriter {
    /**
     * Records a newly-detected breach. Idempotent: if an UNRESOLVED
     * breach already exists for the same (entity_id, book_id,
     * limit_type), this is a no-op — the same ongoing breach is not
     * duplicated on every re-check.
     *
     * Returns the persisted (or pre-existing) event.
     */
    suspend fun recordBreach(
        entityId: String,
        bookId: String,
        limitType: String,
        severity: LimitBreachSeverity,
        currentValue: BigDecimal,
        limitValue: BigDecimal,
        breachedAt: Instant,
    ): LimitBreachEvent

    /**
     * Marks any open (unresolved) breaches for the given
     * (entity_id, book_id, limit_type) as resolved at `resolvedAt`.
     * No-op when there is no open breach. Returns the number of
     * rows resolved.
     */
    suspend fun recordResolution(
        entityId: String,
        bookId: String,
        limitType: String,
        resolvedAt: Instant,
    ): Int

    /** Returns all breach events for a book, newest first. */
    suspend fun findByBook(bookId: String): List<LimitBreachEvent>
}

package com.kinetix.position.model

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * A persisted limit breach event. Recorded when an entity's measured
 * value crossed its cap; `resolvedAt` is filled in when the breach clears
 * and stays null while the breach is still open.
 */
data class LimitBreachEvent(
    val id: UUID,
    val entityId: String,
    val bookId: String,
    val limitType: String,
    val severity: LimitBreachSeverity,
    val currentValue: BigDecimal,
    val limitValue: BigDecimal,
    val breachedAt: Instant,
    val resolvedAt: Instant? = null,
)

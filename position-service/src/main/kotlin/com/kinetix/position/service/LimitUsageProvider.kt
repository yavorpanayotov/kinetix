package com.kinetix.position.service

import com.kinetix.position.model.LimitDefinition
import java.math.BigDecimal

/**
 * Computes the current consumed value for a [LimitDefinition], so the
 * limits-listing API can render utilisation (`current / effectiveLimit`)
 * alongside the ceiling itself. Returns `null` for limit types this
 * service has no source for (e.g. VAR, CONCENTRATION) — those depend on
 * risk-engine output that doesn't live in position-service.
 *
 * Implementations are expected to be cheap to call in a loop over every
 * limit definition; the route invokes the provider once per row of the
 * GET /api/v1/limits response.
 */
interface LimitUsageProvider {
    suspend fun currentUsage(limit: LimitDefinition): BigDecimal?
}

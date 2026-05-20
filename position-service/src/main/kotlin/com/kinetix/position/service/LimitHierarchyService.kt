package com.kinetix.position.service

import com.kinetix.common.model.DeskId
import com.kinetix.position.client.ReferenceDataServiceClient
import com.kinetix.position.model.LimitBreachSeverity
import com.kinetix.position.model.LimitCheckResult
import com.kinetix.position.model.LimitCheckStatus
import com.kinetix.position.model.LimitDefinition
import com.kinetix.position.model.LimitLevel
import com.kinetix.position.model.LimitType
import com.kinetix.position.persistence.LimitBreachEventWriter
import com.kinetix.position.persistence.LimitDefinitionRepository
import com.kinetix.position.persistence.TemporaryLimitIncreaseRepository
import java.math.BigDecimal
import java.time.Instant

class LimitHierarchyService(
    private val limitDefinitionRepo: LimitDefinitionRepository,
    private val temporaryLimitIncreaseRepo: TemporaryLimitIncreaseRepository,
    private val referenceDataClient: ReferenceDataServiceClient? = null,
    private val warningThresholdPct: Double = 0.8,
    // Optional breach-history sink. When null the service does not persist
    // breaches (keeps existing call sites and unit tests unchanged).
    private val breachEventWriter: LimitBreachEventWriter? = null,
) {
    suspend fun checkLimit(
        entityId: String,
        level: LimitLevel,
        limitType: LimitType,
        currentExposure: BigDecimal,
        intraday: Boolean = true,
        parentEntityIds: Map<LimitLevel, String> = emptyMap(),
        parentExposures: Map<LimitLevel, BigDecimal> = emptyMap(),
        // Book the check relates to, used as `limit_breach_events.book_id`.
        // When null: at BOOK level the entityId IS the book; at other levels
        // the entityId is the closest scoping key and is used as the fallback.
        bookId: String? = null,
    ): LimitCheckResult {
        val now = Instant.now()
        val resolvedBookId = bookId ?: entityId

        val entityLimit = limitDefinitionRepo.findByEntityAndType(entityId, level, limitType)
        if (entityLimit != null) {
            val result = evaluateLimit(entityLimit, currentExposure, intraday, now)
            if (result.status == LimitCheckStatus.BREACHED) {
                return persistOutcome(result, entityId, resolvedBookId, limitType, now)
            }
        }

        val resolvedParents = resolveParentIds(entityId, level, parentEntityIds)
        val hierarchyOrder = parentHierarchyFor(level)

        for (parentLevel in hierarchyOrder) {
            val parentEntityId = resolvedParents[parentLevel]
                ?: if (parentLevel == LimitLevel.FIRM) "FIRM" else continue
            val parentExposure = parentExposures[parentLevel] ?: currentExposure

            val parentLimit = limitDefinitionRepo.findByEntityAndType(parentEntityId, parentLevel, limitType)
            if (parentLimit != null) {
                val parentResult = evaluateLimit(parentLimit, parentExposure, intraday, now)
                if (parentResult.status == LimitCheckStatus.BREACHED) {
                    return persistOutcome(
                        parentResult.copy(breachedAt = parentLevel),
                        entityId,
                        resolvedBookId,
                        limitType,
                        now,
                    )
                }
            }
        }

        if (entityLimit != null) {
            return persistOutcome(
                evaluateLimit(entityLimit, currentExposure, intraday, now),
                entityId,
                resolvedBookId,
                limitType,
                now,
            )
        }

        return LimitCheckResult(
            status = LimitCheckStatus.OK,
            currentExposure = currentExposure,
        )
    }

    /**
     * Persists the side effects of a finished limit check, then returns the
     * result unchanged so callers can `return persistOutcome(...)`. A BREACHED
     * status records a (HARD) breach; an OK status resolves any open breach
     * for the same entity/limit. WARNING is a grey zone — it neither records
     * nor resolves. No-op when no [breachEventWriter] is configured.
     */
    private suspend fun persistOutcome(
        result: LimitCheckResult,
        entityId: String,
        bookId: String,
        limitType: LimitType,
        now: Instant,
    ): LimitCheckResult {
        val writer = breachEventWriter ?: return result
        when (result.status) {
            LimitCheckStatus.BREACHED -> writer.recordBreach(
                entityId = entityId,
                bookId = bookId,
                limitType = limitType.name,
                severity = LimitBreachSeverity.HARD,
                currentValue = result.currentExposure ?: BigDecimal.ZERO,
                limitValue = result.effectiveLimit ?: result.limitValue ?: BigDecimal.ZERO,
                breachedAt = now,
            )

            LimitCheckStatus.OK -> writer.recordResolution(
                entityId = entityId,
                bookId = bookId,
                limitType = limitType.name,
                resolvedAt = now,
            )

            LimitCheckStatus.WARNING -> {} // grey zone — leave any open breach open
        }
        return result
    }

    private suspend fun resolveParentIds(
        entityId: String,
        level: LimitLevel,
        suppliedParents: Map<LimitLevel, String>,
    ): Map<LimitLevel, String> {
        if (referenceDataClient == null) return suppliedParents

        val resolved = suppliedParents.toMutableMap()

        when (level) {
            LimitLevel.BOOK -> {
                // For BOOK level: caller supplies the desk ID. We auto-resolve division from it.
                val deskId = resolved[LimitLevel.DESK] ?: return resolved
                if (!resolved.containsKey(LimitLevel.DIVISION)) {
                    val desk = referenceDataClient.getDeskById(DeskId(deskId))
                    if (desk != null) {
                        resolved[LimitLevel.DIVISION] = desk.divisionId.value
                    }
                }
            }
            LimitLevel.DESK -> {
                // For DESK level: auto-resolve division from the desk's own entityId.
                if (!resolved.containsKey(LimitLevel.DIVISION)) {
                    val desk = referenceDataClient.getDeskById(DeskId(entityId))
                    if (desk != null) {
                        resolved[LimitLevel.DIVISION] = desk.divisionId.value
                    }
                }
            }
            else -> {}
        }

        return resolved
    }

    private fun parentHierarchyFor(level: LimitLevel): List<LimitLevel> = when (level) {
        LimitLevel.BOOK -> listOf(LimitLevel.DESK, LimitLevel.DIVISION, LimitLevel.FIRM)
        LimitLevel.DESK -> listOf(LimitLevel.DIVISION, LimitLevel.FIRM)
        LimitLevel.DIVISION -> listOf(LimitLevel.FIRM)
        LimitLevel.TRADER -> listOf(LimitLevel.DESK, LimitLevel.DIVISION, LimitLevel.FIRM)
        LimitLevel.COUNTERPARTY -> listOf(LimitLevel.FIRM)
        LimitLevel.FIRM -> emptyList()
    }

    private suspend fun evaluateLimit(
        limit: LimitDefinition,
        exposure: BigDecimal,
        intraday: Boolean,
        asOf: Instant,
    ): LimitCheckResult {
        val tempIncrease = temporaryLimitIncreaseRepo.findActiveByLimitId(limit.id, asOf)

        val baseLimit = when {
            intraday && limit.intradayLimit != null -> limit.intradayLimit
            !intraday && limit.overnightLimit != null -> limit.overnightLimit
            else -> limit.limitValue
        }

        val effectiveLimit = tempIncrease?.newValue ?: baseLimit
        val warningThreshold = effectiveLimit.multiply(BigDecimal.valueOf(warningThresholdPct))

        return when {
            exposure > effectiveLimit -> LimitCheckResult(
                status = LimitCheckStatus.BREACHED,
                limitValue = baseLimit,
                effectiveLimit = effectiveLimit,
                currentExposure = exposure,
                breachedAt = limit.level,
                message = "Exposure $exposure exceeds limit $effectiveLimit at ${limit.level} level",
            )

            exposure >= warningThreshold -> LimitCheckResult(
                status = LimitCheckStatus.WARNING,
                limitValue = baseLimit,
                effectiveLimit = effectiveLimit,
                currentExposure = exposure,
                breachedAt = limit.level,
                message = "Exposure $exposure approaching limit $effectiveLimit at ${limit.level} level",
            )

            else -> LimitCheckResult(
                status = LimitCheckStatus.OK,
                limitValue = baseLimit,
                effectiveLimit = effectiveLimit,
                currentExposure = exposure,
            )
        }
    }
}

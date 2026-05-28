package com.kinetix.position.service

import com.kinetix.common.model.BookId
import com.kinetix.common.model.Position
import com.kinetix.position.model.LimitDefinition
import com.kinetix.position.model.LimitLevel
import com.kinetix.position.model.LimitType
import com.kinetix.position.persistence.BookHierarchyRepository
import com.kinetix.position.persistence.PositionRepository
import java.math.BigDecimal

/**
 * Computes current consumed value for a limit from the live position book.
 *
 * Coverage:
 *   * [LimitType.NOTIONAL] — sum of |marketValue| across positions in scope.
 *   * [LimitType.POSITION] — sum of |quantity| across positions in scope.
 *   * Any other [LimitType] (VAR, CONCENTRATION, ADV_CONCENTRATION,
 *     VAR_BUDGET) — returns `null`. Those metrics live in risk-orchestrator
 *     / risk-engine output and aren't reachable from this service without
 *     a new cross-service contract; the limits route surfaces them as
 *     `current: null, utilisationPct: null` so the UI renders an em-dash
 *     rather than a misleading "$0 (0%)" figure.
 *
 * Scope resolution by [LimitLevel]:
 *   * [LimitLevel.FIRM] — every position in the book.
 *   * [LimitLevel.DESK] — every position under a book mapped to that desk
 *     via [BookHierarchyRepository].
 *   * [LimitLevel.BOOK] — positions for the single book whose id equals
 *     `entityId`.
 *   * [LimitLevel.DIVISION] / [LimitLevel.TRADER] / [LimitLevel.COUNTERPARTY]
 *     — `null`. Division → desk mapping lives in reference-data-service and
 *     we don't want a cross-service hop on every limits page render; trader
 *     and counterparty attribution isn't held on the position record. The
 *     route surfaces them as `current: null, utilisationPct: null` so the
 *     UI shows an em-dash rather than a misleading "$0 (0%)".
 */
class PositionBasedLimitUsageProvider(
    private val positionRepository: PositionRepository,
    private val bookHierarchyRepository: BookHierarchyRepository,
) : LimitUsageProvider {

    override suspend fun currentUsage(limit: LimitDefinition): BigDecimal? {
        val measure = measureFor(limit.limitType) ?: return null
        val positions = positionsInScope(limit) ?: return null
        if (positions.isEmpty()) return BigDecimal.ZERO
        return positions.fold(BigDecimal.ZERO) { acc, position -> acc + measure(position) }
    }

    private fun measureFor(limitType: LimitType): ((Position) -> BigDecimal)? = when (limitType) {
        LimitType.NOTIONAL -> { position -> position.marketValue.amount.abs() }
        LimitType.POSITION -> { position -> position.quantity.abs() }
        // VaR-style limits aren't computable from positions alone — return null.
        LimitType.VAR,
        LimitType.VAR_BUDGET,
        LimitType.CONCENTRATION,
        LimitType.ADV_CONCENTRATION,
        -> null
    }

    private suspend fun positionsInScope(limit: LimitDefinition): List<Position>? = when (limit.level) {
        LimitLevel.FIRM -> allPositions()
        LimitLevel.DESK -> {
            val bookIds = bookHierarchyRepository.findByDeskId(limit.entityId).map { it.bookId }
            collectPositions(bookIds)
        }
        LimitLevel.BOOK -> positionRepository.findByBookId(BookId(limit.entityId))
        LimitLevel.DIVISION, LimitLevel.TRADER, LimitLevel.COUNTERPARTY -> null
    }

    private suspend fun allPositions(): List<Position> {
        val bookIds = positionRepository.findDistinctBookIds()
        if (bookIds.isEmpty()) return emptyList()
        return bookIds.flatMap { positionRepository.findByBookId(it) }
    }

    private suspend fun collectPositions(bookIds: List<String>): List<Position> {
        if (bookIds.isEmpty()) return emptyList()
        return bookIds.flatMap { positionRepository.findByBookId(BookId(it)) }
    }
}

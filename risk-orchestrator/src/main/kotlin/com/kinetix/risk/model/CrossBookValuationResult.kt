package com.kinetix.risk.model

import com.kinetix.common.model.BookId
import java.time.Instant
import java.util.UUID

data class CrossBookValuationResult(
    val portfolioGroupId: String,
    val bookIds: List<BookId>,
    val calculationType: CalculationType,
    val confidenceLevel: ConfidenceLevel,
    val varValue: Double,
    val expectedShortfall: Double,
    val componentBreakdown: List<ComponentBreakdown>,
    val bookContributions: List<BookVaRContribution>,
    val totalStandaloneVar: Double,
    val diversificationBenefit: Double,
    val calculatedAt: Instant,
    val modelVersion: String? = null,
    val monteCarloSeed: Long = 0,
    val jobId: UUID? = null,
    /**
     * Firm-level aggregate greeks: the asset-class sensitivities produced by the
     * risk engine when it values the merged position set in a single call. Null
     * when greeks were not requested or the engine returned none.
     */
    val greeks: GreeksResult? = null,
)

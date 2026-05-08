package com.kinetix.risk.model

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class ValuationJob(
    val jobId: UUID,
    val bookId: String,
    val triggerType: TriggerType,
    val status: RunStatus,
    val startedAt: Instant,
    /** UTC business date for which positions and market data are current. */
    val valuationDate: LocalDate,
    val completedAt: Instant? = null,
    val durationMs: Long? = null,
    val calculationType: String? = null,
    val confidenceLevel: String? = null,
    val timeHorizonDays: Int? = null,
    val varValue: Double? = null,
    val expectedShortfall: Double? = null,
    val pvValue: Double? = null,
    val delta: Double? = null,
    val gamma: Double? = null,
    val vega: Double? = null,
    val theta: Double? = null,
    val rho: Double? = null,
    val positionRiskSnapshot: List<PositionRisk> = emptyList(),
    val componentBreakdownSnapshot: List<ComponentBreakdown> = emptyList(),
    val computedOutputsSnapshot: Set<ValuationOutput> = emptySet(),
    val assetClassGreeksSnapshot: List<GreekValues> = emptyList(),
    val phases: List<JobPhase> = emptyList(),
    val currentPhase: JobPhaseName? = null,
    val error: String? = null,
    val triggeredBy: String,
    val runLabel: RunLabel? = null,
    val promotedAt: Instant? = null,
    val promotedBy: String? = null,
    /** Opaque identifier for the market data snapshot used in this calculation, enabling reproducibility. */
    val marketDataSnapshotId: String? = null,
    val manifestId: UUID? = null,
    /**
     * The calculation type as originally requested, before any regime override was applied.
     * Null when no regime override was active — in that case [calculationType] is the requested value.
     * Together with [calculationType] (the effective value) this satisfies the AuditBothParameters invariant.
     */
    val requestedCalculationType: String? = null,
    /** The confidence level as originally requested, before any regime override. */
    val requestedConfidenceLevel: String? = null,
    /** The time horizon (in days) as originally requested, before any regime override. */
    val requestedTimeHorizonDays: Int? = null,
    /** Correlation id from the originating CalculateVaRRequested trigger; propagated end-to-end for traceability. */
    val correlationId: String? = null,
)

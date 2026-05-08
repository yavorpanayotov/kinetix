package com.kinetix.risk.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.date
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone
import org.jetbrains.exposed.sql.json.jsonb
import kotlinx.serialization.json.Json

object ValuationJobsTable : Table("valuation_jobs") {
    val jobId = uuid("job_id")
    val bookId = varchar("book_id", 255)
    val triggerType = varchar("trigger_type", 50)
    val status = varchar("status", 20)
    val valuationDate = date("valuation_date")
    val startedAt = timestampWithTimeZone("started_at")
    val completedAt = timestampWithTimeZone("completed_at").nullable()
    val durationMs = long("duration_ms").nullable()
    val calculationType = varchar("calculation_type", 50).nullable()
    val confidenceLevel = varchar("confidence_level", 10).nullable()
    val varValue = double("var_value").nullable()
    val expectedShortfall = double("expected_shortfall").nullable()
    val pvValue = double("pv_value").nullable()
    val delta = double("delta").nullable()
    val gamma = double("gamma").nullable()
    val vega = double("vega").nullable()
    val theta = double("theta").nullable()
    val rho = double("rho").nullable()
    val positionRisk = jsonb<List<PositionRiskJson>>("position_risk", Json).nullable()
    val componentBreakdown = jsonb<List<ComponentBreakdownJson>>("component_breakdown", Json).nullable()
    val computedOutputs = jsonb<List<String>>("computed_outputs", Json).nullable()
    val assetClassGreeks = jsonb<List<AssetClassGreeksJson>>("asset_class_greeks", Json).nullable()
    val phases = jsonb<List<JobPhaseJson>>("phases", Json)
    val currentPhase = varchar("current_phase", 50).nullable()
    val error = text("error").nullable()
    val triggeredBy = varchar("triggered_by", 255)
    val runLabel = varchar("run_label", 20).nullable()
    val promotedAt = timestampWithTimeZone("promoted_at").nullable()
    val promotedBy = varchar("promoted_by", 255).nullable()
    val marketDataSnapshotId = varchar("market_data_snapshot_id", 255).nullable()
    val manifestId = uuid("manifest_id").nullable()
    val timeHorizonDays = integer("time_horizon_days").nullable()
    val requestedCalculationType = varchar("requested_calculation_type", 50).nullable()
    val requestedConfidenceLevel = varchar("requested_confidence_level", 10).nullable()
    val requestedTimeHorizonDays = integer("requested_time_horizon_days").nullable()
    val correlationId = varchar("correlation_id", 255).nullable()

    override val primaryKey = PrimaryKey(jobId)
}

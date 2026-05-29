package com.kinetix.gateway.client

import java.time.Instant

data class VaRCalculationParams(
    val bookId: String,
    val calculationType: String,
    val confidenceLevel: String,
    val timeHorizonDays: Int,
    val numSimulations: Int,
    val requestedOutputs: List<String>? = null,
)

data class ComponentBreakdownItem(
    val assetClass: String,
    val varContribution: Double,
    val percentageOfTotal: Double,
)

data class ValuationResultSummary(
    val bookId: String,
    val calculationType: String,
    val confidenceLevel: String,
    val varValue: Double,
    val expectedShortfall: Double,
    val componentBreakdown: List<ComponentBreakdownItem>,
    val calculatedAt: Instant,
    val greeks: GreeksResultSummary? = null,
    val pvValue: Double? = null,
    val valuationDate: String? = null,
    val positionGreeks: List<PositionGreekSummary> = emptyList(),
)

data class StressTestParams(
    val bookId: String,
    val scenarioName: String,
    val calculationType: String,
    val confidenceLevel: String,
    val timeHorizonDays: Int,
    val volShocks: Map<String, Double>?,
    val priceShocks: Map<String, Double>?,
    val description: String?,
)

data class AssetClassImpactItem(
    val assetClass: String,
    val baseExposure: Double,
    val stressedExposure: Double,
    val pnlImpact: Double,
)

data class PositionStressImpactItem(
    val instrumentId: String,
    val assetClass: String,
    val baseMarketValue: Double,
    val stressedMarketValue: Double,
    val pnlImpact: Double,
    val percentageOfTotal: Double,
)

data class StressLimitBreachItem(
    val limitType: String,
    val limitLevel: String,
    val limitValue: String,
    val stressedValue: String,
    val breachSeverity: String,
    val scenarioName: String,
)

data class StressTestResultSummary(
    val scenarioName: String,
    val baseVar: Double,
    val stressedVar: Double,
    val pnlImpact: Double,
    val assetClassImpacts: List<AssetClassImpactItem>,
    val calculatedAt: Instant,
    val positionImpacts: List<PositionStressImpactItem> = emptyList(),
    val limitBreaches: List<StressLimitBreachItem> = emptyList(),
)

data class StressTestBatchParams(
    val bookId: String,
    val scenarioNames: List<String>,
    val calculationType: String,
    val confidenceLevel: String,
    val timeHorizonDays: Int,
)

data class BatchScenarioResultItem(
    val scenarioName: String,
    val baseVar: String,
    val stressedVar: String,
    val pnlImpact: String,
)

data class BatchScenarioFailureItem(
    val scenarioName: String,
    val errorMessage: String,
)

data class BatchStressRunSummary(
    val results: List<BatchScenarioResultItem>,
    val failedScenarios: List<BatchScenarioFailureItem>,
    val worstScenarioName: String?,
    val worstPnlImpact: String?,
)

data class GreekValuesItem(
    val assetClass: String,
    val delta: Double,
    val gamma: Double,
    val vega: Double,
)

data class GreeksResultSummary(
    val bookId: String,
    val assetClassGreeks: List<GreekValuesItem>,
    val theta: Double,
    val rho: Double,
    val calculatedAt: Instant,
)

data class PositionGreekSummary(
    val instrumentId: String,
    val delta: Double,
    val gamma: Double,
    val vega: Double,
    val theta: Double,
    val rho: Double,
)

data class RiskClassChargeItem(
    val riskClass: String,
    val deltaCharge: Double,
    val vegaCharge: Double,
    val curvatureCharge: Double,
    val totalCharge: Double,
)

data class FrtbResultSummary(
    val bookId: String,
    val sbmCharges: List<RiskClassChargeItem>,
    val totalSbmCharge: Double,
    val grossJtd: Double,
    val hedgeBenefit: Double,
    val netDrc: Double,
    val exoticNotional: Double,
    val otherNotional: Double,
    val totalRrao: Double,
    val totalCapitalCharge: Double,
    val calculatedAt: Instant,
)

data class ReportResult(
    val bookId: String,
    val format: String,
    val content: String,
    val generatedAt: Instant,
)

data class DependenciesParams(
    val bookId: String,
    val calculationType: String,
    val confidenceLevel: String,
)

data class MarketDataDependencyItem(
    val dataType: String,
    val instrumentId: String,
    val assetClass: String,
    val required: Boolean,
    val description: String,
    val parameters: Map<String, String>,
)

data class DataDependenciesSummary(
    val bookId: String,
    val dependencies: List<MarketDataDependencyItem>,
)

data class JobPhaseItem(
    val name: String,
    val status: String,
    val startedAt: Instant,
    val completedAt: Instant?,
    val durationMs: Long?,
    val details: Map<String, String>,
    val error: String?,
)

data class ValuationJobSummaryItem(
    val jobId: String,
    val bookId: String,
    val triggerType: String,
    val status: String,
    val startedAt: Instant,
    val completedAt: Instant?,
    val durationMs: Long?,
    val calculationType: String?,
    val confidenceLevel: String? = null,
    val varValue: Double?,
    val expectedShortfall: Double?,
    val pvValue: Double?,
    val delta: Double?,
    val gamma: Double?,
    val vega: Double?,
    val theta: Double?,
    val rho: Double?,
    val valuationDate: String? = null,
    val runLabel: String? = null,
    val promotedAt: String? = null,
    val promotedBy: String? = null,
    val currentPhase: String? = null,
    val manifestId: String? = null,
)

data class ValuationJobDetailItem(
    val jobId: String,
    val bookId: String,
    val triggerType: String,
    val status: String,
    val startedAt: Instant,
    val completedAt: Instant?,
    val durationMs: Long?,
    val calculationType: String?,
    val confidenceLevel: String?,
    val varValue: Double?,
    val expectedShortfall: Double?,
    val pvValue: Double?,
    val phases: List<JobPhaseItem>,
    val error: String?,
    val valuationDate: String? = null,
    val runLabel: String? = null,
    val promotedAt: String? = null,
    val promotedBy: String? = null,
    val currentPhase: String? = null,
    val manifestId: String? = null,
)

data class EodTimelineEntryItem(
    val valuationDate: String,
    val jobId: String,
    val varValue: Double?,
    val expectedShortfall: Double?,
    val pvValue: Double?,
    val delta: Double?,
    val gamma: Double?,
    val vega: Double?,
    val theta: Double?,
    val rho: Double?,
    val promotedAt: String?,
    val promotedBy: String?,
    val varChange: Double?,
    val varChangePct: Double?,
    val esChange: Double?,
    val calculationType: String?,
    val confidenceLevel: Double?,
)

data class EodTimelineSummary(
    val bookId: String,
    val from: String,
    val to: String,
    val entries: List<EodTimelineEntryItem>,
)

data class SodBaselineStatusSummary(
    val exists: Boolean,
    val baselineDate: String?,
    val snapshotType: String?,
    val createdAt: String?,
    val sourceJobId: String?,
    val calculationType: String?,
)

data class PositionPnlAttributionSummary(
    val instrumentId: String,
    val assetClass: String,
    val totalPnl: String,
    val deltaPnl: String,
    val gammaPnl: String,
    val vegaPnl: String,
    val thetaPnl: String,
    val rhoPnl: String,
    val unexplainedPnl: String,
)

data class PnlAttributionSummary(
    val bookId: String,
    val date: String,
    val totalPnl: String,
    val deltaPnl: String,
    val gammaPnl: String,
    val vegaPnl: String,
    val thetaPnl: String,
    val rhoPnl: String,
    val unexplainedPnl: String,
    val positionAttributions: List<PositionPnlAttributionSummary>,
    val calculatedAt: String,
)

data class MarginEstimateSummary(
    val initialMargin: String,
    val variationMargin: String,
    val totalMargin: String,
    val currency: String,
)

data class HypotheticalTradeParam(
    val instrumentId: String,
    val assetClass: String,
    val side: String,
    val quantity: String,
    val priceAmount: String,
    val priceCurrency: String,
    val instrumentType: String,
)

data class WhatIfRequestParams(
    val bookId: String,
    val hypotheticalTrades: List<HypotheticalTradeParam>,
    val calculationType: String? = null,
    val confidenceLevel: String? = null,
)

data class PositionRiskSummaryItem(
    val instrumentId: String,
    val assetClass: String,
    val marketValue: String,
    val delta: String?,
    val gamma: String?,
    val vega: String?,
    // Per-instrument Theta / Rho (trader-review P0 #2). Null when the
    // upstream did not return them (e.g. a pre-fix orchestrator on the
    // network); otherwise carries the per-position value formatted to 6
    // decimal places.
    val theta: String? = null,
    val rho: String? = null,
    // DV01 (dollar value of a 1bp parallel rates shift). Non-null only
    // for rates instruments; cash equity / FX surface "0.000000" rather
    // than null so the UI can distinguish "computed and zero" from
    // "missing".
    val dv01: String? = null,
    val varContribution: String,
    val esContribution: String,
    val percentageOfTotal: String,
)

data class ChartDataPointItem(
    val bucket: String,
    val varValue: Double?,
    val expectedShortfall: Double?,
    val confidenceLevel: String?,
    val delta: Double?,
    val gamma: Double?,
    val vega: Double?,
    val theta: Double?,
    val rho: Double?,
    val pvValue: Double?,
    val jobCount: Int,
    val completedCount: Int,
    val failedCount: Int,
    val runningCount: Int,
)

data class ChartDataSummary(
    val points: List<ChartDataPointItem>,
    val bucketSizeMs: Long,
)

data class BookVaRContributionSummary(
    val bookId: String,
    val varContribution: Double,
    val percentageOfTotal: Double,
    val standaloneVar: Double,
    val diversificationBenefit: Double,
    val marginalVar: Double = 0.0,
    val incrementalVar: Double = 0.0,
)

data class StressedCrossBookVaRResultSummary(
    val baseVaR: String,
    val stressedVaR: String,
    val baseDiversificationBenefit: String,
    val stressedDiversificationBenefit: String,
    val benefitErosion: String,
    val benefitErosionPct: String,
    val stressCorrelation: String,
)

data class InstrumentDailyReturnsParam(
    val instrumentId: String,
    val dailyReturns: List<Double>,
)

data class HistoricalReplayParams(
    val bookId: String,
    val instrumentReturns: List<InstrumentDailyReturnsParam> = emptyList(),
    val scenarioName: String? = null,
    val windowStart: String? = null,
    val windowEnd: String? = null,
)

data class PositionReplayImpactSummary(
    val instrumentId: String,
    val assetClass: String,
    val marketValue: String,
    val pnlImpact: String,
    val dailyPnl: List<String>,
    val proxyUsed: Boolean,
)

data class HistoricalReplayResultSummary(
    val scenarioName: String,
    val totalPnlImpact: String,
    val positionImpacts: List<PositionReplayImpactSummary>,
    val windowStart: String?,
    val windowEnd: String?,
    val calculatedAt: String,
)

data class ReverseStressParams(
    val bookId: String,
    val targetLoss: Double,
    val maxShock: Double = -1.0,
)

data class InstrumentShockSummary(
    val instrumentId: String,
    val shock: String,
)

data class ReverseStressResultSummary(
    val shocks: List<InstrumentShockSummary>,
    val achievedLoss: String,
    val targetLoss: String,
    val converged: Boolean,
    val calculatedAt: String,
)

data class CrossBookVaRResultSummary(
    val portfolioGroupId: String,
    val bookIds: List<String>,
    val calculationType: String,
    val confidenceLevel: String,
    val varValue: Double,
    val expectedShortfall: Double,
    val componentBreakdown: List<ComponentBreakdownItem>,
    val bookContributions: List<BookVaRContributionSummary>,
    val totalStandaloneVar: Double,
    val diversificationBenefit: Double,
    val calculatedAt: Instant,
)

data class WhatIfResultSummary(
    val baseVaR: String,
    val baseExpectedShortfall: String,
    val baseGreeks: GreeksResultSummary?,
    val basePositionRisk: List<PositionRiskSummaryItem>,
    val hypotheticalVaR: String,
    val hypotheticalExpectedShortfall: String,
    val hypotheticalGreeks: GreeksResultSummary?,
    val hypotheticalPositionRisk: List<PositionRiskSummaryItem>,
    val varChange: String,
    val esChange: String,
    val calculatedAt: String,
)

data class GreeksChangeSummary(
    val deltaChange: String,
    val gammaChange: String,
    val vegaChange: String,
    val thetaChange: String,
    val rhoChange: String,
)

data class TradeVarContributionSummary(
    val instrumentId: String,
    val side: String,
    val quantity: String,
    val marginalVarImpact: String,
    val executionCost: String,
)

data class RebalancingTradeParam(
    val instrumentId: String,
    val assetClass: String,
    val side: String,
    val quantity: String,
    val priceAmount: String,
    val priceCurrency: String,
    val instrumentType: String,
    val bidAskSpreadBps: Double = 5.0,
)

data class RebalancingRequestParams(
    val bookId: String,
    val trades: List<RebalancingTradeParam>,
    val calculationType: String? = null,
    val confidenceLevel: String? = null,
)

data class RebalancingWhatIfResultSummary(
    val baseVar: String,
    val rebalancedVar: String,
    val varChange: String,
    val varChangePct: String,
    val baseExpectedShortfall: String,
    val rebalancedExpectedShortfall: String,
    val esChange: String,
    val baseGreeks: GreeksResultSummary?,
    val rebalancedGreeks: GreeksResultSummary?,
    val greeksChange: GreeksChangeSummary,
    val tradeContributions: List<TradeVarContributionSummary>,
    val estimatedExecutionCost: String,
    val calculatedAt: String,
)

interface RiskServiceClient {
    suspend fun getMarginEstimate(bookId: String, previousMTM: String? = null): MarginEstimateSummary?
    suspend fun calculateVaR(params: VaRCalculationParams): ValuationResultSummary?
    suspend fun getLatestVaR(bookId: String, valuationDate: String? = null): ValuationResultSummary?
    suspend fun runStressTest(params: StressTestParams): StressTestResultSummary?
    suspend fun runBatchStressTest(params: StressTestBatchParams): BatchStressRunSummary
    suspend fun listScenarios(): List<String>
    suspend fun calculateGreeks(params: VaRCalculationParams): GreeksResultSummary?
    suspend fun calculateFrtb(bookId: String): FrtbResultSummary?
    suspend fun generateReport(bookId: String, format: String): ReportResult?
    suspend fun discoverDependencies(params: DependenciesParams): DataDependenciesSummary?
    suspend fun listValuationJobs(bookId: String, limit: Int = 20, offset: Int = 0, from: Instant? = null, to: Instant? = null, valuationDate: String? = null): Pair<List<ValuationJobSummaryItem>, Long>
    suspend fun getValuationJobDetail(jobId: String): ValuationJobDetailItem?
    suspend fun getSodBaselineStatus(bookId: String): SodBaselineStatusSummary?
    suspend fun createSodSnapshot(bookId: String, jobId: String? = null): SodBaselineStatusSummary
    suspend fun resetSodBaseline(bookId: String)
    suspend fun computePnlAttribution(bookId: String): PnlAttributionSummary
    suspend fun getPnlAttribution(bookId: String, date: String? = null): PnlAttributionSummary?
    suspend fun runWhatIf(params: WhatIfRequestParams): WhatIfResultSummary
    suspend fun runRebalancing(params: RebalancingRequestParams): RebalancingWhatIfResultSummary
    suspend fun getPositionRisk(bookId: String, valuationDate: String? = null): List<PositionRiskSummaryItem>?
    suspend fun compareRuns(bookId: String, baseJobId: String, targetJobId: String): kotlinx.serialization.json.JsonObject
    suspend fun compareDayOverDay(bookId: String, targetDate: String?, baseDate: String?): kotlinx.serialization.json.JsonObject?
    suspend fun compareDayOverDayAttribution(bookId: String, targetDate: String?, baseDate: String?): kotlinx.serialization.json.JsonObject
    suspend fun compareModel(bookId: String, request: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject
    suspend fun promoteJobLabel(jobId: String, body: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject
    suspend fun getOfficialEod(bookId: String, date: String): kotlinx.serialization.json.JsonObject?
    suspend fun getMarketDataQuantDiff(bookId: String, dataType: String, instrumentId: String, baseManifestId: String, targetManifestId: String): kotlinx.serialization.json.JsonObject?
    suspend fun getEodTimeline(bookId: String, from: String, to: String): EodTimelineSummary?
    suspend fun getChartData(bookId: String, from: Instant, to: Instant): ChartDataSummary
    suspend fun calculateCrossBookVaR(params: com.kinetix.gateway.dtos.CrossBookVaRCalculationParams): CrossBookVaRResultSummary?
    suspend fun getCrossBookVaR(groupId: String): CrossBookVaRResultSummary?
    suspend fun calculateStressedCrossBookVaR(params: com.kinetix.gateway.dtos.StressedCrossBookVaRParams): StressedCrossBookVaRResultSummary?
    suspend fun getIntradayPnl(bookId: String, from: String, to: String): kotlinx.serialization.json.JsonObject?
    suspend fun calculateLiquidityRisk(bookId: String, baseVar: Double): kotlinx.serialization.json.JsonObject?
    suspend fun getLatestLiquidityRisk(bookId: String): kotlinx.serialization.json.JsonObject?
    suspend fun getLiquidityRiskHistory(bookId: String, limit: Int = 100): kotlinx.serialization.json.JsonArray
    suspend fun runHistoricalReplay(params: HistoricalReplayParams): HistoricalReplayResultSummary
    suspend fun runReverseStress(params: ReverseStressParams): ReverseStressResultSummary
    suspend fun getLatestFactorRisk(bookId: String): kotlinx.serialization.json.JsonObject?
    suspend fun getFactorRiskHistory(bookId: String, limit: Int = 100): kotlinx.serialization.json.JsonArray
    suspend fun getHierarchyRisk(level: String, entityId: String): kotlinx.serialization.json.JsonObject?
    suspend fun getRiskBudgets(level: String? = null, entityId: String? = null): kotlinx.serialization.json.JsonArray
    suspend fun getRiskBudget(id: String): kotlinx.serialization.json.JsonObject?
    suspend fun createRiskBudget(body: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject
    suspend fun deleteRiskBudget(id: String): Boolean
    suspend fun triggerCroReport(): kotlinx.serialization.json.JsonObject?
    suspend fun getCurrentRegime(): kotlinx.serialization.json.JsonObject
    suspend fun getRegimeHistory(limit: Int = 50): kotlinx.serialization.json.JsonObject
    suspend fun suggestHedge(bookId: String, body: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject
    suspend fun getLatestHedgeRecommendations(bookId: String, limit: Int = 10): kotlinx.serialization.json.JsonArray
    suspend fun getHedgeRecommendation(bookId: String, id: String): kotlinx.serialization.json.JsonObject?
    suspend fun acceptHedgeRecommendation(bookId: String, id: String, body: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject?
    suspend fun rejectHedgeRecommendation(bookId: String, id: String): kotlinx.serialization.json.JsonObject?
    suspend fun getAllCounterpartyExposures(): kotlinx.serialization.json.JsonArray
    suspend fun getCounterpartyExposure(counterpartyId: String): kotlinx.serialization.json.JsonObject?
    suspend fun getCounterpartyExposureHistory(counterpartyId: String, limit: Int = 90): kotlinx.serialization.json.JsonArray
    suspend fun computeCounterpartyPFE(counterpartyId: String, body: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject?
    suspend fun computeCounterpartyCVA(counterpartyId: String): kotlinx.serialization.json.JsonObject?
    suspend fun getKeyRateDurations(bookId: String): kotlinx.serialization.json.JsonObject?
    suspend fun getIntradayVaRTimeline(bookId: String, from: String, to: String): kotlinx.serialization.json.JsonObject?
    // SA-CCR (BCBS 279) — regulatory capital model, distinct from MC PFE
    suspend fun getCounterpartySaCcr(counterpartyId: String, collateral: Double = 0.0): kotlinx.serialization.json.JsonObject?
    suspend fun listReportTemplates(): kotlinx.serialization.json.JsonArray
    suspend fun generateReport(body: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject
    suspend fun getReportOutput(outputId: String): kotlinx.serialization.json.JsonObject?
    suspend fun getReportOutputCsv(outputId: String): String?
    suspend fun getRecentReports(limit: Int? = null): kotlinx.serialization.json.JsonArray
    suspend fun getBrinsonAttribution(bookId: String, benchmarkId: String, asOfDate: String?): kotlinx.serialization.json.JsonObject?

    // kx-wxy — canned stress-scenario tile (Risk overview). Proxies through to
    // risk-orchestrator's POST/GET …/canned endpoints. Body is a minimal
    // {bookId, scenario, deltaPv, asOf} JSON payload.
    suspend fun runCannedStressScenario(bookId: String, scenarioName: String): kotlinx.serialization.json.JsonObject?
    suspend fun getCannedStressScenario(bookId: String): kotlinx.serialization.json.JsonObject?
}

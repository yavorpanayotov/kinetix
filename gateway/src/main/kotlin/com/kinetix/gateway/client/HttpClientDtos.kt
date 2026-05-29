package com.kinetix.gateway.client

import com.kinetix.common.model.*
import com.kinetix.common.model.instrument.InstrumentTypeCode
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

// --- Position Service DTOs ---

@Serializable
data class BookSummaryDto(val bookId: String)

@Serializable
data class CurrencyExposureDto(
    val currency: String,
    val localValue: MoneyDto,
    val baseValue: MoneyDto,
    val fxRate: String,
)

@Serializable
data class PortfolioAggregationDto(
    val bookId: String,
    val baseCurrency: String,
    val totalNav: MoneyDto,
    val totalUnrealizedPnl: MoneyDto,
    val currencyBreakdown: List<CurrencyExposureDto>,
)

@Serializable
data class MoneyDto(val amount: String, val currency: String)

@Serializable
data class PositionDto(
    val bookId: String,
    val instrumentId: String,
    val assetClass: String,
    val quantity: String,
    val averageCost: MoneyDto,
    val marketPrice: MoneyDto,
    val marketValue: MoneyDto,
    val unrealizedPnl: MoneyDto,
    val instrumentType: String,
    val strategyId: String? = null,
    val strategyType: String? = null,
    val strategyName: String? = null,
)

@Serializable
data class TradeDto(
    val tradeId: String,
    val bookId: String,
    val instrumentId: String,
    val assetClass: String,
    val side: String,
    val quantity: String,
    val price: MoneyDto,
    val tradedAt: String,
    val instrumentType: String,
    // The Counterparty Exposure tile on the Risk view aggregates trades by
    // counterpartyId. Dropping it here used to leave the tile permanently
    // empty even though the position-service was returning the data.
    val counterpartyId: String? = null,
    // Lifecycle status of the booked record (LIVE / AMENDED / CANCELLED).
    // Trader-review P2 §21: the blotter projects this into a FIX-style fill
    // state. Optional on the wire because some upstream paths predate the
    // field — the default `LIVE` matches the position-service default.
    val status: String? = null,
    // Trader-review P2 §21: real fill state surfaced by trades reconciled
    // from working orders. When present, the gateway forwards these
    // verbatim; when absent, the gateway derives FILLED/CANCELLED from
    // [status].
    val fillStatus: String? = null,
    val qtyFilled: String? = null,
    val qtyOpen: String? = null,
)

@Serializable
data class BookTradeResponseDto(
    val trade: TradeDto,
    val position: PositionDto,
)

@Serializable
data class TradeHistoryPageDto(
    val items: List<TradeDto>,
    val total: Long,
    val offset: Long,
    val limit: Int,
    val hasMore: Boolean,
)

@Serializable
data class BookTradeRequestDto(
    val tradeId: String,
    val instrumentId: String,
    val assetClass: String,
    val side: String,
    val quantity: String,
    val priceAmount: String,
    val priceCurrency: String,
    val tradedAt: String,
    val instrumentType: String,
    val userId: String? = null,
    val userRole: String? = null,
)

// --- Instrument Service DTOs ---

@Serializable
data class InstrumentDto(
    val instrumentId: String,
    val instrumentType: String,
    val displayName: String,
)

// --- Price Service DTOs ---

@Serializable
data class PricePointDto(
    val instrumentId: String,
    val price: MoneyDto,
    val timestamp: String,
    val source: String,
)

// --- Risk Service DTOs ---

@Serializable
data class VaRCalculationRequestDto(
    val calculationType: String? = null,
    val confidenceLevel: String? = null,
    val timeHorizonDays: String? = null,
    val numSimulations: String? = null,
    val requestedOutputs: List<String>? = null,
)

@Serializable
data class ComponentBreakdownDto(
    val assetClass: String,
    val varContribution: String,
    val percentageOfTotal: String,
)

@Serializable
data class PositionGreekClientDto(
    val instrumentId: String,
    val delta: String,
    val gamma: String,
    val vega: String,
    val theta: String,
    val rho: String,
)

@Serializable
data class ValuationResultDto(
    val bookId: String,
    val calculationType: String,
    val confidenceLevel: String,
    val varValue: String,
    val expectedShortfall: String,
    val componentBreakdown: List<ComponentBreakdownDto>,
    val calculatedAt: String,
    val greeks: GreeksResultDto? = null,
    val computedOutputs: List<String>? = null,
    val pvValue: String? = null,
    val valuationDate: String? = null,
    // risk-orchestrator emits `null` (not an empty array) when no per-position
    // greeks are computed — see `RiskMappers.toDto`'s `takeIf { isNotEmpty() }`.
    // Accept that on the wire and normalise to an empty list at the domain edge.
    val positionGreeks: List<PositionGreekClientDto>? = null,
)

@Serializable
data class StressTestRequestDto(
    val scenarioName: String,
    val calculationType: String? = null,
    val confidenceLevel: String? = null,
    val timeHorizonDays: String? = null,
    val volShocks: Map<String, Double>? = null,
    val priceShocks: Map<String, Double>? = null,
    val description: String? = null,
)

@Serializable
data class AssetClassImpactDto(
    val assetClass: String,
    val baseExposure: String,
    val stressedExposure: String,
    val pnlImpact: String,
)

@Serializable
data class PositionStressImpactDto(
    val instrumentId: String,
    val assetClass: String,
    val baseMarketValue: String,
    val stressedMarketValue: String,
    val pnlImpact: String,
    val percentageOfTotal: String,
)

@Serializable
data class StressLimitBreachDto(
    val limitType: String,
    val limitLevel: String,
    val limitValue: String,
    val stressedValue: String,
    val breachSeverity: String,
    val scenarioName: String,
)

@Serializable
data class StressTestResultDto(
    val scenarioName: String,
    val baseVar: String,
    val stressedVar: String,
    val pnlImpact: String,
    val assetClassImpacts: List<AssetClassImpactDto>,
    val calculatedAt: String,
    val positionImpacts: List<PositionStressImpactDto> = emptyList(),
    val limitBreaches: List<StressLimitBreachDto> = emptyList(),
)

@Serializable
data class StressTestBatchRequestDto(
    val scenarioNames: List<String>,
    val calculationType: String? = null,
    val confidenceLevel: String? = null,
    val timeHorizonDays: String? = null,
)

@Serializable
data class BatchScenarioResultClientDto(
    val scenarioName: String,
    val baseVar: String,
    val stressedVar: String,
    val pnlImpact: String,
)

@Serializable
data class BatchScenarioFailureClientDto(
    val scenarioName: String,
    val errorMessage: String,
)

@Serializable
data class BatchStressRunResultClientDto(
    val results: List<BatchScenarioResultClientDto>,
    val failedScenarios: List<BatchScenarioFailureClientDto>,
    val worstScenarioName: String? = null,
    val worstPnlImpact: String? = null,
) {
    fun toDomain() = BatchStressRunSummary(
        results = results.map { BatchScenarioResultItem(it.scenarioName, it.baseVar, it.stressedVar, it.pnlImpact) },
        failedScenarios = failedScenarios.map { BatchScenarioFailureItem(it.scenarioName, it.errorMessage) },
        worstScenarioName = worstScenarioName,
        worstPnlImpact = worstPnlImpact,
    )
}

@Serializable
data class GreekValuesDto(
    val assetClass: String,
    val delta: String,
    val gamma: String,
    val vega: String,
)

@Serializable
data class GreeksResultDto(
    val bookId: String,
    val assetClassGreeks: List<GreekValuesDto>,
    val theta: String,
    val rho: String,
    val calculatedAt: String,
)

@Serializable
data class RiskClassChargeDto(
    val riskClass: String,
    val deltaCharge: String,
    val vegaCharge: String,
    val curvatureCharge: String,
    val totalCharge: String,
)

@Serializable
data class FrtbResultDto(
    val bookId: String,
    val sbmCharges: List<RiskClassChargeDto>,
    val totalSbmCharge: String,
    val grossJtd: String,
    val hedgeBenefit: String,
    val netDrc: String,
    val exoticNotional: String,
    val otherNotional: String,
    val totalRrao: String,
    val totalCapitalCharge: String,
    val calculatedAt: String,
)

@Serializable
data class GenerateReportRequestDto(val format: String? = null)

@Serializable
data class ReportResultDto(
    val bookId: String,
    val format: String,
    val content: String,
    val generatedAt: String,
)

// --- Dependencies DTOs ---

@Serializable
data class DependenciesRequestDto(
    val calculationType: String? = null,
    val confidenceLevel: String? = null,
)

@Serializable
data class MarketDataDependencyItemDto(
    val dataType: String,
    val instrumentId: String,
    val assetClass: String,
    val required: Boolean,
    val description: String,
    val parameters: Map<String, String>,
)

@Serializable
data class DataDependenciesDto(
    val bookId: String,
    val dependencies: List<MarketDataDependencyItemDto>,
)

// --- Notification DTOs ---

@Serializable
data class AlertRuleDto(
    val id: String,
    val name: String,
    val type: String,
    val threshold: Double,
    val operator: String,
    val severity: String,
    val channels: List<String>,
    val enabled: Boolean,
)

@Serializable
data class AlertEventDto(
    val id: String,
    val ruleId: String,
    val ruleName: String,
    val type: String,
    val severity: String,
    val message: String,
    val currentValue: Double,
    val threshold: Double,
    val bookId: String,
    val triggeredAt: String,
    val status: String = "TRIGGERED",
    val resolvedAt: String? = null,
    val resolvedReason: String? = null,
    val escalatedAt: String? = null,
    val escalatedTo: String? = null,
    val correlationId: String? = null,
    val suggestedAction: String? = null,
    val snoozedUntil: String? = null,
)

@Serializable
data class AcknowledgeAlertRequestDto(
    val acknowledgedBy: String,
    val notes: String? = null,
)

@Serializable
data class EscalateAlertRequestDto(
    val reason: String,
    val assignee: String? = null,
)

@Serializable
data class ResolveAlertRequestDto(
    val resolutionText: String,
)

@Serializable
data class SnoozeAlertRequestDto(
    val snoozedUntil: String,
)

@Serializable
data class CreateAlertRuleRequestDto(
    val name: String,
    val type: String,
    val threshold: Double,
    val operator: String,
    val severity: String,
    val channels: List<String>,
)

// --- Job History DTOs ---

@Serializable
data class JobPhaseClientDto(
    val name: String,
    val status: String,
    val startedAt: String,
    val completedAt: String? = null,
    val durationMs: Long? = null,
    val details: Map<String, String> = emptyMap(),
    val error: String? = null,
)

@Serializable
data class ValuationJobSummaryClientDto(
    val jobId: String,
    val bookId: String,
    val triggerType: String,
    val status: String,
    val startedAt: String,
    val completedAt: String? = null,
    val durationMs: Long? = null,
    val calculationType: String? = null,
    val confidenceLevel: String? = null,
    val varValue: Double? = null,
    val expectedShortfall: Double? = null,
    val pvValue: Double? = null,
    val delta: Double? = null,
    val gamma: Double? = null,
    val vega: Double? = null,
    val theta: Double? = null,
    val rho: Double? = null,
    val valuationDate: String? = null,
    val runLabel: String? = null,
    val promotedAt: String? = null,
    val promotedBy: String? = null,
    val currentPhase: String? = null,
    val manifestId: String? = null,
)

@Serializable
data class ValuationJobDetailClientDto(
    val jobId: String,
    val bookId: String,
    val triggerType: String,
    val status: String,
    val startedAt: String,
    val completedAt: String? = null,
    val durationMs: Long? = null,
    val calculationType: String? = null,
    val confidenceLevel: String? = null,
    val varValue: Double? = null,
    val expectedShortfall: Double? = null,
    val pvValue: Double? = null,
    val phases: List<JobPhaseClientDto> = emptyList(),
    val error: String? = null,
    val valuationDate: String? = null,
    val runLabel: String? = null,
    val promotedAt: String? = null,
    val promotedBy: String? = null,
    val currentPhase: String? = null,
    val manifestId: String? = null,
)

@Serializable
data class PaginatedJobsClientDto(
    val items: List<ValuationJobSummaryClientDto>,
    val totalCount: Long,
)

// --- SOD Snapshot DTOs ---

@Serializable
data class SodBaselineStatusClientDto(
    val exists: Boolean,
    val baselineDate: String? = null,
    val snapshotType: String? = null,
    val createdAt: String? = null,
    val sourceJobId: String? = null,
    val calculationType: String? = null,
)

// --- P&L Attribution DTOs ---

@Serializable
data class PositionPnlAttributionClientDto(
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

@Serializable
data class PnlAttributionClientDto(
    val bookId: String,
    val date: String,
    val totalPnl: String,
    val deltaPnl: String,
    val gammaPnl: String,
    val vegaPnl: String,
    val thetaPnl: String,
    val rhoPnl: String,
    val unexplainedPnl: String,
    val positionAttributions: List<PositionPnlAttributionClientDto>,
    val calculatedAt: String,
)

// --- Margin DTOs ---

@Serializable
data class MarginEstimateClientDto(
    val initialMargin: String,
    val variationMargin: String,
    val totalMargin: String,
    val currency: String,
)

// --- What-If DTOs ---

@Serializable
data class WhatIfRequestClientDto(
    val hypotheticalTrades: List<HypotheticalTradeClientDto>,
    val calculationType: String? = null,
    val confidenceLevel: String? = null,
)

@Serializable
data class HypotheticalTradeClientDto(
    val instrumentId: String,
    val assetClass: String,
    val side: String,
    val quantity: String,
    val priceAmount: String,
    val priceCurrency: String,
    val instrumentType: String,
)

@Serializable
data class PositionRiskClientDto(
    val instrumentId: String,
    val assetClass: String,
    val marketValue: String,
    val delta: String? = null,
    val gamma: String? = null,
    val vega: String? = null,
    // Per-instrument Theta / Rho (trader-review P0 #2): options carry both
    // from the Black-Scholes engine; linear instruments report zero.
    val theta: String? = null,
    val rho: String? = null,
    // Per-instrument DV01 (dollar value of a 1bp parallel rates shift).
    // Populated for FIXED_INCOME / rates instruments; zero for cash equity
    // / FX. Optional on the wire so older orchestrator responses keep
    // deserialising during a rolling upgrade.
    val dv01: String? = null,
    val varContribution: String,
    val esContribution: String,
    val percentageOfTotal: String,
)

@Serializable
data class WhatIfResultClientDto(
    val baseVaR: String,
    val baseExpectedShortfall: String,
    val baseGreeks: GreeksResultDto? = null,
    val basePositionRisk: List<PositionRiskClientDto> = emptyList(),
    val hypotheticalVaR: String,
    val hypotheticalExpectedShortfall: String,
    val hypotheticalGreeks: GreeksResultDto? = null,
    val hypotheticalPositionRisk: List<PositionRiskClientDto> = emptyList(),
    val varChange: String,
    val esChange: String,
    val calculatedAt: String,
)

// --- Domain mappers ---

fun BookSummaryDto.toDomain() = PortfolioSummary(id = BookId(bookId))

fun MoneyDto.toDomainMoney() = Money(BigDecimal(amount), Currency.getInstance(currency))

fun PositionDto.toDomain() = Position(
    bookId = BookId(bookId),
    instrumentId = InstrumentId(instrumentId),
    assetClass = AssetClass.valueOf(assetClass),
    quantity = BigDecimal(quantity),
    averageCost = averageCost.toDomainMoney(),
    marketPrice = marketPrice.toDomainMoney(),
    instrumentType = InstrumentTypeCode.fromString(instrumentType),
    strategyId = strategyId,
    strategyType = strategyType,
    strategyName = strategyName,
)

fun TradeDto.toDomain() = Trade(
    tradeId = TradeId(tradeId),
    bookId = BookId(bookId),
    instrumentId = InstrumentId(instrumentId),
    assetClass = AssetClass.valueOf(assetClass),
    side = Side.valueOf(side),
    quantity = BigDecimal(quantity),
    price = price.toDomainMoney(),
    tradedAt = Instant.parse(tradedAt),
    instrumentType = InstrumentTypeCode.fromString(instrumentType),
    counterpartyId = counterpartyId,
    status = status?.let { TradeStatus.valueOf(it) } ?: TradeStatus.LIVE,
)

/**
 * Project an upstream [TradeDto] into the gateway-internal blotter row,
 * carrying both the booked trade and any explicit fill-state projection
 * that the upstream chose to expose.
 */
fun TradeDto.toBlotterRow() = TradeBlotterRow(
    trade = toDomain(),
    fillStatus = fillStatus,
    qtyFilled = qtyFilled?.let { BigDecimal(it) },
    qtyOpen = qtyOpen?.let { BigDecimal(it) },
)

fun BookTradeResponseDto.toDomain() = BookTradeResult(
    trade = trade.toDomain(),
    position = position.toDomain(),
)

fun PricePointDto.toDomain() = PricePoint(
    instrumentId = InstrumentId(instrumentId),
    price = price.toDomainMoney(),
    timestamp = Instant.parse(timestamp),
    source = PriceSource.valueOf(source),
)

fun ValuationResultDto.toDomain() = ValuationResultSummary(
    bookId = bookId,
    calculationType = calculationType,
    confidenceLevel = confidenceLevel,
    varValue = varValue.toDouble(),
    expectedShortfall = expectedShortfall.toDouble(),
    componentBreakdown = componentBreakdown.map {
        ComponentBreakdownItem(
            assetClass = it.assetClass,
            varContribution = it.varContribution.toDouble(),
            percentageOfTotal = it.percentageOfTotal.toDouble(),
        )
    },
    calculatedAt = Instant.parse(calculatedAt),
    greeks = greeks?.toDomain(),
    pvValue = pvValue?.toDoubleOrNull(),
    valuationDate = valuationDate,
    positionGreeks = positionGreeks.orEmpty().map { pg ->
        PositionGreekSummary(
            instrumentId = pg.instrumentId,
            delta = pg.delta.toDouble(),
            gamma = pg.gamma.toDouble(),
            vega = pg.vega.toDouble(),
            theta = pg.theta.toDouble(),
            rho = pg.rho.toDouble(),
        )
    },
)

fun StressTestResultDto.toDomain() = StressTestResultSummary(
    scenarioName = scenarioName,
    baseVar = baseVar.toDouble(),
    stressedVar = stressedVar.toDouble(),
    pnlImpact = pnlImpact.toDouble(),
    assetClassImpacts = assetClassImpacts.map {
        AssetClassImpactItem(
            assetClass = it.assetClass,
            baseExposure = it.baseExposure.toDouble(),
            stressedExposure = it.stressedExposure.toDouble(),
            pnlImpact = it.pnlImpact.toDouble(),
        )
    },
    calculatedAt = Instant.parse(calculatedAt),
    positionImpacts = positionImpacts.map {
        PositionStressImpactItem(
            instrumentId = it.instrumentId,
            assetClass = it.assetClass,
            baseMarketValue = it.baseMarketValue.toDouble(),
            stressedMarketValue = it.stressedMarketValue.toDouble(),
            pnlImpact = it.pnlImpact.toDouble(),
            percentageOfTotal = it.percentageOfTotal.toDouble(),
        )
    },
    limitBreaches = limitBreaches.map {
        StressLimitBreachItem(
            limitType = it.limitType,
            limitLevel = it.limitLevel,
            limitValue = it.limitValue,
            stressedValue = it.stressedValue,
            breachSeverity = it.breachSeverity,
            scenarioName = it.scenarioName,
        )
    },
)

fun GreeksResultDto.toDomain() = GreeksResultSummary(
    bookId = bookId,
    assetClassGreeks = assetClassGreeks.map {
        GreekValuesItem(
            assetClass = it.assetClass,
            delta = it.delta.toDouble(),
            gamma = it.gamma.toDouble(),
            vega = it.vega.toDouble(),
        )
    },
    theta = theta.toDouble(),
    rho = rho.toDouble(),
    calculatedAt = Instant.parse(calculatedAt),
)

fun FrtbResultDto.toDomain() = FrtbResultSummary(
    bookId = bookId,
    sbmCharges = sbmCharges.map {
        RiskClassChargeItem(
            riskClass = it.riskClass,
            deltaCharge = it.deltaCharge.toDouble(),
            vegaCharge = it.vegaCharge.toDouble(),
            curvatureCharge = it.curvatureCharge.toDouble(),
            totalCharge = it.totalCharge.toDouble(),
        )
    },
    totalSbmCharge = totalSbmCharge.toDouble(),
    grossJtd = grossJtd.toDouble(),
    hedgeBenefit = hedgeBenefit.toDouble(),
    netDrc = netDrc.toDouble(),
    exoticNotional = exoticNotional.toDouble(),
    otherNotional = otherNotional.toDouble(),
    totalRrao = totalRrao.toDouble(),
    totalCapitalCharge = totalCapitalCharge.toDouble(),
    calculatedAt = Instant.parse(calculatedAt),
)

fun ReportResultDto.toDomain() = ReportResult(
    bookId = bookId,
    format = format,
    content = content,
    generatedAt = Instant.parse(generatedAt),
)

fun DataDependenciesDto.toDomain() = DataDependenciesSummary(
    bookId = bookId,
    dependencies = dependencies.map {
        MarketDataDependencyItem(
            dataType = it.dataType,
            instrumentId = it.instrumentId,
            assetClass = it.assetClass,
            required = it.required,
            description = it.description,
            parameters = it.parameters,
        )
    },
)

fun AlertRuleDto.toDomain() = AlertRuleItem(
    id = id,
    name = name,
    type = type,
    threshold = threshold,
    operator = operator,
    severity = severity,
    channels = channels,
    enabled = enabled,
)

fun AlertEventDto.toDomain() = AlertEventItem(
    id = id,
    ruleId = ruleId,
    ruleName = ruleName,
    type = type,
    severity = severity,
    message = message,
    currentValue = currentValue,
    threshold = threshold,
    bookId = bookId,
    triggeredAt = Instant.parse(triggeredAt),
    status = status,
    resolvedAt = resolvedAt?.let { Instant.parse(it) },
    resolvedReason = resolvedReason,
    escalatedAt = escalatedAt?.let { Instant.parse(it) },
    escalatedTo = escalatedTo,
    correlationId = correlationId,
    suggestedAction = suggestedAction,
    snoozedUntil = snoozedUntil?.let { Instant.parse(it) },
)

fun JobPhaseClientDto.toDomain() = JobPhaseItem(
    name = name,
    status = status,
    startedAt = Instant.parse(startedAt),
    completedAt = completedAt?.let { Instant.parse(it) },
    durationMs = durationMs,
    details = details,
    error = error,
)

fun ValuationJobSummaryClientDto.toDomain() = ValuationJobSummaryItem(
    jobId = jobId,
    bookId = bookId,
    triggerType = triggerType,
    status = status,
    startedAt = Instant.parse(startedAt),
    completedAt = completedAt?.let { Instant.parse(it) },
    durationMs = durationMs,
    calculationType = calculationType,
    confidenceLevel = confidenceLevel,
    varValue = varValue,
    expectedShortfall = expectedShortfall,
    pvValue = pvValue,
    delta = delta,
    gamma = gamma,
    vega = vega,
    theta = theta,
    rho = rho,
    valuationDate = valuationDate,
    runLabel = runLabel,
    promotedAt = promotedAt,
    promotedBy = promotedBy,
    currentPhase = currentPhase,
    manifestId = manifestId,
)

fun ValuationJobDetailClientDto.toDomain() = ValuationJobDetailItem(
    jobId = jobId,
    bookId = bookId,
    triggerType = triggerType,
    status = status,
    startedAt = Instant.parse(startedAt),
    completedAt = completedAt?.let { Instant.parse(it) },
    durationMs = durationMs,
    calculationType = calculationType,
    confidenceLevel = confidenceLevel,
    varValue = varValue,
    expectedShortfall = expectedShortfall,
    pvValue = pvValue,
    phases = phases.map { it.toDomain() },
    error = error,
    valuationDate = valuationDate,
    runLabel = runLabel,
    promotedAt = promotedAt,
    promotedBy = promotedBy,
    currentPhase = currentPhase,
    manifestId = manifestId,
)

fun SodBaselineStatusClientDto.toDomain() = SodBaselineStatusSummary(
    exists = exists,
    baselineDate = baselineDate,
    snapshotType = snapshotType,
    createdAt = createdAt,
    sourceJobId = sourceJobId,
    calculationType = calculationType,
)

fun PositionPnlAttributionClientDto.toDomain() = PositionPnlAttributionSummary(
    instrumentId = instrumentId,
    assetClass = assetClass,
    totalPnl = totalPnl,
    deltaPnl = deltaPnl,
    gammaPnl = gammaPnl,
    vegaPnl = vegaPnl,
    thetaPnl = thetaPnl,
    rhoPnl = rhoPnl,
    unexplainedPnl = unexplainedPnl,
)

fun PnlAttributionClientDto.toDomain() = PnlAttributionSummary(
    bookId = bookId,
    date = date,
    totalPnl = totalPnl,
    deltaPnl = deltaPnl,
    gammaPnl = gammaPnl,
    vegaPnl = vegaPnl,
    thetaPnl = thetaPnl,
    rhoPnl = rhoPnl,
    unexplainedPnl = unexplainedPnl,
    positionAttributions = positionAttributions.map { it.toDomain() },
    calculatedAt = calculatedAt,
)

fun MarginEstimateClientDto.toDomain() = MarginEstimateSummary(
    initialMargin = initialMargin,
    variationMargin = variationMargin,
    totalMargin = totalMargin,
    currency = currency,
)

fun CurrencyExposureDto.toDomain() = CurrencyExposureSummary(
    currency = currency,
    localValue = localValue.toDomainMoney(),
    baseValue = baseValue.toDomainMoney(),
    fxRate = BigDecimal(fxRate),
)

fun PortfolioAggregationDto.toDomain() = PortfolioAggregationSummary(
    bookId = bookId,
    baseCurrency = baseCurrency,
    totalNav = totalNav.toDomainMoney(),
    totalUnrealizedPnl = totalUnrealizedPnl.toDomainMoney(),
    currencyBreakdown = currencyBreakdown.map { it.toDomain() },
)

fun PositionRiskClientDto.toDomain() = PositionRiskSummaryItem(
    instrumentId = instrumentId,
    assetClass = assetClass,
    marketValue = marketValue,
    delta = delta,
    gamma = gamma,
    vega = vega,
    theta = theta,
    rho = rho,
    dv01 = dv01,
    varContribution = varContribution,
    esContribution = esContribution,
    percentageOfTotal = percentageOfTotal,
)

fun WhatIfResultClientDto.toDomain() = WhatIfResultSummary(
    baseVaR = baseVaR,
    baseExpectedShortfall = baseExpectedShortfall,
    baseGreeks = baseGreeks?.toDomain(),
    basePositionRisk = basePositionRisk.map { it.toDomain() },
    hypotheticalVaR = hypotheticalVaR,
    hypotheticalExpectedShortfall = hypotheticalExpectedShortfall,
    hypotheticalGreeks = hypotheticalGreeks?.toDomain(),
    hypotheticalPositionRisk = hypotheticalPositionRisk.map { it.toDomain() },
    varChange = varChange,
    esChange = esChange,
    calculatedAt = calculatedAt,
)

// --- Historical Replay DTOs ---

@Serializable
data class InstrumentDailyReturnsClientDto(
    val instrumentId: String,
    val dailyReturns: List<Double>,
)

@Serializable
data class HistoricalReplayRequestClientDto(
    val instrumentReturns: List<InstrumentDailyReturnsClientDto> = emptyList(),
    val scenarioName: String? = null,
    val windowStart: String? = null,
    val windowEnd: String? = null,
)

@Serializable
data class PositionReplayImpactClientDto(
    val instrumentId: String,
    val assetClass: String,
    val marketValue: String,
    val pnlImpact: String,
    val dailyPnl: List<String>,
    val proxyUsed: Boolean,
)

@Serializable
data class HistoricalReplayResultClientDto(
    val scenarioName: String,
    val totalPnlImpact: String,
    val positionImpacts: List<PositionReplayImpactClientDto>,
    val windowStart: String? = null,
    val windowEnd: String? = null,
    val calculatedAt: String,
)

// --- Reverse Stress DTOs ---

@Serializable
data class ReverseStressRequestClientDto(
    val targetLoss: Double,
    val maxShock: Double = -1.0,
)

@Serializable
data class InstrumentShockClientDto(
    val instrumentId: String,
    val shock: String,
)

@Serializable
data class ReverseStressResultClientDto(
    val shocks: List<InstrumentShockClientDto>,
    val achievedLoss: String,
    val targetLoss: String,
    val converged: Boolean,
    val calculatedAt: String,
)

fun PositionReplayImpactClientDto.toDomain() = PositionReplayImpactSummary(
    instrumentId = instrumentId,
    assetClass = assetClass,
    marketValue = marketValue,
    pnlImpact = pnlImpact,
    dailyPnl = dailyPnl,
    proxyUsed = proxyUsed,
)

fun HistoricalReplayResultClientDto.toDomain() = HistoricalReplayResultSummary(
    scenarioName = scenarioName,
    totalPnlImpact = totalPnlImpact,
    positionImpacts = positionImpacts.map { it.toDomain() },
    windowStart = windowStart,
    windowEnd = windowEnd,
    calculatedAt = calculatedAt,
)

fun InstrumentShockClientDto.toDomain() = InstrumentShockSummary(
    instrumentId = instrumentId,
    shock = shock,
)

fun ReverseStressResultClientDto.toDomain() = ReverseStressResultSummary(
    shocks = shocks.map { it.toDomain() },
    achievedLoss = achievedLoss,
    targetLoss = targetLoss,
    converged = converged,
    calculatedAt = calculatedAt,
)

// --- Rebalancing What-If DTOs ---

@Serializable
data class RebalancingTradeClientDto(
    val instrumentId: String,
    val assetClass: String,
    val side: String,
    val quantity: String,
    val priceAmount: String,
    val priceCurrency: String,
    val instrumentType: String,
    val bidAskSpreadBps: Double = 5.0,
)

@Serializable
data class RebalancingRequestClientDto(
    val trades: List<RebalancingTradeClientDto>,
    val calculationType: String? = null,
    val confidenceLevel: String? = null,
)

@Serializable
data class GreeksChangeClientDto(
    val deltaChange: String,
    val gammaChange: String,
    val vegaChange: String,
    val thetaChange: String,
    val rhoChange: String,
)

@Serializable
data class TradeVarContributionClientDto(
    val instrumentId: String,
    val side: String,
    val quantity: String,
    val marginalVarImpact: String,
    val executionCost: String,
)

@Serializable
data class RebalancingWhatIfResultClientDto(
    val baseVar: String,
    val rebalancedVar: String,
    val varChange: String,
    val varChangePct: String,
    val baseExpectedShortfall: String,
    val rebalancedExpectedShortfall: String,
    val esChange: String,
    val baseGreeks: GreeksResultDto? = null,
    val rebalancedGreeks: GreeksResultDto? = null,
    val greeksChange: GreeksChangeClientDto,
    val tradeContributions: List<TradeVarContributionClientDto>,
    val estimatedExecutionCost: String,
    val calculatedAt: String,
)

fun RebalancingWhatIfResultClientDto.toDomain() = RebalancingWhatIfResultSummary(
    baseVar = baseVar,
    rebalancedVar = rebalancedVar,
    varChange = varChange,
    varChangePct = varChangePct,
    baseExpectedShortfall = baseExpectedShortfall,
    rebalancedExpectedShortfall = rebalancedExpectedShortfall,
    esChange = esChange,
    baseGreeks = baseGreeks?.toDomain(),
    rebalancedGreeks = rebalancedGreeks?.toDomain(),
    greeksChange = GreeksChangeSummary(
        deltaChange = greeksChange.deltaChange,
        gammaChange = greeksChange.gammaChange,
        vegaChange = greeksChange.vegaChange,
        thetaChange = greeksChange.thetaChange,
        rhoChange = greeksChange.rhoChange,
    ),
    tradeContributions = tradeContributions.map {
        TradeVarContributionSummary(
            instrumentId = it.instrumentId,
            side = it.side,
            quantity = it.quantity,
            marginalVarImpact = it.marginalVarImpact,
            executionCost = it.executionCost,
        )
    },
    estimatedExecutionCost = estimatedExecutionCost,
    calculatedAt = calculatedAt,
)

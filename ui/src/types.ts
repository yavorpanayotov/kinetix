export interface MoneyDto {
  amount: string
  currency: string
}

export interface PositionDto {
  bookId: string
  instrumentId: string
  assetClass: string
  quantity: string
  averageCost: MoneyDto
  marketPrice: MoneyDto
  marketValue: MoneyDto
  unrealizedPnl: MoneyDto
  realizedPnl?: MoneyDto
  instrumentType?: string
  displayName?: string
  strategyId?: string
  strategyType?: string
  strategyName?: string
}

export interface StrategyGroupDto {
  strategyId: string
  strategyType: string
  strategyName?: string
  legs: PositionDto[]
  netDelta: string | null
  netGamma: string | null
  netVega: string | null
  netPnl: string
}

export interface PositionRiskDto {
  instrumentId: string
  assetClass: string
  marketValue: string
  delta: string | null
  gamma: string | null
  vega: string | null
  theta: string | null
  rho: string | null
  varContribution: string
  esContribution: string
  percentageOfTotal: string
}

export interface TradeHistoryDto {
  tradeId: string
  bookId: string
  instrumentId: string
  assetClass: string
  side: string
  quantity: string
  price: MoneyDto
  tradedAt: string
  instrumentType?: string
  displayName?: string
  status?: string
  venueOrderId?: string
}

export type OrderTimeInForce = 'DAY' | 'GTC' | 'IOC' | 'FOK' | 'GTD'

export interface SubmitOrderRequestDto {
  bookId: string
  instrumentId: string
  side: 'BUY' | 'SELL'
  quantity: string
  orderType: string
  limitPrice?: string
  arrivalPrice: string
  assetClass?: string
  currency?: string
  timeInForce?: OrderTimeInForce
  expiresAt?: string
  instrumentType: string
}

export interface OrderResponseDto {
  orderId: string
  bookId: string
  instrumentId: string
  side: string
  quantity: string
  orderType: string
  limitPrice?: string | null
  arrivalPrice: string
  submittedAt: string
  status: string
  fixSessionId?: string | null
  timeInForce: OrderTimeInForce
  expiresAt?: string | null
  venueOrderId?: string | null
  rejectReason?: string | null
}

export interface BookDto {
  bookId: string
}

export interface PriceUpdateMessage {
  type: 'price'
  instrumentId: string
  priceAmount: string
  priceCurrency: string
  timestamp: string
  source: string
}

export interface SubscribeMessage {
  type: 'subscribe'
  instrumentIds: string[]
}

export interface UnsubscribeMessage {
  type: 'unsubscribe'
  instrumentIds: string[]
}

export type ClientMessage = SubscribeMessage | UnsubscribeMessage

export interface PnlUpdateMessage {
  type: 'pnl'
  bookId: string
  snapshotAt: string
  baseCurrency: string
  trigger: string
  totalPnl: string
  realisedPnl: string
  unrealisedPnl: string
  deltaPnl: string
  gammaPnl: string
  vegaPnl: string
  thetaPnl: string
  rhoPnl: string
  unexplainedPnl: string
  highWaterMark: string
  correlationId?: string | null
  missingFxRates?: string[]
}

export interface IntradayPnlSnapshotDto {
  snapshotAt: string
  baseCurrency: string
  trigger: string
  totalPnl: string
  realisedPnl: string
  unrealisedPnl: string
  deltaPnl: string
  gammaPnl: string
  vegaPnl: string
  thetaPnl: string
  rhoPnl: string
  unexplainedPnl: string
  highWaterMark: string
  correlationId?: string | null
  missingFxRates?: string[]
}

export interface IntradayPnlSeriesDto {
  bookId: string
  snapshots: IntradayPnlSnapshotDto[]
}

export interface IntradayVaRPointDto {
  timestamp: string
  varValue: number
  expectedShortfall: number
  delta: number | null
  gamma: number | null
  vega: number | null
}

export interface TradeAnnotationDto {
  timestamp: string
  instrumentId: string
  side: string
  quantity: string
  tradeId: string
}

export interface IntradayVaRTimelineDto {
  bookId: string
  varPoints: IntradayVaRPointDto[]
  tradeAnnotations: TradeAnnotationDto[]
}

export interface ComponentBreakdownDto {
  assetClass: string
  varContribution: string
  percentageOfTotal: string
}

export interface VaRResultDto {
  bookId: string
  calculationType: string
  confidenceLevel: string
  varValue: string
  expectedShortfall: string
  componentBreakdown: ComponentBreakdownDto[]
  calculatedAt: string
  greeks?: GreeksResultDto
  computedOutputs?: string[]
  pvValue?: string
  valuationDate?: string
}

export interface VaRCalculationRequestDto {
  calculationType?: string
  confidenceLevel?: string
  timeHorizonDays?: string
  numSimulations?: string
  requestedOutputs?: string[]
}

export interface AssetClassImpactDto {
  assetClass: string
  baseExposure: string
  stressedExposure: string
  pnlImpact: string
}

export interface PositionStressImpactDto {
  instrumentId: string
  assetClass: string
  baseMarketValue: string
  stressedMarketValue: string
  pnlImpact: string
  percentageOfTotal: string
}

export interface StressLimitBreachDto {
  limitType: string
  limitLevel: string
  limitValue: string
  stressedValue: string
  breachSeverity: string
  scenarioName: string
}

export interface StressedGreeksDto {
  baseDelta: string
  stressedDelta: string
  baseGamma: string
  stressedGamma: string
  baseVega: string
  stressedVega: string
  baseTheta: string
  stressedTheta: string
  baseRho: string
  stressedRho: string
}

export interface StressTestResultDto {
  scenarioName: string
  baseVar: string
  stressedVar: string
  pnlImpact: string
  assetClassImpacts: AssetClassImpactDto[]
  calculatedAt: string
  positionImpacts: PositionStressImpactDto[]
  limitBreaches: StressLimitBreachDto[]
  stressedGreeks?: StressedGreeksDto
}

export interface GreekValuesDto {
  assetClass: string
  delta: string
  gamma: string
  vega: string
}

export interface GreeksResultDto {
  bookId: string
  assetClassGreeks: GreekValuesDto[]
  theta: string
  rho: string
  calculatedAt: string
}

export interface TenorChargeDto {
  tenorLabel: string
  sensitivity: string
  riskWeight: string
  weightedSensitivity: string
}

export interface RiskClassChargeDto {
  riskClass: string
  deltaCharge: string
  vegaCharge: string
  curvatureCharge: string
  totalCharge: string
  tenorCharges?: TenorChargeDto[]
}

export interface FrtbResultDto {
  bookId: string
  sbmCharges: RiskClassChargeDto[]
  totalSbmCharge: string
  grossJtd: string
  hedgeBenefit: string
  netDrc: string
  exoticNotional: string
  otherNotional: string
  totalRrao: string
  totalCapitalCharge: string
  calculatedAt: string
}

export interface ReportResultDto {
  bookId: string
  format: string
  content: string
  generatedAt: string
}

export interface AlertRuleDto {
  id: string
  name: string
  type: string
  threshold: number
  operator: string
  severity: string
  channels: string[]
  enabled: boolean
}

export interface AlertEventDto {
  id: string
  ruleId: string
  ruleName: string
  type: string
  severity: string
  message: string
  currentValue: number
  threshold: number
  bookId: string
  triggeredAt: string
  status: string
  resolvedAt?: string
  resolvedReason?: string
  escalatedAt?: string
  escalatedTo?: string
  correlationId?: string
  suggestedAction?: string
  /**
   * Operator-set snooze deadline (ISO-8601). When in the future, the
   * notification evaluator skips firing for this rule until the deadline
   * passes. Optional — backend may return null which we coerce to undefined.
   * See docs/plans/ui-overhaul.md §3.1b.4.
   */
  snoozedUntil?: string | null
}

export interface CreateAlertRuleRequestDto {
  name: string
  type: string
  threshold: number
  operator: string
  severity: string
  channels: string[]
}

export interface TimeRange {
  from: string
  to: string
  label: string
}

export interface JobPhaseDto {
  name: string
  status: string
  startedAt: string
  completedAt: string | null
  durationMs: number | null
  details: Record<string, string>
  error: string | null
}

export interface ValuationJobSummaryDto {
  jobId: string
  bookId: string
  triggerType: string
  status: string
  startedAt: string
  completedAt: string | null
  durationMs: number | null
  calculationType: string | null
  confidenceLevel: string | null
  varValue: number | null
  expectedShortfall: number | null
  pvValue: number | null
  delta: number | null
  gamma: number | null
  vega: number | null
  theta: number | null
  rho: number | null
  valuationDate?: string
  runLabel: string | null
  promotedAt: string | null
  promotedBy: string | null
  manifestId: string | null
  currentPhase?: string | null
}

export interface HypotheticalTradeDto {
  instrumentId: string
  assetClass: string
  side: string
  quantity: string
  priceAmount: string
  priceCurrency: string
}

export interface WhatIfRequestDto {
  hypotheticalTrades: HypotheticalTradeDto[]
  calculationType?: string
  confidenceLevel?: string
}

export interface WhatIfSnapshotDto {
  var: string
  expectedShortfall: string
  greeks: GreeksResultDto | null
  positionRisk: PositionRiskDto[]
}

export interface WhatIfImpactDto {
  varChange: string
  esChange: string
  deltaChange: number
  gammaChange: number
  vegaChange: number
  thetaChange: number
  rhoChange: number
}

export interface WhatIfResponseDto {
  baseVaR: string
  baseExpectedShortfall: string
  baseGreeks: GreeksResultDto | null
  basePositionRisk: PositionRiskDto[]
  hypotheticalVaR: string
  hypotheticalExpectedShortfall: string
  hypotheticalGreeks: GreeksResultDto | null
  hypotheticalPositionRisk: PositionRiskDto[]
  varChange: string
  esChange: string
  calculatedAt: string
}

export interface RebalancingTradeDto {
  instrumentId: string
  assetClass: string
  side: string
  quantity: string
  priceAmount: string
  priceCurrency: string
  bidAskSpreadBps?: number
}

export interface RebalancingRequestDto {
  trades: RebalancingTradeDto[]
  calculationType?: string
  confidenceLevel?: string
}

export interface GreeksChangeDto {
  deltaChange: string
  gammaChange: string
  vegaChange: string
  thetaChange: string
  rhoChange: string
}

export interface TradeVarContributionDto {
  instrumentId: string
  side: string
  quantity: string
  marginalVarImpact: string
  executionCost: string
}

export interface RebalancingResponseDto {
  baseVar: string
  rebalancedVar: string
  varChange: string
  varChangePct: string
  baseExpectedShortfall: string
  rebalancedExpectedShortfall: string
  esChange: string
  baseGreeks: GreeksResultDto | null
  rebalancedGreeks: GreeksResultDto | null
  greeksChange: GreeksChangeDto
  tradeContributions: TradeVarContributionDto[]
  estimatedExecutionCost: string
  calculatedAt: string
}

export interface PositionPnlAttributionDto {
  instrumentId: string
  assetClass: string
  totalPnl: string
  deltaPnl: string
  gammaPnl: string
  vegaPnl: string
  thetaPnl: string
  rhoPnl: string
  vannaPnl?: string
  volgaPnl?: string
  charmPnl?: string
  crossGammaPnl?: string
  unexplainedPnl: string
}

export interface PnlAttributionDto {
  bookId: string
  date: string
  totalPnl: string
  deltaPnl: string
  gammaPnl: string
  vegaPnl: string
  thetaPnl: string
  rhoPnl: string
  vannaPnl?: string
  volgaPnl?: string
  charmPnl?: string
  crossGammaPnl?: string
  unexplainedPnl: string
  dataQualityFlag?: string
  positionAttributions: PositionPnlAttributionDto[]
  calculatedAt: string
}

export interface SodBaselineStatusDto {
  exists: boolean
  baselineDate: string | null
  snapshotType: string | null
  createdAt: string | null
  sourceJobId: string | null
  calculationType: string | null
}

export interface SodSnapshotResultDto {
  bookId: string
  baselineDate: string
  snapshotType: string
  createdAt: string
  snapshotCount: number
}

export interface ValuationJobDetailDto {
  jobId: string
  bookId: string
  triggerType: string
  status: string
  startedAt: string
  completedAt: string | null
  durationMs: number | null
  calculationType: string | null
  confidenceLevel: string | null
  varValue: number | null
  expectedShortfall: number | null
  pvValue: number | null
  phases: JobPhaseDto[]
  error: string | null
  valuationDate?: string
  runLabel: string | null
  promotedAt: string | null
  promotedBy: string | null
  manifestId: string | null
}

export interface RunManifestDto {
  manifestId: string
  jobId: string
  bookId: string
  valuationDate: string
  capturedAt: string
  modelVersion: string
  calculationType: string
  confidenceLevel: string
  timeHorizonDays: number
  numSimulations: number
  monteCarloSeed: number
  positionCount: number
  positionDigest: string
  marketDataDigest: string
  inputDigest: string
  status: string
  varValue: number | null
  expectedShortfall: number | null
  outputDigest: string | null
}

export interface ReplayResponseDto {
  manifest: RunManifestDto
  replayVarValue: number | null
  replayExpectedShortfall: number | null
  replayModelVersion: string | null
  inputDigestMatch: boolean
  originalInputDigest: string
  replayInputDigest: string
  originalVarValue: number | null
  originalExpectedShortfall: number | null
}

export interface CurrencyExposureDto {
  currency: string
  localValue: MoneyDto
  baseValue: MoneyDto
  fxRate: string
}

export interface DataQualityCheck {
  name: string
  status: string
  message: string
  lastChecked: string
}

export interface DataQualityStatus {
  overall: string
  checks: DataQualityCheck[]
}

export interface BookAggregationDto {
  bookId: string
  baseCurrency: string
  totalNav: MoneyDto
  totalUnrealizedPnl: MoneyDto
  currencyBreakdown: CurrencyExposureDto[]
}

export interface StressScenarioDto {
  id: string
  name: string
  description: string
  shocks: string
  status: string
  createdBy: string
  approvedBy: string | null
  approvedAt: string | null
  createdAt: string
  scenarioType?: string
  scenarioCategory?: string
}

export interface CreateScenarioRequestDto {
  name: string
  description: string
  shocks: string
  createdBy: string
}

export interface ScenarioShocksDto {
  volShocks: Record<string, number>
  priceShocks: Record<string, number>
}

// --- Historical Replay Types ---

export interface InstrumentDailyReturnsDto {
  instrumentId: string
  dailyReturns: number[]
}

export interface HistoricalReplayRequestDto {
  instrumentReturns: InstrumentDailyReturnsDto[]
  scenarioName?: string
  windowStart?: string
  windowEnd?: string
}

export interface PositionReplayImpactDto {
  instrumentId: string
  assetClass: string
  marketValue: string
  pnlImpact: string
  dailyPnl: string[]
  proxyUsed: boolean
}

export interface HistoricalReplayResultDto {
  scenarioName: string
  totalPnlImpact: string
  positionImpacts: PositionReplayImpactDto[]
  windowStart: string | null
  windowEnd: string | null
  calculatedAt: string
}

// --- Reverse Stress Types ---

export interface ReverseStressRequestDto {
  targetLoss: number
  maxShock?: number
}

export interface InstrumentShockDto {
  instrumentId: string
  shock: string
}

export interface ReverseStressResultDto {
  shocks: InstrumentShockDto[]
  achievedLoss: string
  targetLoss: string
  converged: boolean
  calculatedAt: string
}

// --- Run Comparison Types ---

export type ComparisonMode = 'DAILY_VAR' | 'MODEL' | 'BACKTEST'

export type PositionChangeType = 'NEW' | 'REMOVED' | 'MODIFIED' | 'UNCHANGED'

export interface RunSnapshotDto {
  jobId: string | null
  label: string
  valuationDate: string
  calcType: string | null
  confLevel: string | null
  varValue: string | null
  es: string | null
  pv: string | null
  delta: string | null
  gamma: string | null
  vega: string | null
  theta: string | null
  rho: string | null
  componentBreakdown: ComponentBreakdownDto[]
  positionRisk: PositionRiskDto[]
  modelVersion: string | null
  parameters: Record<string, string>
  calculatedAt: string | null
}

export interface BookDiffDto {
  varChange: string
  varChangePercent: string | null
  esChange: string
  esChangePercent: string | null
  pvChange: string
  deltaChange: string
  gammaChange: string
  vegaChange: string
  thetaChange: string
  rhoChange: string
}

export interface ComponentDiffDto {
  assetClass: string
  baseContribution: string
  targetContribution: string
  change: string
  changePercent: string | null
}

export interface PositionDiffDto {
  instrumentId: string
  assetClass: string
  changeType: PositionChangeType
  baseMarketValue: string
  targetMarketValue: string
  marketValueChange: string
  baseVarContribution: string
  targetVarContribution: string
  varContributionChange: string
  baseDelta: string | null
  targetDelta: string | null
  baseGamma: string | null
  targetGamma: string | null
  baseVega: string | null
  targetVega: string | null
}

export interface ParameterDiffDto {
  paramName: string
  baseValue: string | null
  targetValue: string | null
}

export interface VaRAttributionDto {
  totalChange: string
  positionEffect: string
  volEffect: string | null
  corrEffect: string | null
  timeDecayEffect: string
  unexplained: string
  effectMagnitudes: Record<string, string>
  caveats: string[]
}

export interface PositionInputChangeDto {
  instrumentId: string
  assetClass: string
  changeType: 'ADDED' | 'REMOVED' | 'QUANTITY_CHANGED' | 'PRICE_CHANGED' | 'BOTH_CHANGED'
  baseQuantity: string | null
  targetQuantity: string | null
  quantityDelta: string | null
  baseMarketPrice: string | null
  targetMarketPrice: string | null
  priceDelta: string | null
  currency: string
}

export interface MarketDataInputChangeDto {
  dataType: string
  instrumentId: string
  assetClass: string
  changeType: 'CHANGED' | 'ADDED' | 'REMOVED' | 'BECAME_AVAILABLE' | 'BECAME_MISSING'
  magnitude: 'LARGE' | 'MEDIUM' | 'SMALL' | null
}

export interface InputChangesSummaryDto {
  positionsChanged: boolean
  marketDataChanged: boolean
  modelVersionChanged: boolean
  baseModelVersion: string
  targetModelVersion: string
  positionChanges: PositionInputChangeDto[]
  marketDataChanges: MarketDataInputChangeDto[]
  baseManifestId: string | null
  targetManifestId: string | null
}

export interface MarketDataQuantDiffDto {
  dataType: string
  instrumentId: string
  magnitude: 'LARGE' | 'MEDIUM' | 'SMALL'
  diagnostic: boolean
  summary: string | null
  caveats: string[]
}

export interface RunComparisonResponseDto {
  comparisonId: string
  comparisonType: string
  bookId: string
  baseRun: RunSnapshotDto
  targetRun: RunSnapshotDto
  bookDiff: BookDiffDto
  componentDiffs: ComponentDiffDto[]
  positionDiffs: PositionDiffDto[]
  parameterDiffs: ParameterDiffDto[]
  attribution: VaRAttributionDto | null
  inputChanges: InputChangesSummaryDto | null
}

export interface ModelComparisonRequestDto {
  calculationType: string
  confidenceLevel: string
  targetCalculationType: string
  targetConfidenceLevel: string
  targetNumSimulations?: number
}

// --- EOD Timeline Types ---

export interface EodTimelineEntryDto {
  valuationDate: string
  jobId: string
  varValue: number | null
  expectedShortfall: number | null
  pvValue: number | null
  delta: number | null
  gamma: number | null
  vega: number | null
  theta: number | null
  rho: number | null
  promotedAt: string | null
  promotedBy: string | null
  varChange: number | null
  varChangePct: number | null
  esChange: number | null
  calculationType: string | null
  confidenceLevel: number | null
}

export interface EodTimelineResponseDto {
  bookId: string
  from: string
  to: string
  entries: EodTimelineEntryDto[]
}

export interface DivisionDto {
  id: string
  name: string
  description?: string
  deskCount: number
}

export interface DeskDto {
  id: string
  name: string
  divisionId: string
  deskHead?: string
  description?: string
  bookCount: number
}

export interface BacktestComparisonDto {
  baseCalculationType: string
  baseConfidenceLevel: string
  baseTotalDays: number
  baseViolationCount: number
  baseViolationRate: string
  baseKupiecPValue: string
  baseChristoffersenPValue: string
  baseTrafficLightZone: string
  targetCalculationType: string
  targetConfidenceLevel: string
  targetTotalDays: number
  targetViolationCount: number
  targetViolationRate: string
  targetKupiecPValue: string
  targetChristoffersenPValue: string
  targetTrafficLightZone: string
  trafficLightChanged: boolean
}

// --- Cross-Book VaR ---

export interface BookVaRContributionDto {
  bookId: string
  varContribution: string
  percentageOfTotal: string
  standaloneVar: string
  diversificationBenefit: string
  marginalVar: string
  incrementalVar: string
}

export interface CrossBookVaRRequestDto {
  bookIds: string[]
  bookGroupId: string
  calculationType?: string
  confidenceLevel?: string
  timeHorizonDays?: string
  numSimulations?: string
}

export interface CrossBookVaRResultDto {
  bookGroupId: string
  bookIds: string[]
  calculationType: string
  confidenceLevel: string
  varValue: string
  expectedShortfall: string
  componentBreakdown: ComponentBreakdownDto[]
  bookContributions: BookVaRContributionDto[]
  totalStandaloneVar: string
  diversificationBenefit: string
  calculatedAt: string
}

export type LiquidityTier = 'HIGH_LIQUID' | 'LIQUID' | 'SEMI_LIQUID' | 'ILLIQUID'

export interface PositionLiquidityRiskDto {
  instrumentId: string
  assetClass: string
  marketValue: number
  tier: LiquidityTier
  horizonDays: number
  adv: number | null
  advMissing: boolean
  advStale: boolean
  lvarContribution: number
  stressedLiquidationValue: number
  concentrationStatus: string
}

export interface LiquidityRiskResultDto {
  bookId: string
  portfolioLvar: number
  dataCompleteness: number
  portfolioConcentrationStatus: string
  calculatedAt: string
  positionRisks: PositionLiquidityRiskDto[]
}

export interface FactorContributionDto {
  factorType: string
  varContribution: number
  pctOfTotal: number
  loading: number
  loadingMethod: string
}

export interface FactorRiskDto {
  bookId: string
  calculatedAt: string
  totalVar: number
  systematicVar: number
  idiosyncraticVar: number
  rSquared: number
  concentrationWarning: boolean
  factors: FactorContributionDto[]
}

export interface RiskContributorDto {
  entityId: string
  entityName: string
  varContribution: string
  pctOfTotal: string
}

export interface HierarchyNodeRiskDto {
  level: string
  entityId: string
  entityName: string
  parentId: string | null
  varValue: string
  expectedShortfall: string | null
  pnlToday: string | null
  limitUtilisation: string | null
  marginalVar: string | null
  incrementalVar: string | null
  topContributors: RiskContributorDto[]
  childCount: number
  isPartial: boolean
  missingBooks: string[]
}

export type MarketRegime = 'NORMAL' | 'ELEVATED_VOL' | 'CRISIS' | 'RECOVERY'

export interface RegimeSignalsDto {
  realisedVol20d: number
  crossAssetCorrelation: number
  creditSpreadBps: number | null
  pnlVolatility: number | null
}

export interface AdaptiveVaRParametersDto {
  calculationType: string
  confidenceLevel: string
  timeHorizonDays: number
  correlationMethod: string
  numSimulations: number | null
}

export interface MarketRegimeDto {
  regime: MarketRegime
  isConfirmed: boolean
  confidence: number
  consecutiveObservations: number
  detectedAt: string
  degradedInputs: boolean
  signals: RegimeSignalsDto
  varParameters: AdaptiveVaRParametersDto
}

export interface MarketRegimeHistoryItemDto {
  id: string
  regime: MarketRegime
  startedAt: string
  endedAt: string | null
  durationMs: number | null
  confidence: number
  consecutiveObservations: number
  degradedInputs: boolean
  signals: RegimeSignalsDto
  varParameters: AdaptiveVaRParametersDto
}

export interface MarketRegimeHistoryDto {
  items: MarketRegimeHistoryItemDto[]
  total: number
}

export type HedgeTarget = 'DELTA' | 'GAMMA' | 'VEGA' | 'VAR'
export type HedgeStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'EXPIRED'

export interface GreekImpactDto {
  deltaBefore: number
  deltaAfter: number
  gammaBefore: number
  gammaAfter: number
  vegaBefore: number
  vegaAfter: number
  thetaBefore: number
  thetaAfter: number
  rhoBefore: number
  rhoAfter: number
}

export interface HedgeSuggestionDto {
  instrumentId: string
  instrumentType: string
  side: string
  quantity: number
  estimatedCost: number
  crossingCost: number
  carrycostPerDay: number | null
  targetReduction: number
  targetReductionPct: number
  residualMetric: number
  greekImpact: GreekImpactDto
  liquidityTier: string
  dataQuality: string
}

export interface HedgeConstraintsDto {
  maxNotional: number | null
  maxSuggestions: number
  respectPositionLimits: boolean
  instrumentUniverse: string | null
  allowedSides: string[] | null
}

export interface HedgeRecommendationDto {
  id: string
  bookId: string
  targetMetric: HedgeTarget
  targetReductionPct: number
  requestedAt: string
  status: HedgeStatus
  expiresAt: string
  acceptedBy: string | null
  acceptedAt: string | null
  sourceJobId: string | null
  suggestions: HedgeSuggestionDto[]
  preHedgeGreeks: GreekImpactDto
  totalEstimatedCost: number
  isExpired: boolean
}

export interface HedgeSuggestRequestDto {
  targetMetric: HedgeTarget
  targetReductionPct: number
  maxSuggestions?: number
  maxNotional?: number | null
  respectPositionLimits?: boolean
  allowedSides?: string[] | null
}

// --- Execution Cost Types ---

export interface ExecutionCostDto {
  orderId: string
  bookId: string
  instrumentId: string
  completedAt: string
  arrivalPrice: string
  averageFillPrice: string
  side: string
  totalQty: string
  slippageBps: string
  marketImpactBps: string | null
  timingCostBps: string | null
  totalCostBps: string
}

// --- Prime Broker Reconciliation Types ---

export interface ReconciliationBreakDto {
  instrumentId: string
  internalQty: string
  primeBrokerQty: string
  breakQty: string
  breakNotional: string
}

export interface ReconciliationDto {
  reconciliationDate: string
  bookId: string
  status: string
  totalPositions: number
  matchedCount: number
  breakCount: number
  breaks: ReconciliationBreakDto[]
  reconciledAt: string
}

export interface KrdBucketDto {
  tenorLabel: string
  tenorDays: number
  dv01: string
}

export interface InstrumentKrdResultDto {
  instrumentId: string
  krdBuckets: KrdBucketDto[]
  totalDv01: string
}

export interface KeyRateDurationResponseDto {
  bookId: string
  instruments: InstrumentKrdResultDto[]
  aggregated: KrdBucketDto[]
}

export interface SaCcrResultDto {
  nettingSetId: string
  counterpartyId: string
  replacementCost: number
  pfeAddon: number
  multiplier: number
  ead: number
  alpha: number
}

export interface PositionNoteDto {
  id: string
  bookId: string
  instrumentId: string
  note: string
  author: string
  createdAt: string
}

export interface CreatePositionNoteRequest {
  instrumentId: string
  note: string
}

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ErrorBoundary, SectionErrorCard } from './ErrorBoundary'
import { SubTabBar } from './ui/SubTabBar'
import { useVaR } from '../hooks/useVaR'
import { useCrossBookVaR } from '../hooks/useCrossBookVaR'
import { usePositionRisk } from '../hooks/usePositionRisk'
import { useVarLimit } from '../hooks/useVarLimit'
import { useAlerts } from '../hooks/useAlerts'
import { useSodBaseline } from '../hooks/useSodBaseline'
import { usePnlAttribution } from '../hooks/usePnlAttribution'
import { useLiquidityRisk } from '../hooks/useLiquidityRisk'
import { useFactorRisk } from '../hooks/useFactorRisk'
import { useFactorRiskHistory } from '../hooks/useFactorRiskHistory'
import { useHierarchyNodeRisk } from '../hooks/useHierarchyNodeRisk'
import type { MarketRegime, StressTestResultDto } from '../types'
import { VaRDashboard } from './VaRDashboard'
import { PositionRiskTable } from './PositionRiskTable'
import { BookContributionTable } from './BookContributionTable'
import { HierarchyContributionTable } from './HierarchyContributionTable'
import { RiskBudgetPanel } from './RiskBudgetPanel'
import { JobHistory } from './JobHistory'
import { RiskAlertBanner } from './RiskAlertBanner'
import { StressSummaryCard } from './StressSummaryCard'
import { PnlSummaryCard } from './PnlSummaryCard'
import { LiquidityRiskPanel } from './LiquidityRiskPanel'
import { LimitsPanel } from './LimitsPanel'
import { LimitsBreachHeader } from './LimitsBreachHeader'
import { MarginPanel } from './MarginPanel'
import { FactorDecompositionPanel } from './FactorDecompositionPanel'
import { FactorAttributionHistoryChart } from './FactorAttributionHistoryChart'
import { LastUpdatedIndicator } from './LastUpdatedIndicator'
import { ValuationDatePicker } from './ValuationDatePicker'
import { RunComparisonContainer } from './RunComparisonContainer'
import { CorrelationHeatmap } from './CorrelationHeatmap'
import { HedgeRecommendationPanel } from './HedgeRecommendationPanel'
import { useHedgeRecommendation } from '../hooks/useHedgeRecommendation'
import { useIntradayVaRTimeline } from '../hooks/useIntradayVaRTimeline'
import { useKrd } from '../hooks/useKrd'
import { VolSurfacePanel } from './VolSurfacePanel'
import { YieldCurvePanel } from './YieldCurvePanel'
import { IntradayVaRChart } from './IntradayVaRChart'
import { KrdPanel } from './KrdPanel'
import { Spinner } from './ui/Spinner'
import { ErrorCard } from './ui/ErrorCard'
import { SectionBlock } from './ui/SectionBlock'
import { useWorkspace, type RiskDashboardSectionsState } from '../hooks/useWorkspace'

type RiskSubTab = 'dashboard' | 'run-compare' | 'market-data' | 'intraday'

interface RiskTabProps {
  bookId: string | null
  stressResults: StressTestResultDto[]
  stressLoading: boolean
  onRunStress: () => void
  onViewStressDetails: () => void
  onWhatIf?: () => void
  onViewPnlTab?: () => void
  aggregatedView?: boolean
  effectiveBookIds?: string[]
  bookGroupId?: string | null
  hierarchyLevel?: 'FIRM' | 'DIVISION' | 'DESK' | null
  onNavigateToBook?: (bookId: string) => void
  /** Active demo scenario context — threaded down to per-number annotations (plan §1.2). */
  activeScenario?: string | null
  /** Current market regime — used to annotate regime-adjusted VaR / ES numbers. */
  marketRegime?: MarketRegime | null
  /** Switch to the Alerts tab — wired by the breach-header "recent alerts" chip. */
  onShowAlerts?: () => void
  /**
   * Cross-tab link (plan §2.4): when the user opens RiskTab via a "Open in
   * Risk" link from the Reports tab, this seed value sets the valuation
   * date picker so the dashboard renders as-of that date. The user can
   * still change it via the date picker afterwards.
   */
  initialValuationDate?: string | null
}

export function RiskTab({
  bookId,
  stressResults,
  stressLoading,
  onRunStress,
  onViewStressDetails,
  onWhatIf,
  onViewPnlTab,
  aggregatedView = false,
  effectiveBookIds = [],
  bookGroupId = null,
  hierarchyLevel = null,
  onNavigateToBook,
  activeScenario = null,
  marketRegime = null,
  onShowAlerts,
  initialValuationDate = null,
}: RiskTabProps) {
  const [subTab, setSubTab] = useState<RiskSubTab>('dashboard')
  const [valuationDate, setValuationDate] = useState<string | null>(initialValuationDate)
  // Sync the picker when the parent threads a new initial valuation date —
  // e.g. the user clicks a different report's "Open in Risk".
  const [lastInitialValuationDate, setLastInitialValuationDate] = useState(initialValuationDate)
  if (initialValuationDate !== lastInitialValuationDate) {
    setLastInitialValuationDate(initialValuationDate)
    setValuationDate(initialValuationDate)
  }
  const [pendingJobCompare, setPendingJobCompare] = useState<{ baseJobId: string; targetJobId: string } | null>(null)
  const [hedgePanelOpen, setHedgePanelOpen] = useState(false)
  const limitsPanelRef = useRef<HTMLDivElement | null>(null)

  const { preferences, updatePreference } = useWorkspace()
  const sections = preferences.riskDashboardSections
  const toggleSection = useCallback(
    (key: keyof RiskDashboardSectionsState, next: boolean) => {
      updatePreference('riskDashboardSections', { ...sections, [key]: next })
    },
    [sections, updatePreference],
  )

  const scrollToLimitsPanel = useCallback(() => {
    limitsPanelRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }, [])
  const { recommendation: hedgeRec, loading: hedgeLoading, error: hedgeError, suggest: suggestHedge } = useHedgeRecommendation(bookId)
  const { aggregated: krdAggregated, instruments: krdInstruments, loading: krdLoading, error: krdError } = useKrd(bookId)

  // Intraday VaR: default to today's trading window (07:00 UTC to now)
  const todayFrom = useMemo(() => {
    const d = new Date()
    d.setUTCHours(7, 0, 0, 0)
    return d.toISOString()
  }, [])
  const todayTo = useMemo(() => new Date().toISOString(), [])
  const { varPoints: intradayVarPoints, tradeAnnotations: intradayTradeAnnotations, loading: intradayVarLoading, error: intradayVarError } = useIntradayVaRTimeline(
    subTab === 'intraday' ? bookId : null,
    todayFrom,
    todayTo,
  )

  const handleCompareJobs = useCallback((baseJobId: string, targetJobId: string) => {
    setPendingJobCompare({ baseJobId, targetJobId })
    setSubTab('run-compare')
  }, [])

  const {
    varResult,
    greeksResult,
    loading: varLoading,
    historyLoading: varHistoryLoading,
    refreshing: varRefreshing,
    error: varError,
    refresh,
    filteredHistory,
    timeRange: varTimeRange,
    setTimeRange: setVarTimeRange,
    zoomIn: varZoomIn,
    resetZoom: varResetZoom,
    zoomDepth: varZoomDepth,
    selectedConfidenceLevel,
    setSelectedConfidenceLevel,
    isLive,
  } = useVaR(bookId, valuationDate)

  const {
    positionRisk,
    loading: positionRiskLoading,
    error: positionRiskError,
    refresh: refreshPositionRisk,
  } = usePositionRisk(bookId, valuationDate)

  const {
    result: crossBookResult,
    loading: crossBookLoading,
    refreshing: crossBookRefreshing,
    error: crossBookError,
    refresh: crossBookRefresh,
  } = useCrossBookVaR(
    aggregatedView ? effectiveBookIds : [],
    aggregatedView ? bookGroupId : null,
  )

  const { varLimit } = useVarLimit()
  const { alerts, dismissAlert } = useAlerts()

  const sod = useSodBaseline(bookId)
  const { data: pnlData } = usePnlAttribution(bookId)

  const {
    result: liquidityResult,
    loading: liquidityLoading,
    refresh: refreshLiquidity,
  } = useLiquidityRisk(bookId)

  const {
    result: factorRiskResult,
    loading: factorRiskLoading,
    error: factorRiskError,
  } = useFactorRisk(bookId)

  const {
    history: factorRiskHistory,
    loading: factorRiskHistoryLoading,
    error: factorRiskHistoryError,
  } = useFactorRiskHistory(bookId)
  const { node: hierarchyNode } = useHierarchyNodeRisk(
    aggregatedView ? hierarchyLevel : null,
    bookGroupId ?? 'FIRM',
  )

  const [jobRefreshSignal, setJobRefreshSignal] = useState(0)

  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if (e.shiftKey && e.key === 'H' && !(e.target instanceof HTMLInputElement) && !(e.target instanceof HTMLTextAreaElement)) {
        setHedgePanelOpen((prev) => !prev)
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [])

  const handleRefresh = useCallback(async () => {
    if (aggregatedView) {
      await crossBookRefresh()
    }
    await refresh()
    await refreshPositionRisk()
    setJobRefreshSignal((prev) => prev + 1)
  }, [refresh, refreshPositionRisk, crossBookRefresh, aggregatedView])

  const handleLiquidityRefresh = useCallback(() => {
    const baseVar = varResult ? Number(varResult.varValue) : 0
    refreshLiquidity(baseVar)
  }, [varResult, refreshLiquidity])

  useEffect(() => {
    if (bookId) {
      refreshLiquidity()
    }
  }, [bookId, refreshLiquidity])

  const lastUpdated = varResult?.calculatedAt ?? null

  const subTabs: { id: RiskSubTab; label: string; testId: string }[] = [
    { id: 'dashboard', label: 'Dashboard', testId: 'risk-subtab-dashboard' },
    { id: 'intraday', label: 'Intraday', testId: 'risk-subtab-intraday' },
    { id: 'run-compare', label: 'Run Compare', testId: 'risk-subtab-run-compare' },
    { id: 'market-data', label: 'Market Data', testId: 'risk-subtab-market-data' },
  ]

  const instrumentIds = [...new Set(positionRisk.map((p) => p.instrumentId))]

  return (
    <div>
      <SubTabBar
        aria-label="Risk sections"
        activeId={subTab}
        onSelect={(id) => setSubTab(id as RiskSubTab)}
        tabs={subTabs}
      />


      {subTab === 'dashboard' && (
        <>
          <LimitsBreachHeader
            alerts={alerts}
            onScrollToLimits={scrollToLimitsPanel}
            onShowAlerts={onShowAlerts}
          />
          {aggregatedView && !crossBookResult && !crossBookLoading && (
            <div
              data-testid="aggregated-var-note"
              className="mb-3 px-3 py-2 text-sm text-amber-700 bg-amber-50 border border-amber-200 rounded-md"
            >
              Showing sum of book VaRs — click Recalculate All to compute diversified portfolio VaR.
            </div>
          )}
          {alerts.length > 0 && (
            <div className="mb-2">
              <RiskAlertBanner alerts={alerts} onDismiss={dismissAlert} />
            </div>
          )}
          <div className="flex items-center justify-between mb-2">
            <ValuationDatePicker value={valuationDate} onChange={setValuationDate} />
            <div className="flex items-center gap-2">
              <LastUpdatedIndicator timestamp={lastUpdated} />
              {!aggregatedView && (
                <button
                  onClick={() => setHedgePanelOpen(true)}
                  className="text-xs px-2 py-1 rounded bg-blue-50 dark:bg-blue-900/30 text-blue-700 dark:text-blue-300 hover:bg-blue-100 dark:hover:bg-blue-900/50 border border-blue-200 dark:border-blue-800 transition-colors"
                  title="Suggest Hedge (Shift+H)"
                  data-testid="suggest-hedge-open-button"
                >
                  Suggest Hedge
                </button>
              )}
            </div>
          </div>
          <div className="space-y-4 mt-2">
            <SectionBlock
              title="Market Risk"
              open={sections.marketRisk}
              onToggle={(next) => toggleSection('marketRisk', next)}
              data-testid="section-block-market-risk"
            >
              <ErrorBoundary fallback={<SectionErrorCard name="VaR Dashboard" />}>
                <VaRDashboard
                  varResult={varResult}
                  filteredHistory={filteredHistory}
                  loading={varLoading}
                  historyLoading={varHistoryLoading}
                  refreshing={varRefreshing || crossBookRefreshing}
                  error={crossBookError || varError}
                  onRefresh={handleRefresh}
                  timeRange={varTimeRange}
                  setTimeRange={setVarTimeRange}
                  zoomIn={varZoomIn}
                  resetZoom={varResetZoom}
                  zoomDepth={varZoomDepth}
                  greeksResult={greeksResult}
                  varLimit={varLimit}
                  onWhatIf={onWhatIf}
                  selectedConfidenceLevel={selectedConfidenceLevel}
                  onConfidenceLevelChange={setSelectedConfidenceLevel}
                  isLive={isLive}
                  valuationDate={valuationDate}
                  totalStandaloneVar={crossBookResult ? Number(crossBookResult.totalStandaloneVar) : undefined}
                  diversificationBenefit={crossBookResult ? Number(crossBookResult.diversificationBenefit) : undefined}
                  activeScenario={activeScenario}
                  marketRegime={marketRegime}
                />
              </ErrorBoundary>
              {aggregatedView && crossBookResult && (
                <>
                  <div className="mt-4">
                    <BookContributionTable
                      contributions={crossBookResult.bookContributions}
                      onBookClick={onNavigateToBook}
                    />
                  </div>
                  <div className="mt-4 grid grid-cols-1 md:grid-cols-2 gap-4">
                    <CorrelationHeatmap
                      assetClasses={[...new Set(crossBookResult.componentBreakdown.map((c) => c.assetClass))]}
                    />
                  </div>
                </>
              )}
              {aggregatedView && hierarchyNode && (
                <div className="mt-4 space-y-3" data-testid="hierarchy-contribution-section">
                  <RiskBudgetPanel node={hierarchyNode} />
                  <HierarchyContributionTable
                    node={hierarchyNode}
                    onEntityClick={onNavigateToBook}
                  />
                </div>
              )}
            </SectionBlock>

            <SectionBlock
              title="Position & Factor Risk"
              open={sections.positionFactor}
              onToggle={(next) => toggleSection('positionFactor', next)}
              data-testid="section-block-position-factor"
            >
              <ErrorBoundary fallback={<SectionErrorCard name="Position Risk" />}>
                <PositionRiskTable
                  data={positionRisk}
                  loading={positionRiskLoading}
                  error={positionRiskError}
                  onRetry={refreshPositionRisk}
                  activeScenario={activeScenario}
                  marketRegime={marketRegime}
                />
              </ErrorBoundary>
              <div className="mt-4">
                <KrdPanel
                  aggregated={krdAggregated}
                  instruments={krdInstruments}
                  loading={krdLoading}
                  error={krdError}
                  activeScenario={activeScenario}
                  marketRegime={marketRegime}
                />
              </div>
              <div className="mt-4">
                <ErrorBoundary fallback={<SectionErrorCard name="Factor Decomposition" />}>
                  <FactorDecompositionPanel
                    result={factorRiskResult}
                    loading={factorRiskLoading}
                    error={factorRiskError}
                    activeScenario={activeScenario}
                    marketRegime={marketRegime}
                  />
                </ErrorBoundary>
              </div>
              <div className="mt-4">
                <FactorAttributionHistoryChart
                  history={factorRiskHistory}
                  loading={factorRiskHistoryLoading}
                  error={factorRiskHistoryError}
                />
              </div>
            </SectionBlock>

            <SectionBlock
              title="P&L, Stress & Liquidity"
              open={sections.pnlStressLiquidity}
              onToggle={(next) => toggleSection('pnlStressLiquidity', next)}
              data-testid="section-block-pnl-stress-liquidity"
            >
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <PnlSummaryCard
                  sodStatus={sod.status}
                  pnlData={pnlData}
                  computing={sod.computing}
                  onComputePnl={sod.computeAttribution}
                  onViewFullAttribution={onViewPnlTab}
                  activeScenario={activeScenario}
                />
                <StressSummaryCard
                  results={stressResults}
                  loading={stressLoading}
                  onRun={onRunStress}
                  onViewDetails={onViewStressDetails}
                  activeScenario={activeScenario}
                />
              </div>
              <div className="mt-4">
                <ErrorBoundary fallback={<SectionErrorCard name="Liquidity Risk" />}>
                  <LiquidityRiskPanel
                    result={liquidityResult}
                    loading={liquidityLoading}
                    onRefresh={handleLiquidityRefresh}
                  />
                </ErrorBoundary>
              </div>
              <div className="mt-4">
                <ErrorBoundary fallback={<SectionErrorCard name="Margin" />}>
                  <MarginPanel bookId={bookId} />
                </ErrorBoundary>
              </div>
            </SectionBlock>

            <SectionBlock
              title="Limits & Jobs"
              open={sections.limitsJobs}
              onToggle={(next) => toggleSection('limitsJobs', next)}
              data-testid="section-block-limits-jobs"
            >
              <div ref={limitsPanelRef}>
                <ErrorBoundary fallback={<SectionErrorCard name="Limits" />}>
                  <LimitsPanel />
                </ErrorBoundary>
              </div>
              <div className="mt-4">
                <JobHistory bookId={bookId} refreshSignal={jobRefreshSignal} onCompareJobs={handleCompareJobs} />
              </div>
            </SectionBlock>
          </div>
        </>
      )}

      {subTab === 'run-compare' && (
        <RunComparisonContainer bookId={bookId} initialJobIds={pendingJobCompare} />
      )}

      {subTab === 'market-data' && (
        <div className="space-y-6">
          <VolSurfacePanel instruments={instrumentIds} />
          <YieldCurvePanel />
        </div>
      )}

      {subTab === 'intraday' && (
        <div data-testid="intraday-var-panel" className="space-y-4">
          {intradayVarLoading && <Spinner size="sm" />}
          {intradayVarError && <ErrorCard message={intradayVarError} />}
          {!intradayVarLoading && !intradayVarError && (
            <IntradayVaRChart varPoints={intradayVarPoints} tradeAnnotations={intradayTradeAnnotations} />
          )}
        </div>
      )}

      <HedgeRecommendationPanel
        open={hedgePanelOpen}
        onClose={() => setHedgePanelOpen(false)}
        bookId={bookId}
        recommendation={hedgeRec}
        loading={hedgeLoading}
        error={hedgeError}
        onSuggest={suggestHedge}
        onSendToWhatIf={onWhatIf ? () => onWhatIf() : undefined}
      />
    </div>
  )
}

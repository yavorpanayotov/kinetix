import { useCallback, useEffect, useMemo, useState } from 'react'
import { ErrorBoundary, SectionErrorCard } from './ErrorBoundary'
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
import type { StressTestResultDto } from '../types'
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
}: RiskTabProps) {
  const [subTab, setSubTab] = useState<RiskSubTab>('dashboard')
  const [valuationDate, setValuationDate] = useState<string | null>(null)
  const [pendingJobCompare, setPendingJobCompare] = useState<{ baseJobId: string; targetJobId: string } | null>(null)
  const [hedgePanelOpen, setHedgePanelOpen] = useState(false)
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

  const subTabs: { key: RiskSubTab; label: string }[] = [
    { key: 'dashboard', label: 'Dashboard' },
    { key: 'intraday', label: 'Intraday' },
    { key: 'run-compare', label: 'Run Compare' },
    { key: 'market-data', label: 'Market Data' },
  ]

  const instrumentIds = [...new Set(positionRisk.map((p) => p.instrumentId))]

  return (
    <div>
      {/* Sub-tab bar */}
      <div className="flex gap-1 mb-4 border-b border-slate-200 dark:border-surface-700">
        {subTabs.map((t) => (
          <button
            key={t.key}
            data-testid={`risk-subtab-${t.key}`}
            onClick={() => setSubTab(t.key)}
            className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
              subTab === t.key
                ? 'border-primary-500 text-primary-600 dark:text-primary-400'
                : 'border-transparent text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300'
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      {subTab === 'dashboard' && (
        <>
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
          <div className="mt-4">
            <ErrorBoundary fallback={<SectionErrorCard name="Position Risk" />}>
              <PositionRiskTable data={positionRisk} loading={positionRiskLoading} error={positionRiskError} onRetry={refreshPositionRisk} />
            </ErrorBoundary>
          </div>
          <div className="mt-4">
            <KrdPanel aggregated={krdAggregated} instruments={krdInstruments} loading={krdLoading} error={krdError} />
          </div>
          <div className="mt-4 grid grid-cols-1 md:grid-cols-2 gap-4">
            <PnlSummaryCard
              sodStatus={sod.status}
              pnlData={pnlData}
              computing={sod.computing}
              onComputePnl={sod.computeAttribution}
              onViewFullAttribution={onViewPnlTab}
            />
            <StressSummaryCard
              results={stressResults}
              loading={stressLoading}
              onRun={onRunStress}
              onViewDetails={onViewStressDetails}
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
          <div className="mt-4">
            <ErrorBoundary fallback={<SectionErrorCard name="Limits" />}>
              <LimitsPanel />
            </ErrorBoundary>
          </div>
          <div className="mt-4">
            <ErrorBoundary fallback={<SectionErrorCard name="Factor Decomposition" />}>
              <FactorDecompositionPanel
                result={factorRiskResult}
                loading={factorRiskLoading}
                error={factorRiskError}
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
          <div className="mt-4">
            <JobHistory bookId={bookId} refreshSignal={jobRefreshSignal} onCompareJobs={handleCompareJobs} />
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
          {intradayVarError && <p className="text-sm text-red-600">{intradayVarError}</p>}
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

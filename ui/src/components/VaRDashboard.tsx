import { useCallback, useEffect, useRef, useState } from 'react'
import { Calendar, FlaskRound, Info, RefreshCw, X } from 'lucide-react'
import type { VaRResultDto, GreeksResultDto, MarketRegime, TimeRange, TradeAnnotationDto } from '../types'
import type { VaRHistoryEntry } from '../hooks/useVaR'
import { useClickOutside } from '../hooks/useClickOutside'
import { VaRGauge } from './VaRGauge'
import { RiskSensitivities } from './RiskSensitivities'
import { ComponentBreakdown } from './ComponentBreakdown'
import { useStressWindows } from '../hooks/useStressWindows'
import { VaRTrendChart } from './VaRTrendChart'
import { GreeksTrendChart } from './GreeksTrendChart'
import { TimeRangeSelector } from './TimeRangeSelector'
import { Card, Button, Spinner, EmptyState } from './ui'

const calculationTypeDescriptions: Record<string, string> = {
  PARAMETRIC: 'Variance-covariance method — assumes returns are normally distributed and estimates VaR from the portfolio\'s mean and standard deviation.',
  HISTORICAL: 'Historical simulation — uses actual past returns to estimate potential losses without assuming a specific distribution.',
  MONTE_CARLO: 'Monte Carlo simulation — generates thousands of random return scenarios to estimate the distribution of potential losses.',
}

interface VaRDashboardProps {
  varResult: VaRResultDto | null
  filteredHistory: VaRHistoryEntry[]
  loading: boolean
  historyLoading?: boolean
  refreshing?: boolean
  error: string | null
  onRefresh: () => void
  timeRange: TimeRange
  setTimeRange: (range: TimeRange) => void
  zoomIn: (range: TimeRange) => void
  resetZoom: () => void
  zoomDepth: number
  greeksResult?: GreeksResultDto | null
  varLimit?: number | null
  onWhatIf?: () => void
  selectedConfidenceLevel?: string
  onConfidenceLevelChange?: (level: string) => void
  isLive?: boolean
  valuationDate?: string | null
  totalStandaloneVar?: number
  diversificationBenefit?: number
  /**
   * The name of the active demo scenario, if any. When non-null, the VaR and
   * sensitivities displayed below came out of a scenario run and are annotated
   * with a per-number badge — see plan §1.2.
   */
  activeScenario?: string | null
  /**
   * The current market regime, if any. Non-NORMAL regimes adjust the VaR
   * parameters (calculationType, confidenceLevel, horizon) and the VaR /
   * Expected Shortfall numbers carry a regime-adjusted badge accordingly.
   */
  marketRegime?: MarketRegime | null
  /**
   * Plan §8.4 — trade markers on the VaR trend chart so a user can answer
   * "why did VaR move at 2:47pm?". Sourced from the intraday timeline hook.
   */
  tradeAnnotations?: TradeAnnotationDto[]
}

export function VaRDashboard({ varResult, filteredHistory, loading, historyLoading, refreshing = false, error, onRefresh, timeRange, setTimeRange, zoomIn, resetZoom, zoomDepth, greeksResult, varLimit, onWhatIf, selectedConfidenceLevel, onConfidenceLevelChange, isLive = true, valuationDate, totalStandaloneVar, diversificationBenefit, activeScenario = null, marketRegime = null, tradeAnnotations = [] }: VaRDashboardProps) {
  const [tooltipOpen, setTooltipOpen] = useState(false)
  const [chartView, setChartView] = useState<'var' | 'greeks'>('var')
  const calcTypeRef = useRef<HTMLSpanElement>(null)
  const { windows: stressWindows } = useStressWindows()

  const closeTooltip = useCallback(() => setTooltipOpen(false), [])
  useClickOutside(calcTypeRef, closeTooltip)

  useEffect(() => {
    if (!tooltipOpen) return
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setTooltipOpen(false)
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [tooltipOpen])

  if (loading && !varResult) {
    return (
      <Card data-testid="var-loading" className="mb-4">
        <div className="flex items-center gap-2 text-slate-500">
          <Spinner size="sm" />
          Loading VaR data...
        </div>
      </Card>
    )
  }

  if (error) {
    return (
      <Card data-testid="var-error" className="mb-4">
        <div className="flex items-center justify-between gap-4">
          <p className="text-red-600">{error}</p>
          <Button
            data-testid="var-error-retry"
            variant="primary"
            size="sm"
            icon={<RefreshCw className="h-3 w-3" />}
            onClick={onRefresh}
          >
            Retry
          </Button>
        </div>
      </Card>
    )
  }

  if (!varResult) {
    return (
      <Card data-testid="var-empty" className="mb-4">
        <EmptyState
          title="No VaR results yet"
          description="Run a calculation to see risk metrics."
        />
      </Card>
    )
  }

  const description = calculationTypeDescriptions[varResult.calculationType]
  const toggleTooltip = () => setTooltipOpen(prev => !prev)

  const varValue = Number(varResult.varValue)
  const expectedShortfall = Number(varResult.expectedShortfall)
  const previousVaR = filteredHistory.length >= 2 ? filteredHistory[filteredHistory.length - 2].varValue : null

  return (
    <Card data-testid="var-dashboard" className={`mb-4${!isLive ? ' bg-amber-50/30 dark:bg-amber-950/20' : ''}`}>
      {!isLive && (
        <div data-testid="historical-badge" className="flex items-center gap-1.5 mb-3 text-xs font-medium text-amber-700 dark:text-amber-400" aria-live="polite">
          <Calendar className="h-3.5 w-3.5" />
          Historical — {valuationDate}
        </div>
      )}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-6">
        <VaRGauge
          varValue={varValue}
          expectedShortfall={expectedShortfall}
          confidenceLevel={selectedConfidenceLevel ?? varResult.confidenceLevel}
          varLimit={varLimit}
          previousVaR={previousVaR}
          onConfidenceLevelChange={onConfidenceLevelChange}
          disabled={refreshing}
          totalStandaloneVar={totalStandaloneVar}
          diversificationBenefit={diversificationBenefit}
          activeScenario={activeScenario}
          marketRegime={marketRegime}
        />

        <div data-testid="var-sensitivities" className="md:col-span-2 flex flex-col items-center justify-center">
          {greeksResult ? (
            <RiskSensitivities greeksResult={greeksResult} pvValue={varResult.pvValue} />
          ) : (
            <div data-testid="sensitivities-placeholder" className="text-sm text-slate-400 text-center">No greeks data</div>
          )}
        </div>

        <div data-testid="var-breakdown" className="flex flex-col justify-center">
          <ComponentBreakdown breakdown={varResult.componentBreakdown} bookVaR={varResult.varValue} />
        </div>
      </div>

      <div className="mt-4">
        <div className="flex items-center justify-between mb-2">
          <TimeRangeSelector value={timeRange} onChange={setTimeRange} />
          <div className="inline-flex rounded-md border border-slate-200" role="group">
            <button
              data-testid="chart-toggle-var"
              onClick={() => setChartView('var')}
              className={`px-3 py-1 text-xs font-medium rounded-l-md transition-colors ${chartView === 'var' ? 'bg-primary-100 text-primary-700' : 'text-slate-500 hover:bg-slate-50'}`}
            >
              VaR / ES
            </button>
            <button
              data-testid="chart-toggle-greeks"
              onClick={() => setChartView('greeks')}
              className={`px-3 py-1 text-xs font-medium rounded-r-md transition-colors ${chartView === 'greeks' ? 'bg-primary-100 text-primary-700' : 'text-slate-500 hover:bg-slate-50'}`}
            >
              Greeks
            </button>
          </div>
        </div>
        {chartView === 'var' ? (
          <VaRTrendChart
            history={filteredHistory}
            isLoading={historyLoading}
            timeRange={timeRange}
            onZoom={zoomIn}
            zoomDepth={zoomDepth}
            onResetZoom={resetZoom}
            stressWindows={stressWindows}
            tradeAnnotations={tradeAnnotations}
          />
        ) : (
          <GreeksTrendChart
            history={filteredHistory}
            isLoading={historyLoading}
            timeRange={timeRange}
            onZoom={zoomIn}
            zoomDepth={zoomDepth}
            onResetZoom={resetZoom}
          />
        )}
      </div>

      <div className="flex items-center flex-wrap justify-between gap-2 mt-4 pt-3 border-t border-slate-100 text-xs text-slate-500">
        <span ref={calcTypeRef} className="relative min-w-0">
          <span data-testid="calc-type-label" className="inline-flex items-center gap-1">
            {varResult.calculationType}
            <Info data-testid="calc-type-info" className="h-3 w-3 cursor-pointer text-slate-400 hover:text-slate-600 transition-colors flex-shrink-0" onClick={toggleTooltip} />
          </span>
          {tooltipOpen && description && (
            <span data-testid="calc-type-tooltip" className="absolute bottom-full left-0 mb-1 w-64 rounded bg-slate-800 px-3 py-2 text-xs text-white shadow-lg z-10">
              <button data-testid="calc-type-tooltip-close" className="float-right ml-2 text-slate-400 hover:text-white" onClick={closeTooltip}><X className="h-3 w-3" /></button>
              {description}
            </span>
          )}
          {' '}&middot;{' '}
          <span title={new Date(varResult.calculatedAt).toLocaleString()}>
            {new Date(varResult.calculatedAt).toLocaleTimeString()}
          </span>
          {varResult.valuationDate && (
            <span data-testid="valuation-date-label"> &middot; Risk as of: {varResult.valuationDate}</span>
          )}
        </span>
        <div className="flex items-center gap-2">
          {onWhatIf && (
            <button
              data-testid="var-whatif-button"
              onClick={onWhatIf}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium text-indigo-600 border border-indigo-300 rounded-md hover:bg-indigo-50 transition-colors"
            >
              <FlaskRound className="h-4 w-4" />
              What-If
            </button>
          )}
          <Button
            data-testid="var-recalculate"
            variant="primary"
            size="sm"
            icon={<RefreshCw className={`h-3 w-3${refreshing ? ' animate-spin' : ''}`} />}
            onClick={onRefresh}
            disabled={refreshing || !isLive}
          >
            {refreshing ? 'Refreshing...' : 'Refresh'}
          </Button>
        </div>
      </div>
    </Card>
  )
}

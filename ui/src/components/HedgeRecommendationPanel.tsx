import { useState } from 'react'
import { X, TrendingDown, AlertCircle, Loader2 } from 'lucide-react'
import type { HedgeRecommendationDto, HedgeSuggestionDto, HedgeTarget, GreekImpactDto } from '../types'
import { formatNum } from '../utils/format'
import { Button, Card, ErrorCard, StalePanelWrapper } from './ui'

interface HedgeRecommendationPanelProps {
  open: boolean
  onClose: () => void
  bookId: string | null
  recommendation: HedgeRecommendationDto | null
  loading: boolean
  error: string | null
  onSuggest: (target: HedgeTarget, reductionPct: number, maxSuggestions: number) => void
  onSendToWhatIf?: (suggestion: HedgeSuggestionDto) => void
}

const TARGET_OPTIONS: { value: HedgeTarget; label: string }[] = [
  { value: 'DELTA', label: 'Delta' },
  { value: 'GAMMA', label: 'Gamma' },
  { value: 'VEGA', label: 'Vega' },
  { value: 'VAR', label: 'VaR' },
]

function greekDelta(before: number, after: number): number {
  return after - before
}

function greekDeltaClass(before: number, after: number): string {
  const delta = greekDelta(before, after)
  if (Math.abs(delta) < 0.001) return 'text-gray-500 dark:text-gray-400'
  return delta < 0 ? 'text-green-600 dark:text-green-400' : 'text-red-600 dark:text-red-400'
}

function GreekImpactRow({
  label,
  before,
  after,
}: {
  label: string
  before: number
  after: number
}) {
  const delta = greekDelta(before, after)
  return (
    <div className="flex items-center justify-between text-xs py-0.5">
      <span className="text-gray-600 dark:text-gray-400 w-12">{label}</span>
      <span className="text-gray-800 dark:text-gray-200 w-20 text-right">{formatNum(before, 1)}</span>
      <span className="text-gray-400 dark:text-gray-500 mx-1">→</span>
      <span className="text-gray-800 dark:text-gray-200 w-20 text-right">{formatNum(after, 1)}</span>
      <span className={`w-20 text-right font-medium ${greekDeltaClass(before, after)}`}>
        {delta >= 0 ? '+' : ''}{formatNum(delta, 1)}
      </span>
    </div>
  )
}

function GreekImpactCard({ impact }: { impact: GreekImpactDto }) {
  return (
    <div data-testid="greek-impact-table" className="mt-2 p-2 bg-gray-50 dark:bg-gray-800 rounded text-xs">
      <div className="flex items-center justify-between text-gray-500 dark:text-gray-400 mb-1 text-[10px] uppercase tracking-wide">
        <span className="w-12">Greek</span>
        <span className="w-20 text-right">Before</span>
        <span className="mx-1 invisible">→</span>
        <span className="w-20 text-right">After</span>
        <span className="w-20 text-right">Change</span>
      </div>
      <GreekImpactRow label="Delta" before={impact.deltaBefore} after={impact.deltaAfter} />
      <GreekImpactRow label="Gamma" before={impact.gammaBefore} after={impact.gammaAfter} />
      <GreekImpactRow label="Vega" before={impact.vegaBefore} after={impact.vegaAfter} />
      <GreekImpactRow label="Theta" before={impact.thetaBefore} after={impact.thetaAfter} />
      <GreekImpactRow label="Rho" before={impact.rhoBefore} after={impact.rhoAfter} />
    </div>
  )
}

function SuggestionCard({
  suggestion,
  rank,
  computedAt,
  onSendToWhatIf,
}: {
  suggestion: HedgeSuggestionDto
  rank: number
  computedAt?: string
  onSendToWhatIf?: (s: HedgeSuggestionDto) => void
}) {
  const [showGreeks, setShowGreeks] = useState(false)
  const isStale = suggestion.dataQuality === 'STALE'

  const card = (
    <div
      data-testid={`suggestion-${rank}`}
      className="border border-gray-200 dark:border-gray-700 rounded-lg p-3 mb-2"
    >
      <div className="flex items-start justify-between">
        <div>
          <div className="flex items-center gap-2">
            <span className="text-xs font-medium text-gray-500 dark:text-gray-400">#{rank}</span>
            <span className="font-medium text-gray-900 dark:text-gray-100 text-sm" data-testid={`suggestion-instrument-${rank}`}>
              {suggestion.instrumentId}
            </span>
            <span className="text-xs text-gray-500 dark:text-gray-400">{suggestion.instrumentType}</span>
            {isStale && (
              <span className="inline-flex items-center gap-0.5 text-[10px] bg-yellow-100 dark:bg-yellow-900 text-yellow-800 dark:text-yellow-200 px-1 rounded" data-testid={`stale-badge-${rank}`}>
                <AlertCircle className="h-2.5 w-2.5" />
                STALE
              </span>
            )}
          </div>
          <div className="flex items-center gap-3 mt-1 text-xs text-gray-600 dark:text-gray-400">
            <span>
              <span className={`font-medium ${suggestion.side === 'BUY' ? 'text-green-600 dark:text-green-400' : 'text-red-600 dark:text-red-400'}`} data-testid={`suggestion-side-${rank}`}>
                {suggestion.side}
              </span>
              {' '}{formatNum(suggestion.quantity, 0)} units
            </span>
            <span className="text-gray-400">|</span>
            <span>Tier: <span className="font-medium text-gray-700 dark:text-gray-300">{suggestion.liquidityTier}</span></span>
          </div>
        </div>
        <div className="text-right">
          <div className="text-sm font-medium text-gray-900 dark:text-gray-100" data-testid={`suggestion-cost-${rank}`}>
            ${formatNum(suggestion.estimatedCost, 0)}
          </div>
          <div className="text-xs text-gray-500 dark:text-gray-400">est. cost</div>
          <div className="text-xs text-blue-600 dark:text-blue-400 font-medium mt-0.5" data-testid={`suggestion-reduction-${rank}`}>
            {(suggestion.targetReductionPct * 100).toFixed(0)}% reduction
          </div>
        </div>
      </div>

      <div className="flex items-center gap-2 mt-2">
        <button
          onClick={() => setShowGreeks((v) => !v)}
          className="text-xs text-blue-600 dark:text-blue-400 hover:underline"
          aria-expanded={showGreeks}
        >
          {showGreeks ? 'Hide' : 'Show'} Greek impact
        </button>
        {onSendToWhatIf && (
          <button
            onClick={() => onSendToWhatIf(suggestion)}
            className="text-xs text-purple-600 dark:text-purple-400 hover:underline"
            data-testid={`send-to-what-if-${rank}`}
          >
            Send to What-If
          </button>
        )}
      </div>

      {showGreeks && <GreekImpactCard impact={suggestion.greekImpact} />}
    </div>
  )

  return (
    <StalePanelWrapper
      stale={isStale}
      computedAt={computedAt}
      data-testid={`suggestion-stale-wrapper-${rank}`}
      className="mb-2"
    >
      {card}
    </StalePanelWrapper>
  )
}

export function HedgeRecommendationPanel({
  open,
  onClose,
  bookId,
  recommendation,
  loading,
  error,
  onSuggest,
  onSendToWhatIf,
}: HedgeRecommendationPanelProps) {
  const [target, setTarget] = useState<HedgeTarget>('DELTA')
  // reductionPctDisplay is stored as a percentage string (e.g. "80" means 80%)
  const [reductionPctDisplay, setReductionPctDisplay] = useState('80')
  const [maxSuggestions, setMaxSuggestions] = useState('5')
  const [validationError, setValidationError] = useState<string | null>(null)

  if (!open) return null

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const displayVal = parseFloat(reductionPctDisplay)
    if (isNaN(displayVal) || displayVal <= 0 || displayVal > 100) {
      setValidationError('Reduction % must be between 0 and 100 (exclusive)')
      return
    }
    setValidationError(null)
    const pct = displayVal / 100
    const max = parseInt(maxSuggestions, 10)
    onSuggest(target, pct, isNaN(max) || max < 1 ? 5 : max)
  }

  return (
    <div
      className="fixed inset-y-0 right-0 w-[480px] bg-white dark:bg-gray-900 shadow-2xl z-50 flex flex-col"
      role="dialog"
      aria-label="Hedge Recommendation Panel"
      data-testid="hedge-recommendation-panel"
    >
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-3 border-b border-gray-200 dark:border-gray-700">
        <div className="flex items-center gap-2">
          <TrendingDown className="h-5 w-5 text-blue-600 dark:text-blue-400" />
          <h2 className="text-base font-semibold text-gray-900 dark:text-gray-100">
            Hedge Suggestions
          </h2>
          {bookId && (
            <span className="text-xs text-gray-500 dark:text-gray-400 bg-gray-100 dark:bg-gray-800 px-1.5 py-0.5 rounded">
              {bookId}
            </span>
          )}
        </div>
        <button
          onClick={onClose}
          aria-label="Close hedge recommendation panel"
          className="p-1 rounded hover:bg-gray-100 dark:hover:bg-gray-800 text-gray-500 dark:text-gray-400"
        >
          <X className="h-4 w-4" />
        </button>
      </div>

      {/* Form */}
      <form onSubmit={handleSubmit} className="px-4 py-3 border-b border-gray-200 dark:border-gray-700">
        <div className="grid grid-cols-3 gap-3">
          <div>
            <label className="block text-xs text-gray-600 dark:text-gray-400 mb-1" htmlFor="hedge-target">
              Target Metric
            </label>
            <select
              id="hedge-target"
              value={target}
              onChange={(e) => setTarget(e.target.value as HedgeTarget)}
              className="w-full text-sm border border-gray-300 dark:border-gray-600 rounded px-2 py-1.5 bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100"
              data-testid="hedge-target-select"
            >
              {TARGET_OPTIONS.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className="block text-xs text-gray-600 dark:text-gray-400 mb-1" htmlFor="hedge-reduction">
              Reduction %
            </label>
            <input
              id="hedge-reduction"
              type="number"
              min="1"
              max="100"
              step="1"
              value={reductionPctDisplay}
              onChange={(e) => setReductionPctDisplay(e.target.value)}
              placeholder="80"
              className="w-full text-sm border border-gray-300 dark:border-gray-600 rounded px-2 py-1.5 bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100"
              data-testid="hedge-reduction-input"
            />
          </div>
          <div>
            <label className="block text-xs text-gray-600 dark:text-gray-400 mb-1" htmlFor="hedge-max">
              Max Results
            </label>
            <input
              id="hedge-max"
              type="number"
              min="1"
              max="20"
              value={maxSuggestions}
              onChange={(e) => setMaxSuggestions(e.target.value)}
              className="w-full text-sm border border-gray-300 dark:border-gray-600 rounded px-2 py-1.5 bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100"
              data-testid="hedge-max-input"
            />
          </div>
        </div>
        {validationError && (
          <p className="mt-1 text-xs text-red-600 dark:text-red-400" role="alert">
            {validationError}
          </p>
        )}
        <div className="mt-2">
          <Button
            type="submit"
            disabled={loading || !bookId}
            className="w-full"
            data-testid="suggest-hedge-button"
          >
            {loading ? (
              <span className="flex items-center justify-center gap-2">
                <Loader2 className="h-4 w-4 animate-spin" />
                Calculating...
              </span>
            ) : (
              'Suggest Hedge'
            )}
          </Button>
        </div>
      </form>

      {/* Results */}
      <div className="flex-1 overflow-y-auto px-4 py-3">
        {error && (
          <div className="mb-3">
            <ErrorCard message={error} data-testid="hedge-error" />
          </div>
        )}

        {recommendation && (
          <div>
            <div className="flex items-center justify-between mb-3">
              <div className="text-sm text-gray-700 dark:text-gray-300">
                <span className="font-medium">{recommendation.suggestions.length}</span> suggestions for{' '}
                <span className="font-medium">{recommendation.targetMetric}</span> neutralisation
              </div>
              <div className="text-xs text-gray-500 dark:text-gray-400">
                Total est. cost: <span className="font-medium">${formatNum(recommendation.totalEstimatedCost, 0)}</span>
              </div>
            </div>

            {recommendation.isExpired && (
              <div className="text-xs text-amber-600 dark:text-amber-400 bg-amber-50 dark:bg-amber-900/20 px-2 py-1 rounded mb-2" data-testid="expired-warning">
                This recommendation has expired. Request a new one.
              </div>
            )}

            {recommendation.suggestions.length === 0 ? (
              <Card className="p-4 text-center text-sm text-gray-500 dark:text-gray-400" data-testid="no-suggestions">
                No suitable instruments found matching your constraints.
              </Card>
            ) : (
              recommendation.suggestions.map((s, i) => (
                <SuggestionCard
                  key={s.instrumentId}
                  suggestion={s}
                  rank={i + 1}
                  computedAt={recommendation.requestedAt}
                  onSendToWhatIf={onSendToWhatIf}
                />
              ))
            )}
          </div>
        )}

        {!recommendation && !loading && !error && (
          <div className="text-center text-sm text-gray-500 dark:text-gray-400 mt-8" data-testid="empty-state">
            Configure your target metric and reduction percentage above, then click{' '}
            <strong>Suggest Hedge</strong> to get instrument recommendations.
          </div>
        )}
      </div>
    </div>
  )
}

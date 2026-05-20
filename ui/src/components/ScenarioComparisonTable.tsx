import { useState } from 'react'
import { ChevronRight, ChevronDown } from 'lucide-react'
import type { StressScenarioDto, StressTestResultDto } from '../types'
import { formatCurrency } from '../utils/format'
import { ScenarioTooltip } from './ScenarioTooltip'
import { ExplainButton } from './ExplainButton'
import { AIInsightPanel } from './AIInsightPanel'
import { useCopilotContext } from '../hooks/useCopilotContext'
import { chat, type ChatChunk, type ChatRequest } from '../api/copilot'
import { buildScenarioExplainContext } from './buildScenarioExplainContext'

/** Signature of the injectable `chatFn` — mirrors `chat` in `api/copilot`. */
type ChatFn = (
  request: ChatRequest,
  options?: { signal?: AbortSignal },
) => ReadableStream<ChatChunk>

interface ScenarioComparisonTableProps {
  results: StressTestResultDto[]
  selectedScenario: string | null
  onSelectScenario: (scenario: string | null) => void
  checkedScenarios?: Set<string>
  onToggleCheck?: (scenario: string) => void
  scenarioMetadata?: StressScenarioDto[]
  /**
   * Dependency-injection seam for the streaming `chat()` client. Tests
   * substitute a fake; production callers leave it unset and the real
   * `chat` import is used (plan §9.4 — inline explainer on scenario rows).
   */
  chatFn?: ChatFn
}

const CATEGORY_BADGE_STYLES: Record<string, string> = {
  REGULATORY_MANDATED: 'bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-300',
  INTERNAL_APPROVED: 'bg-slate-100 text-slate-700 dark:bg-slate-700 dark:text-slate-300',
  SUPERVISORY_REQUESTED: 'bg-purple-100 text-purple-800 dark:bg-purple-900 dark:text-purple-300',
}

const CATEGORY_LABELS: Record<string, string> = {
  REGULATORY_MANDATED: 'Regulatory',
  INTERNAL_APPROVED: 'Internal',
  SUPERVISORY_REQUESTED: 'Supervisory',
}

function formatMultiplier(baseVar: string, stressedVar: string): string {
  const base = Number(baseVar)
  const stressed = Number(stressedVar)
  if (base === 0) return '-'
  return `${(stressed / base).toFixed(1)}x`
}

function breachSummary(result: StressTestResultDto): { count: number; worst: string } {
  const breaches = result.limitBreaches ?? []
  if (breaches.length === 0) return { count: 0, worst: 'OK' }
  const hasBreached = breaches.some((b) => b.breachSeverity === 'BREACHED')
  const hasWarning = breaches.some((b) => b.breachSeverity === 'WARNING')
  const worst = hasBreached ? 'BREACHED' : hasWarning ? 'WARNING' : 'OK'
  const count = breaches.filter((b) => b.breachSeverity !== 'OK').length
  return { count, worst }
}

const BREACH_BADGE_STYLES: Record<string, string> = {
  OK: 'bg-green-100 text-green-800',
  WARNING: 'bg-yellow-100 text-yellow-800',
  BREACHED: 'bg-red-100 text-red-800',
}

const BREACH_BORDER: Record<string, string> = {
  BREACHED: 'border-l-4 border-l-red-500',
  WARNING: 'border-l-4 border-l-amber-400',
}

export function ScenarioComparisonTable({
  results,
  selectedScenario,
  onSelectScenario,
  checkedScenarios,
  onToggleCheck,
  scenarioMetadata,
  chatFn = chat,
}: ScenarioComparisonTableProps) {
  const copilotContext = useCopilotContext()
  // Inline explainer state (plan §9.4). At most one scenario-row explainer
  // is ever open — `explainScenario` identifies which row's panel is
  // showing and `explainStream` is the live `/chat` token stream feeding it.
  const [explainScenario, setExplainScenario] = useState<string | null>(null)
  const [explainStream, setExplainStream] =
    useState<ReadableStream<ChatChunk> | null>(null)

  /**
   * Open the inline explainer for a specific scenario result row.
   *
   * Double-click protection: a second click on the scenario whose panel is
   * already open is a no-op — neither a duplicate panel nor a duplicate
   * `/chat` request is created. Opening a *different* scenario's explainer
   * replaces the current one ("only one panel open"), plan §9.4.
   */
  function handleExplainScenario(result: StressTestResultDto) {
    if (explainScenario === result.scenarioName) return
    const stream = chatFn({
      message: `Explain the ${result.scenarioName.replace(/_/g, ' ')} stress scenario — what drove the P&L impact?`,
      page_context: buildScenarioExplainContext(copilotContext, result),
    })
    setExplainScenario(result.scenarioName)
    setExplainStream(stream)
  }

  function closeExplainScenario() {
    setExplainScenario(null)
    setExplainStream(null)
  }

  if (results.length === 0) {
    return (
      <p data-testid="no-results" className="text-sm text-slate-500">
        No stress test results yet. Click &quot;Run All Scenarios&quot; to see the comparison.
      </p>
    )
  }

  const hasAnyBreaches = results.some((r) => (r.limitBreaches ?? []).length > 0)
  const showCheckboxes = !!onToggleCheck
  const explainedResult =
    explainScenario !== null
      ? results.find((r) => r.scenarioName === explainScenario) ?? null
      : null

  return (
    <div data-testid="scenario-comparison-table" className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b text-left text-slate-600 dark:text-slate-400">
            {showCheckboxes && <th className="py-2 w-8"></th>}
            <th className="py-2 w-8"></th>
            <th className="py-2">Scenario</th>
            <th className="py-2">Category</th>
            <th className="py-2 text-right">Base VaR</th>
            <th className="py-2 text-right">Stressed VaR</th>
            <th className="py-2 text-right">VaR Multiplier</th>
            <th className="py-2 text-right">P&amp;L Impact</th>
            {hasAnyBreaches && <th className="py-2 text-center">Limits</th>}
            {/* Rightmost action column — per-row inline explainer (plan §9.4). */}
            <th className="py-2 w-8" aria-label="Explain"></th>
          </tr>
        </thead>
        <tbody>
          {results.map((r) => {
            const isSelected = selectedScenario === r.scenarioName
            const isChecked = checkedScenarios?.has(r.scenarioName) ?? false
            const pnlValue = Number(r.pnlImpact)
            const isLoss = pnlValue < 0
            const { count, worst } = breachSummary(r)
            const breachBorder = BREACH_BORDER[worst] || ''
            return (
              <tr
                key={r.scenarioName}
                data-testid="scenario-row"
                className={`border-b cursor-pointer transition-colors ${
                  isSelected
                    ? 'bg-indigo-50 dark:bg-indigo-900/20 border-l-2 border-l-indigo-500'
                    : breachBorder || 'hover:bg-slate-50 dark:hover:bg-slate-800'
                }`}
                onClick={() => onSelectScenario(isSelected ? null : r.scenarioName)}
              >
                {showCheckboxes && (
                  <td className="py-1.5">
                    <input
                      type="checkbox"
                      data-testid={`scenario-check-${r.scenarioName}`}
                      aria-label={`Compare ${r.scenarioName.replace(/_/g, ' ')}`}
                      checked={isChecked}
                      onChange={(e) => {
                        e.stopPropagation()
                        onToggleCheck?.(r.scenarioName)
                      }}
                      onClick={(e) => e.stopPropagation()}
                      className="rounded border-slate-300 text-indigo-600 focus:ring-indigo-500"
                    />
                  </td>
                )}
                <td className="py-1.5 text-slate-400">
                  {isSelected ? (
                    <ChevronDown className="h-4 w-4" />
                  ) : (
                    <ChevronRight className="h-4 w-4" />
                  )}
                </td>
                <td className="py-1.5 font-medium max-w-[240px] truncate">
                  {(() => {
                    const meta = scenarioMetadata?.find(
                      (s) => s.name === r.scenarioName || s.name.replace(/_/g, ' ') === r.scenarioName.replace(/_/g, ' '),
                    )
                    return meta ? (
                      <ScenarioTooltip
                        scenarioName={r.scenarioName}
                        description={meta.description}
                        shocks={meta.shocks}
                        lastRunAt={r.calculatedAt}
                        status={meta.status}
                        approvedBy={meta.approvedBy}
                      />
                    ) : (
                      r.scenarioName.replace(/_/g, ' ')
                    )
                  })()}
                </td>
                <td className="py-1.5" data-testid="scenario-category">
                  {(() => {
                    const meta = scenarioMetadata?.find(
                      (s) => s.name === r.scenarioName || s.name.replace(/_/g, ' ') === r.scenarioName.replace(/_/g, ' '),
                    )
                    const cat = meta?.scenarioCategory
                    if (!cat) return null
                    return (
                      <span className={`inline-flex items-center px-1.5 py-0.5 rounded text-xs font-medium ${CATEGORY_BADGE_STYLES[cat] ?? 'bg-slate-100 text-slate-600'}`}>
                        {CATEGORY_LABELS[cat] ?? cat}
                      </span>
                    )
                  })()}
                </td>
                <td className="py-1.5 text-right">{formatCurrency(r.baseVar)}</td>
                <td className="py-1.5 text-right font-medium text-red-600 dark:text-red-400">
                  {formatCurrency(r.stressedVar)}
                </td>
                <td data-testid="var-multiplier" className="py-1.5 text-right font-medium">
                  {formatMultiplier(r.baseVar, r.stressedVar)}
                </td>
                <td
                  data-testid="pnl-impact"
                  className={`py-1.5 text-right font-medium ${isLoss ? 'text-red-600 dark:text-red-400' : ''}`}
                >
                  {formatCurrency(r.pnlImpact)}
                </td>
                {hasAnyBreaches && (
                  <td className="py-1.5 text-center" data-testid="breach-badge">
                    <span
                      className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${BREACH_BADGE_STYLES[worst]}`}
                    >
                      {count > 0 ? `${count} ${worst === 'BREACHED' ? 'breach' : 'warn'}` : 'OK'}
                    </span>
                  </td>
                )}
                {/* Per-row inline explainer (plan §9.4). The click is stopped
                    from bubbling so it does not toggle the row selection. */}
                <td
                  className="py-1.5 w-8 text-right"
                  onClick={(e) => e.stopPropagation()}
                >
                  <ExplainButton
                    data-testid={`explain-scenario-${r.scenarioName}`}
                    label=""
                    ariaLabel={`Explain ${r.scenarioName.replace(/_/g, ' ')} stress scenario`}
                    onClick={() => handleExplainScenario(r)}
                    className="px-1.5 py-1 text-xs"
                  />
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>

      {/* Inline AI explainer panel — plan §9.4. At most one scenario row's
          panel is open at a time; opening another replaces this one. */}
      {explainedResult && explainStream && (
        <div
          data-testid={`scenario-explain-panel-${explainedResult.scenarioName}`}
          className="mt-2"
        >
          <AIInsightPanel
            stream={explainStream}
            title={`Explain — ${explainedResult.scenarioName.replace(/_/g, ' ')}`}
            onClose={closeExplainScenario}
          />
        </div>
      )}
    </div>
  )
}

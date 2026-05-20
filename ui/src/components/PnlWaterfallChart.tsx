import { useState } from 'react'
import type { PnlAttributionDto } from '../types'
import { formatSignedNum, pnlColorClass } from '../utils/format'
import { PNL_FACTOR_COLORS } from '../utils/factorColors'
import { useCopilotContext } from '../hooks/useCopilotContext'
import { chat, type ChatChunk, type ChatRequest } from '../api/copilot'
import { ExplainButton } from './ExplainButton'
import { AIInsightPanel } from './AIInsightPanel'

/** Signature of the injectable `chatFn` — mirrors `chat` in `api/copilot`. */
type ChatFn = (
  request: ChatRequest,
  options?: { signal?: AbortSignal },
) => ReadableStream<ChatChunk>

/** How many P&L drivers to attach to the explainer's `page_context`. */
const TOP_DRIVERS = 3

interface PnlWaterfallChartProps {
  data: PnlAttributionDto
  /**
   * Dependency-injection seam for the streaming `chat()` client. Tests
   * substitute a fake; production callers leave it unset and the real
   * `chat` import is used (plan §9.2).
   */
  chatFn?: ChatFn
}

interface FactorEntry {
  key: string
  label: string
  value: number
  color: string
}

const FACTOR_COLORS = PNL_FACTOR_COLORS

/**
 * Build the top-N P&L drivers — the Greek factors (excluding `Total`)
 * sorted by absolute contribution, largest first. Surfaced to the
 * inline explainer so the model can lead with the dominant drivers.
 */
function topDrivers(
  factors: FactorEntry[],
  n: number,
): { factor: string; value: number }[] {
  return factors
    .filter((f) => f.key !== 'total')
    .slice()
    .sort((a, b) => Math.abs(b.value) - Math.abs(a.value))
    .slice(0, n)
    .map((f) => ({ factor: f.label, value: f.value }))
}

/**
 * Build the `page_context` for the P&L attribution inline explainer.
 *
 * Extends the ambient copilot context (`useCopilotContext()`) with the
 * attribution period date and the top-N P&L drivers so the model can
 * speak to *why* P&L moved (plan §9.2).
 */
function buildExplainContext(
  base: Record<string, unknown>,
  data: PnlAttributionDto,
  factors: FactorEntry[],
): Record<string, unknown> {
  return {
    ...base,
    page: 'pnl-attribution',
    book_id: data.bookId,
    date: data.date,
    total_pnl: Number(data.totalPnl),
    top_drivers: topDrivers(factors, TOP_DRIVERS),
  }
}

export function PnlWaterfallChart({ data, chatFn = chat }: PnlWaterfallChartProps) {
  const copilotContext = useCopilotContext()

  // Inline explainer state (plan §9.2). At most one panel is ever open
  // for the chart; `explainStream` is the live `/chat` token stream.
  const [explainOpen, setExplainOpen] = useState(false)
  const [explainStream, setExplainStream] = useState<ReadableStream<ChatChunk> | null>(null)

  const factors: FactorEntry[] = [
    { key: 'delta', label: 'Delta', value: Number(data.deltaPnl), color: FACTOR_COLORS.delta },
    { key: 'gamma', label: 'Gamma', value: Number(data.gammaPnl), color: FACTOR_COLORS.gamma },
    { key: 'vega', label: 'Vega', value: Number(data.vegaPnl), color: FACTOR_COLORS.vega },
    { key: 'theta', label: 'Theta', value: Number(data.thetaPnl), color: FACTOR_COLORS.theta },
    { key: 'rho', label: 'Rho', value: Number(data.rhoPnl), color: FACTOR_COLORS.rho },
    { key: 'unexplained', label: 'Unexplained', value: Number(data.unexplainedPnl), color: FACTOR_COLORS.unexplained },
    { key: 'total', label: 'Total', value: Number(data.totalPnl), color: FACTOR_COLORS.total },
  ]

  const absValues = factors.map((f) => Math.abs(f.value))
  const maxAbsValue = Math.max(...absValues, 1)

  /**
   * Open the inline explainer.
   *
   * Double-click protection: a second click while the panel is already
   * open is a no-op — neither a duplicate panel nor a duplicate `/chat`
   * request is created (plan §9.2).
   */
  const handleExplain = () => {
    if (explainOpen) return
    const stream = chatFn({
      message: 'Explain this P&L attribution — what drove the move?',
      page_context: buildExplainContext(copilotContext, data, factors),
    })
    setExplainOpen(true)
    setExplainStream(stream)
  }

  const closeExplain = () => {
    setExplainOpen(false)
    setExplainStream(null)
  }

  return (
    <div data-testid="pnl-waterfall-container">
      {/* Chart header — explain affordance sits above the waterfall body. */}
      <div className="flex items-center justify-end pb-2">
        <ExplainButton
          data-testid="explain-pnl-attribution"
          label="Explain"
          ariaLabel="Explain P&L attribution"
          onClick={handleExplain}
          className="px-2 py-1 text-xs"
        />
      </div>

      {explainOpen && explainStream && (
        <div data-testid="pnl-explain-panel" className="pb-3">
          <AIInsightPanel
            stream={explainStream}
            title="Explain — P&L Attribution"
            onClose={closeExplain}
          />
        </div>
      )}

      <div data-testid="waterfall-chart" className="space-y-2 overflow-hidden">
        {factors.map((factor) => {
          const barWidthPercent = (Math.abs(factor.value) / maxAbsValue) * 50
          const isPositive = factor.value >= 0
          const valueStr = factor.value.toString()

          return (
            <div
              key={factor.key}
              data-testid={`waterfall-bar-${factor.key}`}
              className="flex items-center gap-3"
            >
              <span className="w-24 text-right text-sm font-medium text-slate-600 shrink-0">
                {factor.label}
              </span>

              <div className="flex-1 min-w-0 relative h-7">
                <div className="absolute inset-0 flex items-center">
                  {/* Zero line in the center */}
                  <div className="absolute left-1/2 top-0 bottom-0 w-px bg-slate-300" />

                  {/* Bar */}
                  {isPositive ? (
                    <div
                      className="absolute h-5 rounded-r"
                      style={{
                        left: '50%',
                        width: `${barWidthPercent}%`,
                        backgroundColor: factor.color,
                      }}
                    />
                  ) : (
                    <div
                      className="absolute h-5 rounded-l"
                      style={{
                        right: '50%',
                        width: `${barWidthPercent}%`,
                        backgroundColor: factor.color,
                      }}
                    />
                  )}
                </div>
              </div>

              <span
                data-testid={`waterfall-value-${factor.key}`}
                className={`w-36 text-right text-sm font-mono tabular-nums whitespace-nowrap shrink-0 ${pnlColorClass(valueStr)}`}
              >
                {formatSignedNum(factor.value)}
              </span>
            </div>
          )
        })}
      </div>
    </div>
  )
}

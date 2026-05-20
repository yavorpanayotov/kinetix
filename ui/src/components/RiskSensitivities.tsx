import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Info, X } from 'lucide-react'
import type { GreeksResultDto } from '../types'
import { useClickOutside } from '../hooks/useClickOutside'
import { formatNum } from '../utils/format'
import { formatAssetClassLabel } from '../utils/formatAssetClass'
import { formatCompactCurrency } from '../utils/formatCompactCurrency'
import { useCopilotContext } from '../hooks/useCopilotContext'
import { chat, type ChatChunk, type ChatRequest } from '../api/copilot'
import { ExplainButton } from './ExplainButton'
import { AIInsightPanel } from './AIInsightPanel'
import { buildGreeksExplainContext } from './buildGreeksExplainContext'

type Greek = 'delta' | 'gamma' | 'vega' | 'theta' | 'rho'

/** Signature of the injectable `chatFn` — mirrors `chat` in `api/copilot`. */
type ChatFn = (
  request: ChatRequest,
  options?: { signal?: AbortSignal },
) => ReadableStream<ChatChunk>

const greekDescriptions: Record<Greek, string> = {
  delta: "Estimated change in portfolio VaR for a $1 move in the underlying. A positive value means VaR increases as the underlying rises.",
  gamma: "Rate of change of Delta VaR sensitivity — captures convexity risk. Measures how quickly the delta sensitivity itself changes with the underlying.",
  vega: "Estimated change in portfolio VaR for a 1% move in implied volatility. Reflects how options risk changes with the volatility surface.",
  theta: "Estimated change in portfolio VaR for each day that passes (time decay effect on risk). Captures how option time value erodes overnight.",
  rho: "Estimated change in portfolio VaR for a 1 basis point move in interest rates. Reflects sensitivity to yield curve shifts.",
}

interface RiskSensitivitiesProps {
  greeksResult: GreeksResultDto
  pvValue?: string | null
  /**
   * Dependency-injection seam for the streaming `chat()` client. Tests
   * substitute a fake; production callers leave it unset and the real
   * `chat` import is used (plan §9.5).
   */
  chatFn?: ChatFn
}

export function RiskSensitivities({ greeksResult, pvValue, chatFn = chat }: RiskSensitivitiesProps) {
  const [openPopover, setOpenPopover] = useState<Greek | null>(null)
  const containerRef = useRef<HTMLDivElement>(null)
  const copilotContext = useCopilotContext()

  // Inline explainer state (plan §9.5). At most one panel is ever open
  // for the aggregate Greeks card; `explainStream` is the live `/chat`
  // token stream.
  const [explainOpen, setExplainOpen] = useState(false)
  const [explainStream, setExplainStream] = useState<ReadableStream<ChatChunk> | null>(null)

  const closePopover = useCallback(() => setOpenPopover(null), [])
  useClickOutside(containerRef, closePopover)

  /**
   * Open the inline explainer for the aggregate Greeks card.
   *
   * Double-click protection: a second click while the panel is already
   * open is a no-op — neither a duplicate panel nor a duplicate `/chat`
   * request is created (plan §9.5).
   */
  const handleExplain = () => {
    if (explainOpen) return
    const stream = chatFn({
      message: 'Explain the aggregate Greeks for this book — what is the net sensitivity?',
      page_context: buildGreeksExplainContext(copilotContext, greeksResult),
    })
    setExplainOpen(true)
    setExplainStream(stream)
  }

  const closeExplain = () => {
    setExplainOpen(false)
    setExplainStream(null)
  }

  useEffect(() => {
    if (!openPopover) return
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpenPopover(null)
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [openPopover])

  const togglePopover = (greek: Greek) => {
    setOpenPopover(prev => prev === greek ? null : greek)
  }

  const renderHeader = (label: string, greek: Greek, className: string) => (
    <th className={`${className} relative`}>
      <span className="inline-flex items-center gap-1">
        {label}
        <Info
          data-testid={`greek-info-${greek}`}
          className="h-3 w-3 cursor-pointer text-slate-400 hover:text-slate-600 transition-colors"
          onClick={() => togglePopover(greek)}
        />
      </span>
      {openPopover === greek && (
        <span
          data-testid={`greek-popover-${greek}`}
          className="absolute top-full left-0 mt-1 w-64 rounded bg-slate-800 px-3 py-2 text-xs font-normal text-white text-justify shadow-lg z-10"
        >
          <button data-testid={`greek-popover-${greek}-close`} className="float-right ml-2 text-slate-400 hover:text-white" onClick={closePopover}><X className="h-3 w-3" /></button>
          {greekDescriptions[greek]}
        </span>
      )}
    </th>
  )

  const totals = useMemo(() => {
    let delta = 0
    let gamma = 0
    let vega = 0
    for (const g of greeksResult.assetClassGreeks) {
      delta += Number(g.delta)
      gamma += Number(g.gamma)
      vega += Number(g.vega)
    }
    return { delta: delta.toFixed(2), gamma: gamma.toFixed(2), vega: vega.toFixed(2) }
  }, [greeksResult])

  return (
    <div ref={containerRef} data-testid="risk-sensitivities">
      {/* Card header — aggregate-Greeks explain affordance (plan §9.5). */}
      <div className="flex items-center justify-between pb-2">
        <span className="text-xs font-semibold text-slate-600">Greeks</span>
        <ExplainButton
          data-testid="explain-greeks"
          label="Explain"
          ariaLabel="Explain aggregate Greeks"
          onClick={handleExplain}
          className="px-2 py-1 text-xs"
        />
      </div>

      {explainOpen && explainStream && (
        <div data-testid="greeks-explain-panel" className="pb-3">
          <AIInsightPanel
            stream={explainStream}
            title="Explain — Aggregate Greeks"
            onClose={closeExplain}
          />
        </div>
      )}

      {pvValue != null && (
        <div data-testid="pv-display" className="text-xs mb-2">
          <span className="text-slate-600">PV: </span>
          <span className="font-medium">{formatCompactCurrency(Number(pvValue))}</span>
        </div>
      )}
      <table data-testid="greeks-heatmap" className="text-xs">
        <thead>
          <tr className="border-b text-left text-slate-600">
            <th className="py-1 pr-5">Asset Class</th>
            {renderHeader('Delta ($/1%)', 'delta', 'py-1 px-4 text-right')}
            {renderHeader('Gamma', 'gamma', 'py-1 px-4 text-right')}
            {renderHeader('Vega ($/1pp)', 'vega', 'py-1 px-4 text-right')}
          </tr>
        </thead>
        <tbody>
          {greeksResult.assetClassGreeks.map((g) => (
            <tr key={g.assetClass} data-testid={`greeks-row-${g.assetClass}`} className="border-b hover:bg-slate-50 transition-colors">
              <td className="py-1 pr-5 font-medium">{formatAssetClassLabel(g.assetClass)}</td>
              <td className="py-1 px-4 text-right">{formatNum(g.delta)}</td>
              <td className="py-1 px-4 text-right">{formatNum(g.gamma)}</td>
              <td className="py-1 px-4 text-right">{formatNum(g.vega)}</td>
            </tr>
          ))}
          <tr data-testid="greeks-row-TOTAL" className="border-t border-slate-300 font-semibold">
            <td className="py-1 pr-5">Total</td>
            <td className="py-1 px-4 text-right">{formatNum(totals.delta)}</td>
            <td className="py-1 px-4 text-right">{formatNum(totals.gamma)}</td>
            <td className="py-1 px-4 text-right">{formatNum(totals.vega)}</td>
          </tr>
        </tbody>
      </table>
      <div className="flex gap-4 mt-2">
        <div data-testid="greek-summary-theta" className="flex flex-col items-start gap-0.5 px-3 py-1.5 bg-slate-50 rounded text-xs">
          <span className="inline-flex items-center gap-1 text-slate-500 font-medium">
            Theta ($/day)
            <Info
              data-testid="greek-info-theta"
              className="h-3 w-3 cursor-pointer text-slate-400 hover:text-slate-600 transition-colors"
              onClick={() => togglePopover('theta')}
            />
          </span>
          {openPopover === 'theta' && (
            <span
              data-testid="greek-popover-theta"
              className="absolute mt-6 w-64 rounded bg-slate-800 px-3 py-2 text-xs font-normal text-white text-justify shadow-lg z-10"
            >
              <button data-testid="greek-popover-theta-close" className="float-right ml-2 text-slate-400 hover:text-white" onClick={closePopover}><X className="h-3 w-3" /></button>
              {greekDescriptions.theta}
            </span>
          )}
          <span className="font-semibold text-slate-800">{formatNum(greeksResult.theta)}</span>
        </div>
        <div data-testid="greek-summary-rho" className="flex flex-col items-start gap-0.5 px-3 py-1.5 bg-slate-50 rounded text-xs">
          <span className="inline-flex items-center gap-1 text-slate-500 font-medium">
            Rho ($/bp)
            <Info
              data-testid="greek-info-rho"
              className="h-3 w-3 cursor-pointer text-slate-400 hover:text-slate-600 transition-colors"
              onClick={() => togglePopover('rho')}
            />
          </span>
          {openPopover === 'rho' && (
            <span
              data-testid="greek-popover-rho"
              className="absolute mt-6 w-64 rounded bg-slate-800 px-3 py-2 text-xs font-normal text-white text-justify shadow-lg z-10"
            >
              <button data-testid="greek-popover-rho-close" className="float-right ml-2 text-slate-400 hover:text-white" onClick={closePopover}><X className="h-3 w-3" /></button>
              {greekDescriptions.rho}
            </span>
          )}
          <span className="font-semibold text-slate-800">{formatNum(greeksResult.rho)}</span>
        </div>
      </div>
      <p data-testid="greeks-footnote" className="text-[10px] text-slate-400 mt-1">
        Asset-class sensitivities show change in VaR per unit bump. Per-instrument Greeks for options use analytical Black-Scholes. Hover headers for details.
      </p>
    </div>
  )
}

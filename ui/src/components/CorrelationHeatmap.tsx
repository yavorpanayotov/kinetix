import { useState } from 'react'
import { useCopilotContext } from '../hooks/useCopilotContext'
import { chat, type ChatChunk, type ChatRequest } from '../api/copilot'
import { ExplainButton } from './ExplainButton'
import { AIInsightPanel } from './AIInsightPanel'
import { buildCorrelationExplainContext } from './buildCorrelationExplainContext'

/** Signature of the injectable `chatFn` — mirrors `chat` in `api/copilot`. */
type ChatFn = (
  request: ChatRequest,
  options?: { signal?: AbortSignal },
) => ReadableStream<ChatChunk>

const ASSET_CLASSES = ['EQUITY', 'FIXED_INCOME', 'FX', 'COMMODITY', 'DERIVATIVE']

const CORRELATION_DATA: Record<string, Record<string, number>> = {
  EQUITY:       { EQUITY: 1.00, FIXED_INCOME: -0.20, FX: 0.30, COMMODITY: 0.40, DERIVATIVE: 0.70 },
  FIXED_INCOME: { EQUITY: -0.20, FIXED_INCOME: 1.00, FX: -0.10, COMMODITY: -0.05, DERIVATIVE: -0.15 },
  FX:           { EQUITY: 0.30, FIXED_INCOME: -0.10, FX: 1.00, COMMODITY: 0.25, DERIVATIVE: 0.20 },
  COMMODITY:    { EQUITY: 0.40, FIXED_INCOME: -0.05, FX: 0.25, COMMODITY: 1.00, DERIVATIVE: 0.35 },
  DERIVATIVE:   { EQUITY: 0.70, FIXED_INCOME: -0.15, FX: 0.20, COMMODITY: 0.35, DERIVATIVE: 1.00 },
}

const SHORT_LABELS: Record<string, string> = {
  EQUITY: 'Equity',
  FIXED_INCOME: 'Fixed Inc.',
  FX: 'FX',
  COMMODITY: 'Commodity',
  DERIVATIVE: 'Deriv.',
}

interface CorrelationHeatmapProps {
  assetClasses?: string[]
  /**
   * Dependency-injection seam for the streaming `chat()` client. Tests
   * substitute a fake; production callers leave it unset and the real
   * `chat` import is used (plan §9.5).
   */
  chatFn?: ChatFn
}

function getCellColor(value: number, isDiagonal: boolean): string {
  if (isDiagonal) return '#e2e8f0'
  if (value === 0) return '#ffffff'
  if (value > 0) {
    // Interpolate white → blue (#3b82f6)
    const r = Math.round(255 - (255 - 0x3b) * value)
    const g = Math.round(255 - (255 - 0x82) * value)
    const b = Math.round(255 - (255 - 0xf6) * value)
    return `rgb(${r}, ${g}, ${b})`
  }
  // Interpolate white → red (#ef4444)
  const t = Math.abs(value)
  const r = Math.round(255 - (255 - 0xef) * t)
  const g = Math.round(255 - (255 - 0x44) * t)
  const b = Math.round(255 - (255 - 0x44) * t)
  return `rgb(${r}, ${g}, ${b})`
}

function getTextColor(value: number, isDiagonal: boolean): string {
  if (isDiagonal) return '#475569'
  if (Math.abs(value) > 0.6) return '#ffffff'
  return '#334155'
}

export function CorrelationHeatmap({ assetClasses, chatFn = chat }: CorrelationHeatmapProps) {
  const classes = assetClasses
    ? ASSET_CLASSES.filter((ac) => assetClasses.includes(ac))
    : ASSET_CLASSES

  const copilotContext = useCopilotContext()

  // Inline explainer state (plan §9.5). At most one panel is ever open
  // for the matrix; `explainStream` is the live `/chat` token stream.
  // `explainBusy` tracks the in-flight wait so the trigger button can
  // surface a spinner while the model warms up (Opus can sit silent for
  // 10+ s before the first delta).
  const [explainOpen, setExplainOpen] = useState(false)
  const [explainStream, setExplainStream] = useState<ReadableStream<ChatChunk> | null>(null)
  const [explainBusy, setExplainBusy] = useState(false)

  /**
   * Open the matrix-level inline explainer, focused on correlation
   * breaks derived from the matrix already on screen.
   *
   * Double-click protection: a second click while the panel is already
   * open is a no-op — neither a duplicate panel nor a duplicate `/chat`
   * request is created (plan §9.5).
   */
  const handleExplain = () => {
    if (explainOpen) return
    const stream = chatFn({
      message: 'Explain the correlation breaks in this matrix — where is diversification weakest?',
      page_context: buildCorrelationExplainContext(
        copilotContext,
        classes,
        CORRELATION_DATA,
      ),
    })
    setExplainOpen(true)
    setExplainStream(stream)
    setExplainBusy(true)
  }

  const closeExplain = () => {
    setExplainOpen(false)
    setExplainStream(null)
    setExplainBusy(false)
  }

  const handleStreamComplete = () => {
    setExplainBusy(false)
  }

  const cellWidth = 52
  const cellHeight = 40
  const labelColWidth = 70
  const labelRowHeight = 28
  const svgWidth = labelColWidth + classes.length * cellWidth
  const svgHeight = labelRowHeight + classes.length * cellHeight

  return (
    <div data-testid="correlation-heatmap" className="rounded-lg border border-slate-200 bg-white p-4">
      {/* Matrix header — matrix-level explain affordance (plan §9.5). */}
      <div className="flex items-center justify-between mb-3">
        <h3 className="text-sm font-semibold text-slate-700">Correlation Matrix</h3>
        <ExplainButton
          data-testid="explain-correlation-matrix"
          label="Explain"
          ariaLabel="Explain correlation breaks"
          onClick={handleExplain}
          isBusy={explainBusy}
          className="px-2 py-1 text-xs"
        />
      </div>

      {explainOpen && explainStream && (
        <div data-testid="correlation-explain-panel" className="mb-3">
          <AIInsightPanel
            stream={explainStream}
            title="Explain — Correlation Breaks"
            onClose={closeExplain}
            onStreamComplete={handleStreamComplete}
          />
        </div>
      )}

      <svg width={svgWidth} height={svgHeight} style={{ display: 'block' }}>
        {/* Column header labels */}
        {classes.map((col, colIdx) => (
          <text
            key={`header-${col}`}
            x={labelColWidth + colIdx * cellWidth + cellWidth / 2}
            y={labelRowHeight - 8}
            textAnchor="middle"
            fontSize={11}
            fill="#475569"
            fontFamily="system-ui, sans-serif"
          >
            {SHORT_LABELS[col] ?? col}
          </text>
        ))}

        {/* Rows */}
        {classes.map((row, rowIdx) => (
          <g key={`row-${row}`}>
            {/* Row label */}
            <text
              x={labelColWidth - 8}
              y={labelRowHeight + rowIdx * cellHeight + cellHeight / 2 + 4}
              textAnchor="end"
              fontSize={11}
              fill="#475569"
              fontFamily="system-ui, sans-serif"
            >
              {SHORT_LABELS[row] ?? row}
            </text>

            {/* Cells */}
            {classes.map((col, colIdx) => {
              const value = CORRELATION_DATA[row][col]
              const isDiagonal = row === col

              return (
                <g
                  key={`cell-${row}-${col}`}
                  data-testid={`correlation-cell-${row}-${col}`}
                >
                  <rect
                    x={labelColWidth + colIdx * cellWidth}
                    y={labelRowHeight + rowIdx * cellHeight}
                    width={cellWidth}
                    height={cellHeight}
                    fill={getCellColor(value, isDiagonal)}
                    stroke="#e2e8f0"
                    strokeWidth={0.5}
                  />
                  <text
                    x={labelColWidth + colIdx * cellWidth + cellWidth / 2}
                    y={labelRowHeight + rowIdx * cellHeight + cellHeight / 2 + 4}
                    textAnchor="middle"
                    fontSize={10}
                    fill={getTextColor(value, isDiagonal)}
                    fontFamily="system-ui, sans-serif"
                    fontWeight={isDiagonal ? 500 : 400}
                  >
                    {value.toFixed(2)}
                  </text>
                </g>
              )
            })}
          </g>
        ))}
      </svg>
    </div>
  )
}

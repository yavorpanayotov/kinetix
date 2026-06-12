import { useState } from 'react'
import { formatTimestamp } from '../utils/format'

interface ScenarioTooltipProps {
  scenarioName: string
  description?: string
  shocks?: string
  lastRunAt?: string
  status?: string
  approvedBy?: string | null
}

interface ParsedShocks {
  volShocks: Record<string, number>
  priceShocks: Record<string, number>
}

function parseShocks(shocks: string | undefined): ParsedShocks | null {
  if (!shocks) return null
  try {
    const raw = JSON.parse(shocks)
    if (raw && typeof raw === 'object' && ('volShocks' in raw || 'priceShocks' in raw)) {
      return {
        volShocks: raw.volShocks ?? {},
        priceShocks: raw.priceShocks ?? {},
      }
    }
    // Flat map format: { equity: -0.1, fx: -0.04, ... } — treat as price shocks
    if (raw && typeof raw === 'object') {
      return { volShocks: {}, priceShocks: raw }
    }
    return null
  } catch {
    return null
  }
}

function formatDateTime(iso: string): string {
  try {
    return formatTimestamp(iso)
  } catch {
    return iso
  }
}

export function ScenarioTooltip({
  scenarioName,
  description,
  shocks,
  lastRunAt,
  status,
  approvedBy,
}: ScenarioTooltipProps) {
  const [visible, setVisible] = useState(false)

  const displayName = scenarioName.replace(/_/g, ' ')
  const parsed = parseShocks(shocks)
  const assetClasses = parsed
    ? [...new Set([...Object.keys(parsed.volShocks), ...Object.keys(parsed.priceShocks)])]
    : []

  return (
    <span
      className="relative inline-block"
      onMouseEnter={() => setVisible(true)}
      onMouseLeave={() => setVisible(false)}
      onFocus={() => setVisible(true)}
      onBlur={() => setVisible(false)}
      tabIndex={0}
      role="button"
      aria-describedby={visible ? 'scenario-tooltip' : undefined}
    >
      <span className="font-medium cursor-help border-b border-dotted border-slate-400">
        {displayName}
      </span>

      {visible && (
        <div
          id="scenario-tooltip"
          data-testid="scenario-tooltip"
          role="tooltip"
          className="absolute left-0 top-full mt-1 z-50 w-72 p-3 rounded-lg border bg-white dark:bg-surface-800 border-slate-200 dark:border-slate-700 shadow-lg text-sm"
        >
          {description && (
            <p className="text-slate-700 dark:text-slate-300 mb-2">{description}</p>
          )}

          {parsed && assetClasses.length > 0 && (
            <div className="mb-2">
              <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 mb-1 uppercase tracking-wide">
                Shocks
              </p>
              <table className="w-full text-xs">
                <thead>
                  <tr className="text-slate-400">
                    <th className="text-left py-0.5">Asset</th>
                    <th className="text-right py-0.5">Vol</th>
                    <th className="text-right py-0.5">Price</th>
                  </tr>
                </thead>
                <tbody>
                  {assetClasses.map((ac) => (
                    <tr key={ac}>
                      <td className="py-0.5 text-slate-700 dark:text-slate-300">{ac}</td>
                      <td className="py-0.5 text-right text-slate-600 dark:text-slate-400">
                        {parsed.volShocks[ac] != null ? `${parsed.volShocks[ac]}x` : '-'}
                      </td>
                      <td className="py-0.5 text-right text-slate-600 dark:text-slate-400">
                        {parsed.priceShocks[ac] != null ? `${parsed.priceShocks[ac]}x` : '-'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {lastRunAt && (
            <p data-testid="tooltip-last-run" className="text-xs text-slate-500 dark:text-slate-400 mb-1">
              Last run: {formatDateTime(lastRunAt)}
            </p>
          )}

          {status && (
            <div className="flex items-center gap-2 text-xs">
              <span className="font-medium text-slate-600 dark:text-slate-400">Status:</span>
              <span className="font-semibold text-slate-800 dark:text-slate-200">{status}</span>
              {approvedBy && (
                <span className="text-slate-500 dark:text-slate-400">{approvedBy}</span>
              )}
            </div>
          )}
        </div>
      )}
    </span>
  )
}

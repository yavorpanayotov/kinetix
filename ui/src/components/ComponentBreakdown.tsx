import { useMemo, useState } from 'react'
import type { ComponentBreakdownDto } from '../types'
import { formatAssetClassLabel } from '../utils/formatAssetClass'
import { formatMoney } from '../utils/format'
import { formatDiversificationBenefit } from '../utils/diversificationFormatter'
import { formatInstrumentTypeLabel, INSTRUMENT_TYPE_SVG_COLORS } from '../utils/instrumentTypes'

interface ComponentBreakdownProps {
  breakdown: ComponentBreakdownDto[]
  bookVaR?: string
  instrumentTypeBreakdown?: ComponentBreakdownDto[]
}

const ASSET_CLASS_COLORS: Record<string, string> = {
  EQUITY: '#3b82f6',
  FIXED_INCOME: '#22c55e',
  COMMODITY: '#f59e0b',
  FX: '#a855f7',
}

const DEFAULT_COLOR = '#9ca3af'

type BreakdownMode = 'asset-class' | 'instrument-type'

export function ComponentBreakdown({ breakdown, bookVaR, instrumentTypeBreakdown }: ComponentBreakdownProps) {
  const [mode, setMode] = useState<BreakdownMode>('asset-class')

  const hasInstrumentTypeData = instrumentTypeBreakdown != null && instrumentTypeBreakdown.length > 0

  const activeBreakdown = mode === 'instrument-type' && hasInstrumentTypeData
    ? instrumentTypeBreakdown
    : breakdown

  const sorted = [...activeBreakdown].sort(
    (a, b) => Number(b.percentageOfTotal) - Number(a.percentageOfTotal),
  )

  const radius = 40
  const center = 60
  const strokeWidth = 20
  const circumference = 2 * Math.PI * radius

  const segments = sorted.reduce<{ comp: ComponentBreakdownDto; offset: number }[]>(
    (acc, comp) => {
      const prevOffset = acc.length > 0 ? acc[acc.length - 1].offset + Number(acc[acc.length - 1].comp.percentageOfTotal) / 100 : 0
      return [...acc, { comp, offset: prevOffset }]
    },
    [],
  )

  const diversification = useMemo(() => {
    if (!bookVaR || breakdown.length === 0) return null
    const sumComponentVaR = breakdown.reduce((sum, c) => sum + Number(c.varContribution), 0)
    const portfolioValue = Number(bookVaR)
    const benefit = sumComponentVaR - portfolioValue
    const pct = sumComponentVaR !== 0 ? (benefit / sumComponentVaR) * 100 : 0
    return { benefit, pct }
  }, [breakdown, bookVaR])

  const getColor = (key: string): string => {
    if (mode === 'instrument-type') {
      return INSTRUMENT_TYPE_SVG_COLORS[key] ?? DEFAULT_COLOR
    }
    return ASSET_CLASS_COLORS[key] ?? DEFAULT_COLOR
  }

  const getLabel = (key: string): string => {
    if (mode === 'instrument-type') {
      return formatInstrumentTypeLabel(key)
    }
    return formatAssetClassLabel(key)
  }

  return (
    <div>
      <div className="flex items-center justify-between mb-3">
        <h3 className="text-sm font-semibold text-slate-700">Component Breakdown</h3>
        {instrumentTypeBreakdown != null && (
          <div className="flex rounded-md border border-slate-200 overflow-hidden text-xs">
            <button
              data-testid="breakdown-toggle-asset-class"
              onClick={() => setMode('asset-class')}
              className={`px-2 py-1 transition-colors ${mode === 'asset-class' ? 'bg-slate-100 font-semibold text-slate-800' : 'text-slate-500 hover:bg-slate-50'}`}
            >
              Asset Class
            </button>
            <button
              data-testid="breakdown-toggle-instrument-type"
              onClick={() => setMode('instrument-type')}
              className={`px-2 py-1 transition-colors ${mode === 'instrument-type' ? 'bg-slate-100 font-semibold text-slate-800' : 'text-slate-500 hover:bg-slate-50'}`}
            >
              Instrument Type
            </button>
          </div>
        )}
      </div>

      <div className="flex items-center gap-8">
        <div className="space-y-2.5">
          {sorted.map((comp) => (
            <div
              key={comp.assetClass}
              data-testid={`breakdown-${comp.assetClass}`}
              className="flex items-center gap-2"
            >
              <span
                className="inline-block w-2.5 h-2.5 rounded-full flex-shrink-0"
                style={{ backgroundColor: getColor(comp.assetClass) }}
              />
              <div className="flex flex-col">
                <span className="text-xs text-slate-500 leading-tight">
                  {getLabel(comp.assetClass)}
                </span>
                <div className="flex items-baseline gap-2">
                  <span className="text-sm font-semibold text-slate-800 tabular-nums leading-tight">
                    {formatMoney(comp.varContribution, 'USD')}
                  </span>
                  <span className="text-xs text-slate-400 tabular-nums leading-tight">
                    {comp.percentageOfTotal}%
                  </span>
                </div>
              </div>
            </div>
          ))}
        </div>

        <div className="flex-shrink-0">
          <svg viewBox={`0 0 ${center * 2} ${center * 2}`} width="130" height="130">
            {segments.map(({ comp, offset }) => {
              const pct = Number(comp.percentageOfTotal) / 100
              const dashLength = pct * circumference

              return (
                <circle
                  key={comp.assetClass}
                  data-testid={`breakdown-segment-${comp.assetClass}`}
                  cx={center}
                  cy={center}
                  r={radius}
                  fill="none"
                  stroke={getColor(comp.assetClass)}
                  strokeWidth={strokeWidth}
                  strokeDasharray={`${dashLength} ${circumference - dashLength}`}
                  strokeDashoffset={-offset * circumference}
                  transform={`rotate(-90 ${center} ${center})`}
                />
              )
            })}
          </svg>
        </div>
      </div>

      {diversification && (
        <div data-testid="diversification-benefit" className="mt-3 text-xs">
          <span className="text-slate-500">Diversification </span>
          <span data-testid="diversification-amount" className="font-medium text-green-600 tabular-nums">
            {formatDiversificationBenefit(diversification.benefit)}
          </span>
          <span className="text-slate-400 ml-1 tabular-nums">
            ({diversification.pct.toFixed(2)}%)
          </span>
        </div>
      )}
    </div>
  )
}

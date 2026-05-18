import { useState } from 'react'
import { useVolSurface } from '../hooks/useVolSurface'
import { VolSkewChart } from './VolSkewChart'
import { VolTermStructureChart } from './VolTermStructureChart'

interface VolSurfacePanelProps {
  instruments: string[]
  defaultInstrumentId?: string
}

export function VolSurfacePanel({ instruments, defaultInstrumentId }: VolSurfacePanelProps) {
  const [instrumentId, setInstrumentId] = useState<string | null>(defaultInstrumentId ?? null)
  const [compareDate, setCompareDate] = useState<string | undefined>(undefined)

  const { surface, diff, maturities, loading, error } = useVolSurface(instrumentId ?? null, compareDate)

  const hasInstrument = instrumentId !== null && instrumentId !== ''

  return (
    <div data-testid="vol-surface-panel" className="space-y-4">
      {/* Controls row */}
      <div className="flex flex-wrap items-center gap-3">
        <select
          data-testid="instrument-selector"
          value={instrumentId ?? ''}
          onChange={(e) => setInstrumentId(e.target.value || null)}
          className="bg-slate-700 border border-slate-600 text-slate-200 text-sm rounded px-2 py-1 focus:outline-none focus:ring-1 focus:ring-blue-500"
          aria-label="Select instrument"
        >
          <option value="">Select instrument</option>
          {instruments.map((id) => (
            <option key={id} value={id}>{id}</option>
          ))}
        </select>

        <div className="flex items-center gap-2">
          <label className="text-xs text-slate-400">Compare to:</label>
          <input
            type="date"
            data-testid="compare-date-picker"
            value={compareDate ? compareDate.slice(0, 10) : ''}
            onChange={(e) => {
              const v = e.target.value
              if (v) {
                setCompareDate(`${v}T00:00:00Z`)
              } else {
                setCompareDate(undefined)
              }
            }}
            className="bg-slate-700 border border-slate-600 text-slate-200 text-sm rounded px-2 py-1 focus:outline-none focus:ring-1 focus:ring-blue-500"
            aria-label="Compare date"
          />
        </div>
      </div>

      {/* Content */}
      {!hasInstrument ? (
        <div className="flex items-center justify-center py-12 text-sm text-slate-400">
          Select an instrument to view the vol surface.
        </div>
      ) : error ? (
        <div className="text-sm text-red-400 py-4">{error}</div>
      ) : !loading && !surface ? (
        <div className="flex items-center justify-center py-12 text-sm text-slate-400">
          No vol surface available for this instrument.
        </div>
      ) : (
        <div className="grid grid-cols-2 gap-4">
          <VolSkewChart
            points={surface?.points ?? []}
            maturities={maturities}
            diffPoints={diff?.diffs}
            isLoading={loading}
          />
          <VolTermStructureChart
            points={surface?.points ?? []}
            diffPoints={diff?.diffs}
            isLoading={loading}
          />
        </div>
      )}

      {surface && (
        <p className="text-xs text-slate-500 font-mono tabular-nums">
          As of {surface.asOfDate} · Source: {surface.source}
        </p>
      )}
    </div>
  )
}

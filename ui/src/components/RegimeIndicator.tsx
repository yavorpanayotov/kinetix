import { useState, useRef, useCallback, useEffect } from 'react'
import { Activity, AlertTriangle, AlertCircle, TrendingUp, CheckCircle } from 'lucide-react'
import { useClickOutside } from '../hooks/useClickOutside'
import type { MarketRegime, MarketRegimeDto } from '../types'

interface RegimeIndicatorProps {
  regime: MarketRegimeDto | null
  loading: boolean
}

function regimeColor(regime: MarketRegime): string {
  switch (regime) {
    case 'CRISIS':
      return 'text-red-500'
    case 'ELEVATED_VOL':
      return 'text-amber-500'
    case 'RECOVERY':
      return 'text-blue-400'
    case 'NORMAL':
    default:
      return 'text-green-500'
  }
}

function regimeBgColor(regime: MarketRegime): string {
  switch (regime) {
    case 'CRISIS':
      return 'bg-red-500/10 border-red-500/30'
    case 'ELEVATED_VOL':
      return 'bg-amber-500/10 border-amber-500/30'
    case 'RECOVERY':
      return 'bg-blue-400/10 border-blue-400/30'
    case 'NORMAL':
    default:
      return 'bg-green-500/10 border-green-500/30'
  }
}

function isPulsing(regime: MarketRegime): boolean {
  return regime === 'CRISIS' || regime === 'ELEVATED_VOL'
}

function RegimeIcon({ regime }: { regime: MarketRegime }) {
  switch (regime) {
    case 'CRISIS':
      return <AlertCircle className="h-3.5 w-3.5" />
    case 'ELEVATED_VOL':
      return <AlertTriangle className="h-3.5 w-3.5" />
    case 'RECOVERY':
      return <TrendingUp className="h-3.5 w-3.5" />
    case 'NORMAL':
    default:
      return <CheckCircle className="h-3.5 w-3.5" />
  }
}

export function RegimeIndicator({ regime, loading }: RegimeIndicatorProps) {
  const [open, setOpen] = useState(false)
  const containerRef = useRef<HTMLDivElement>(null)
  const closePanel = useCallback(() => setOpen(false), [])
  useClickOutside(containerRef, closePanel)

  useEffect(() => {
    if (!open) return
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [open])

  if (loading) {
    return (
      <div data-testid="regime-loading" className="text-slate-400 text-sm">
        <Activity className="h-4 w-4 animate-pulse" />
      </div>
    )
  }

  if (!regime) return null

  const colorClass = regimeColor(regime.regime)
  const bgClass = regimeBgColor(regime.regime)
  const pulse = isPulsing(regime.regime)

  return (
    <div ref={containerRef} className="relative">
      <button
        data-testid="regime-indicator"
        onClick={() => setOpen((prev) => !prev)}
        className={`
          flex items-center gap-1.5 px-2 py-1 rounded border text-xs font-medium
          transition-colors cursor-pointer select-none
          ${colorClass} ${bgClass}
        `}
        aria-label={`Market regime: ${regime.regime}`}
      >
        <span className={`relative flex h-2 w-2 ${pulse ? 'inline-flex' : ''}`}>
          {pulse && (
            <span className={`animate-ping absolute inline-flex h-full w-full rounded-full opacity-75 ${regime.regime === 'CRISIS' ? 'bg-red-500' : 'bg-amber-500'}`} />
          )}
          <span className={`relative inline-flex rounded-full h-2 w-2 ${regime.regime === 'CRISIS' ? 'bg-red-500' : regime.regime === 'ELEVATED_VOL' ? 'bg-amber-500' : regime.regime === 'RECOVERY' ? 'bg-blue-400' : 'bg-green-500'}`} />
        </span>
        <RegimeIcon regime={regime.regime} />
        <span>{regime.regime}</span>
      </button>

      {open && (
        <div
          data-testid="regime-detail-panel"
          className="absolute right-0 top-full mt-2 w-72 bg-surface-800 border border-surface-700 rounded-lg shadow-xl z-50 p-3 space-y-3"
        >
          <div className="text-sm font-medium text-white">Market Regime</div>

          <div className="space-y-1.5 text-xs">
            <div className="flex justify-between">
              <span className="text-slate-400">Regime</span>
              <span className={`font-medium ${colorClass}`}>{regime.regime}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-slate-400">Confidence</span>
              <span className="text-white">{Math.round(regime.confidence * 100)}%</span>
            </div>
            <div className="flex justify-between">
              <span className="text-slate-400">Confirmed</span>
              <span className="text-white">{regime.isConfirmed ? 'Yes' : 'No'}</span>
            </div>
          </div>

          <div className="border-t border-surface-700 pt-2 space-y-1.5 text-xs">
            <div className="text-slate-400 font-medium mb-1">Effective VaR Method</div>
            <div className="flex justify-between">
              <span className="text-slate-400">Calculation</span>
              <span className="text-white font-mono">{regime.varParameters.calculationType}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-slate-400">Confidence Level</span>
              <span className="text-white font-mono">{regime.varParameters.confidenceLevel}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-slate-400">Horizon</span>
              <span className="text-white">{regime.varParameters.timeHorizonDays}d</span>
            </div>
          </div>

          <div className="border-t border-surface-700 pt-2 space-y-1.5 text-xs">
            <div className="text-slate-400 font-medium mb-1">Signals</div>
            <div className="flex justify-between">
              <span className="text-slate-400">Realised Vol (20d)</span>
              <span className="text-white">{(regime.signals.realisedVol20d * 100).toFixed(1)}%</span>
            </div>
            <div className="flex justify-between">
              <span className="text-slate-400">Cross-Asset Corr</span>
              <span className="text-white">{regime.signals.crossAssetCorrelation.toFixed(2)}</span>
            </div>
          </div>

          {regime.degradedInputs && (
            <div className="border-t border-surface-700 pt-2">
              <div className="flex items-center gap-1.5 text-xs text-amber-400">
                <AlertTriangle className="h-3 w-3 flex-shrink-0" />
                <span>Classification uses degraded (two-factor) signals</span>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

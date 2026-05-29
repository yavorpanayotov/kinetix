import { Activity, Pause, Radio } from 'lucide-react'
import type { TapeReplayStatus } from '../api/tapeReplay'

interface TapeReplayIndicatorProps {
  status: TapeReplayStatus | null
  loading: boolean
}

interface Variant {
  label: string
  longLabel: string
  /**
   * Accessible name for the pill. Distinct from the visible long label so the
   * terse "Frozen" chip can answer "frozen what?" for screen-reader users and
   * the hover title (plan items P3 #31).
   */
  ariaLabel: string
  description: string
  icon: typeof Activity
  className: string
}

const VARIANTS: Record<TapeReplayStatus, Variant> = {
  LIVE: {
    label: 'Live',
    longLabel: 'Live',
    ariaLabel: 'Live market data',
    description: 'Production data — prices update from real venue feeds',
    icon: Radio,
    className: 'border-emerald-500/30 bg-emerald-500/10 text-emerald-200',
  },
  ACTIVE: {
    label: 'Replay',
    longLabel: 'Tape Replay Active',
    ariaLabel: 'Tape replay active',
    description: 'Demo tape replay is running — prices and trades tick on a loop',
    icon: Activity,
    className: 'border-amber-500/30 bg-amber-500/10 text-amber-200',
  },
  FROZEN: {
    label: 'Frozen',
    longLabel: 'Frozen',
    ariaLabel: 'Frozen market data — screen is static between resets',
    description: 'Demo mode without tape replay — screen is static between resets',
    icon: Pause,
    className: 'border-slate-500/30 bg-slate-700/40 text-slate-300',
  },
}

export function TapeReplayIndicator({ status, loading }: TapeReplayIndicatorProps) {
  if (loading && status === null) {
    return (
      <div
        data-testid="tape-replay-indicator-loading"
        className="inline-flex items-center gap-1.5 px-2 py-1 text-[11px] tracking-wider rounded border border-slate-700/50 bg-slate-800/40 text-slate-500"
        aria-label="Tape replay status loading"
      >
        <Activity className="h-3 w-3" />
        Replay...
      </div>
    )
  }

  if (status === null) {
    return null
  }

  const variant = VARIANTS[status]
  const Icon = variant.icon

  return (
    <div
      data-testid="tape-replay-indicator"
      data-status={status}
      className={`inline-flex items-center gap-1.5 px-2 py-1 text-[11px] tracking-wider rounded border ${variant.className}`}
      aria-label={variant.ariaLabel}
      title={variant.description}
    >
      <Icon className="h-3 w-3" />
      <span className="hidden 2xl:inline">{variant.longLabel}</span>
      <span className="2xl:hidden">{variant.label}</span>
    </div>
  )
}

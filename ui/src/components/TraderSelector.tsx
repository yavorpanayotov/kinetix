import type { ChangeEvent } from 'react'
import { UserRound } from 'lucide-react'
import type { TraderDto } from '../api/traders'

interface TraderSelectorProps {
  traders: TraderDto[]
  selectedTraderId: string | null
  onChange: (traderId: string | null) => void
  loading: boolean
  /** When provided, only traders on this desk appear in the dropdown. */
  filterByDeskId?: string | null
}

/**
 * Phase 3 Gap 13 — per-trader drill-down entry point. Renders a dropdown of
 * known traders that filters position, P&L, and risk views. "All traders"
 * (null selection) is the default state and matches the previous global view.
 *
 * The selector is intentionally compact so it can sit in the positions /
 * P&L / risk tab toolbars without crowding the existing book selector.
 */
export function TraderSelector({
  traders,
  selectedTraderId,
  onChange,
  loading,
  filterByDeskId,
}: TraderSelectorProps) {
  const visibleTraders = filterByDeskId
    ? traders.filter((t) => t.deskId === filterByDeskId)
    : traders

  const handleChange = (event: ChangeEvent<HTMLSelectElement>) => {
    const value = event.target.value
    onChange(value === '' ? null : value)
  }

  return (
    <label
      data-testid="trader-selector"
      className="inline-flex items-center gap-1.5 text-[12px] tracking-wide text-slate-200"
    >
      <UserRound className="h-3.5 w-3.5 text-slate-400" />
      <span className="text-slate-400">Trader</span>
      <select
        data-testid="trader-selector-input"
        value={selectedTraderId ?? ''}
        onChange={handleChange}
        disabled={loading}
        className="bg-surface-800 border border-slate-700/60 rounded px-2 py-1 text-slate-100 focus:border-primary-500 focus:outline-none disabled:opacity-50 max-w-[200px]"
        aria-label="Filter view by trader"
      >
        <option value="">All traders</option>
        {visibleTraders.map((trader) => (
          <option key={trader.trader_id} value={trader.trader_id}>
            {trader.name}
          </option>
        ))}
      </select>
    </label>
  )
}

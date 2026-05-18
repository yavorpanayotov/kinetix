import { Fragment, useState } from 'react'
import { ChevronDown, ChevronRight } from 'lucide-react'
import type { StrategyGroupDto } from '../types'
import { formatNum, formatSignedNum, pnlColorClass } from '../utils/format'

interface StrategyGroupRowProps {
  strategy: StrategyGroupDto
  colSpan: number
}

const STRATEGY_TYPE_COLORS: Record<string, string> = {
  STRADDLE: 'bg-violet-100 text-violet-700',
  STRANGLE: 'bg-purple-100 text-purple-700',
  SPREAD: 'bg-blue-100 text-blue-700',
  COLLAR: 'bg-teal-100 text-teal-700',
  CALENDAR_SPREAD: 'bg-cyan-100 text-cyan-700',
  BUTTERFLY: 'bg-amber-100 text-amber-700',
  CONDOR: 'bg-orange-100 text-orange-700',
  CUSTOM: 'bg-slate-100 text-slate-700',
}

function formatStrategyType(type: string): string {
  return type.replace(/_/g, ' ')
}

export function StrategyGroupRow({ strategy, colSpan }: StrategyGroupRowProps) {
  const [expanded, setExpanded] = useState(false)

  const badgeClass = STRATEGY_TYPE_COLORS[strategy.strategyType] ?? 'bg-slate-100 text-slate-700'

  return (
    <Fragment>
      {/* Strategy parent row */}
      <tr
        data-testid={`strategy-row-${strategy.strategyId}`}
        onClick={() => setExpanded((v) => !v)}
        className="cursor-pointer bg-indigo-50/40 hover:bg-indigo-50 border-b border-slate-200 transition-colors"
      >
        <td className="px-4 py-2">
          {expanded ? (
            <ChevronDown className="h-4 w-4 text-slate-500" />
          ) : (
            <ChevronRight className="h-4 w-4 text-slate-500" />
          )}
        </td>
        <td className="px-4 py-2" colSpan={colSpan - 5}>
          <div className="flex items-center gap-2">
            <span className="font-semibold text-slate-800 text-sm">
              {strategy.strategyName ?? formatStrategyType(strategy.strategyType)}
            </span>
            <span
              data-testid={`strategy-type-badge-${strategy.strategyId}`}
              className={`inline-block px-2 py-0.5 rounded text-xs font-medium ${badgeClass}`}
            >
              {formatStrategyType(strategy.strategyType)}
            </span>
            <span className="text-xs text-slate-400">{strategy.legs.length} legs</span>
          </div>
        </td>
        <td
          data-testid={`strategy-net-delta-${strategy.strategyId}`}
          className="px-4 py-2 text-right text-sm font-mono tabular-nums text-indigo-700"
        >
          {strategy.netDelta != null ? formatNum(strategy.netDelta) : '\u2014'}
        </td>
        <td
          data-testid={`strategy-net-gamma-${strategy.strategyId}`}
          className="px-4 py-2 text-right text-sm font-mono tabular-nums text-indigo-700"
        >
          {strategy.netGamma != null ? formatNum(strategy.netGamma) : '\u2014'}
        </td>
        <td
          data-testid={`strategy-net-vega-${strategy.strategyId}`}
          className="px-4 py-2 text-right text-sm font-mono tabular-nums text-indigo-700"
        >
          {strategy.netVega != null ? formatNum(strategy.netVega) : '\u2014'}
        </td>
        <td
          data-testid={`strategy-net-pnl-${strategy.strategyId}`}
          className={`px-4 py-2 text-right text-sm font-mono tabular-nums font-semibold ${pnlColorClass(strategy.netPnl)}`}
        >
          {formatSignedNum(strategy.netPnl)}
        </td>
      </tr>

      {/* Leg rows — only rendered when expanded */}
      {expanded && strategy.legs.map((leg) => (
        <tr
          key={leg.instrumentId}
          data-testid={`strategy-leg-${leg.instrumentId}`}
          className="bg-slate-50/60 border-b border-slate-100"
        >
          <td className="px-4 py-1.5 pl-10" />
          <td className="px-4 py-1.5 text-sm text-slate-600 pl-10" colSpan={colSpan - 5}>
            {leg.instrumentId}
            {leg.instrumentType && (
              <span className="ml-2 text-xs text-slate-400">{leg.instrumentType}</span>
            )}
          </td>
          <td className="px-4 py-1.5 text-sm text-right text-slate-500" colSpan={4}>
            <span className={`font-mono tabular-nums ${pnlColorClass(leg.unrealizedPnl.amount)}`}>
              {leg.unrealizedPnl.amount !== '0' ? `$${Number(leg.unrealizedPnl.amount).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}` : '$0.00'}
            </span>
          </td>
        </tr>
      ))}
    </Fragment>
  )
}

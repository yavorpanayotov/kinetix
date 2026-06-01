import type { BrinsonAttributionDto, BrinsonSectorDto } from '../api/benchmarkAttribution'

interface BrinsonAttributionTableProps {
  data: BrinsonAttributionDto
}

function pct(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return '—'
  return `${(value * 100).toFixed(2)}%`
}

function effectClass(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return 'text-slate-500'
  if (value > 0) return 'text-green-600'
  if (value < 0) return 'text-red-600'
  return 'text-slate-500'
}

function SectorRow({ sector }: { sector: BrinsonSectorDto }) {
  return (
    <tr data-testid={`brinson-row-${sector.sectorLabel}`} className="border-t border-slate-100">
      <td className="py-2 px-3 text-sm font-mono text-slate-800">{sector.sectorLabel}</td>
      <td className="py-2 px-3 text-sm text-right text-slate-600">{pct(sector.portfolioWeight)}</td>
      <td className="py-2 px-3 text-sm text-right text-slate-600">{pct(sector.benchmarkWeight)}</td>
      <td className={`py-2 px-3 text-sm text-right ${effectClass(sector.portfolioReturn)}`}>
        {pct(sector.portfolioReturn)}
      </td>
      <td className={`py-2 px-3 text-sm text-right ${effectClass(sector.benchmarkReturn)}`}>
        {pct(sector.benchmarkReturn)}
      </td>
      <td
        data-testid={`brinson-allocation-${sector.sectorLabel}`}
        className={`py-2 px-3 text-sm text-right ${effectClass(sector.allocationEffect)}`}
      >
        {pct(sector.allocationEffect)}
      </td>
      <td
        data-testid={`brinson-selection-${sector.sectorLabel}`}
        className={`py-2 px-3 text-sm text-right ${effectClass(sector.selectionEffect)}`}
      >
        {pct(sector.selectionEffect)}
      </td>
      <td
        data-testid={`brinson-interaction-${sector.sectorLabel}`}
        className={`py-2 px-3 text-sm text-right ${effectClass(sector.interactionEffect)}`}
      >
        {pct(sector.interactionEffect)}
      </td>
      <td className={`py-2 px-3 text-sm text-right font-medium ${effectClass(sector.totalActiveContribution)}`}>
        {pct(sector.totalActiveContribution)}
      </td>
    </tr>
  )
}

export function BrinsonAttributionTable({ data }: BrinsonAttributionTableProps) {
  return (
    <div data-testid="brinson-attribution-table" className="overflow-x-auto">
      <p className="text-xs text-slate-500 mb-2">
        Benchmark: <span className="font-medium">{data.benchmarkId}</span>
        {' · '}
        As of: <span className="font-medium">{data.asOfDate}</span>
      </p>

      {data.sectors.length === 0 ? (
        <p data-testid="brinson-empty" className="text-sm text-slate-500 py-4 text-center">
          No sectors to display.
        </p>
      ) : (
        <table className="w-full text-left border-collapse">
          <thead>
            <tr className="text-xs text-slate-500 uppercase tracking-wide">
              <th className="py-2 px-3 font-medium">Sector</th>
              <th className="py-2 px-3 font-medium text-right">Port. Wt</th>
              <th className="py-2 px-3 font-medium text-right">Bench. Wt</th>
              <th className="py-2 px-3 font-medium text-right">Port. Ret.</th>
              <th className="py-2 px-3 font-medium text-right">Bench. Ret.</th>
              <th className="py-2 px-3 font-medium text-right">Allocation</th>
              <th className="py-2 px-3 font-medium text-right">Selection</th>
              <th className="py-2 px-3 font-medium text-right">Interaction</th>
              <th className="py-2 px-3 font-medium text-right">Total Active</th>
            </tr>
          </thead>
          <tbody>
            {data.sectors.map((sector) => (
              <SectorRow key={sector.sectorLabel} sector={sector} />
            ))}
            <tr
              data-testid="brinson-totals-row"
              className="border-t-2 border-slate-300 bg-slate-50 font-semibold"
            >
              <td className="py-2 px-3 text-sm text-slate-700">Total</td>
              <td className="py-2 px-3 text-sm text-right text-slate-500" colSpan={4} />
              <td className={`py-2 px-3 text-sm text-right ${effectClass(data.totalAllocationEffect)}`}>
                {pct(data.totalAllocationEffect)}
              </td>
              <td className={`py-2 px-3 text-sm text-right ${effectClass(data.totalSelectionEffect)}`}>
                {pct(data.totalSelectionEffect)}
              </td>
              <td className={`py-2 px-3 text-sm text-right ${effectClass(data.totalInteractionEffect)}`}>
                {pct(data.totalInteractionEffect)}
              </td>
              <td className={`py-2 px-3 text-sm text-right font-semibold ${effectClass(data.totalActiveReturn)}`}>
                {pct(data.totalActiveReturn)}
              </td>
            </tr>
          </tbody>
        </table>
      )}
    </div>
  )
}

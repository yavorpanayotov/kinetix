import type { BookVaRContributionDto } from '../types'
import { formatMoney } from '../utils/format'
import { formatDiversificationBenefit } from '../utils/diversificationFormatter'

interface BookContributionTableProps {
  contributions: BookVaRContributionDto[]
  onBookClick?: (bookId: string) => void
}

export function BookContributionTable({ contributions, onBookClick }: BookContributionTableProps) {
  if (contributions.length === 0) return null

  return (
    <div data-testid="book-contribution-table">
      <h3 className="text-sm font-medium text-slate-700 dark:text-slate-300 mb-2">Book Contributions</h3>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-slate-200 dark:border-slate-700">
              <th scope="col" className="text-left py-1.5 px-2 text-xs font-medium text-slate-500">Book</th>
              <th scope="col" className="text-right py-1.5 px-2 text-xs font-medium text-slate-500">VaR Contribution</th>
              <th scope="col" className="text-right py-1.5 px-2 text-xs font-medium text-slate-500">% of Total</th>
              <th scope="col" className="text-right py-1.5 px-2 text-xs font-medium text-slate-500">Standalone VaR</th>
              <th scope="col" className="text-right py-1.5 px-2 text-xs font-medium text-slate-500">Diversification</th>
              <th scope="col" className="text-right py-1.5 px-2 text-xs font-medium text-slate-500">Marginal VaR</th>
              <th scope="col" className="text-right py-1.5 px-2 text-xs font-medium text-slate-500">Incremental VaR</th>
            </tr>
          </thead>
          <tbody>
            {contributions.map(c => (
              <tr
                key={c.bookId}
                data-testid={`book-row-${c.bookId}`}
                className={`border-b border-slate-100 dark:border-slate-800 ${onBookClick ? 'cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-800/50' : ''}`}
                onClick={() => onBookClick?.(c.bookId)}
              >
                <td className="py-1.5 px-2 font-medium text-slate-700 dark:text-slate-300">{c.bookId}</td>
                <td className="py-1.5 px-2 text-right tabular-nums">{formatMoney(c.varContribution, 'USD')}</td>
                <td className="py-1.5 px-2 text-right tabular-nums">{Number(c.percentageOfTotal).toFixed(1)}%</td>
                <td className="py-1.5 px-2 text-right tabular-nums text-slate-500">{formatMoney(c.standaloneVar, 'USD')}</td>
                <td className="py-1.5 px-2 text-right tabular-nums text-green-600">
                  {formatDiversificationBenefit(Number(c.diversificationBenefit))}
                </td>
                <td className="py-1.5 px-2 text-right tabular-nums text-slate-500">
                  {Number(c.marginalVar).toFixed(4)}
                </td>
                <td className={`py-1.5 px-2 text-right tabular-nums ${Number(c.incrementalVar) < 0 ? 'text-red-600' : 'text-slate-700 dark:text-slate-300'}`}>
                  {formatMoney(c.incrementalVar, 'USD')}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

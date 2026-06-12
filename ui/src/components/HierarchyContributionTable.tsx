import type { HierarchyNodeRiskDto } from '../types'
import { EM_DASH, formatMoney } from '../utils/format'

interface HierarchyContributionTableProps {
  node: HierarchyNodeRiskDto
  onEntityClick?: (entityId: string) => void
}

export function HierarchyContributionTable({
  node,
  onEntityClick,
}: HierarchyContributionTableProps) {
  const { topContributors, isPartial, missingBooks } = node

  if (topContributors.length === 0 && !isPartial) return null

  const levelLabel = node.level.charAt(0) + node.level.slice(1).toLowerCase()
  const childLevel = childLevelOf(node.level)

  return (
    <div data-testid="hierarchy-contribution-table">
      <div className="flex items-center justify-between mb-2">
        <h3 className="text-sm font-medium text-slate-700 dark:text-slate-300">
          Top {childLevel} Contributors — {levelLabel}: {node.entityName}
        </h3>
        {isPartial && (
          <span
            data-testid="partial-badge"
            className="text-xs px-2 py-0.5 rounded-full bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400"
          >
            Partial ({missingBooks.length} book{missingBooks.length !== 1 ? 's' : ''} missing)
          </span>
        )}
      </div>

      {topContributors.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-200 dark:border-slate-700">
                <th
                  scope="col"
                  className="text-left py-1.5 px-2 text-xs font-medium text-slate-500"
                >
                  {childLevel}
                </th>
                <th
                  scope="col"
                  className="text-right py-1.5 px-2 text-xs font-medium text-slate-500"
                >
                  VaR Contribution
                </th>
                <th
                  scope="col"
                  className="text-right py-1.5 px-2 text-xs font-medium text-slate-500"
                >
                  % of Total
                </th>
              </tr>
            </thead>
            <tbody>
              {topContributors.map((c) => (
                <tr
                  key={c.entityId}
                  data-testid={`contributor-row-${c.entityId}`}
                  className={`border-b border-slate-100 dark:border-slate-800 ${
                    onEntityClick
                      ? 'cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-800/50'
                      : ''
                  }`}
                  onClick={() => onEntityClick?.(c.entityId)}
                >
                  <td className="py-1.5 px-2 font-medium text-slate-700 dark:text-slate-300">
                    {c.entityName}
                  </td>
                  <td className="py-1.5 px-2 text-right tabular-nums">
                    {formatMoney(c.varContribution, 'USD')}
                  </td>
                  <td className="py-1.5 px-2 text-right tabular-nums">
                    {Number(c.pctOfTotal).toFixed(1)}%
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {isPartial && missingBooks.length > 0 && (
        <p
          data-testid="missing-books-note"
          className="mt-2 text-xs text-amber-600 dark:text-amber-400"
        >
          Missing VaR for: {missingBooks.join(', ')}
        </p>
      )}

      {(node.marginalVar !== null || node.incrementalVar !== null) && (
        <div className="mt-3 flex gap-4 text-xs text-slate-500 dark:text-slate-400">
          {node.marginalVar !== null && (
            <span data-testid="marginal-var-summary">
              Marginal VaR:{' '}
              <span className="font-medium text-slate-700 dark:text-slate-300 tabular-nums">
                {summaryValue(node.marginalVar, topContributors)}
              </span>
            </span>
          )}
          {node.incrementalVar !== null && (
            <span data-testid="incremental-var-summary">
              Incremental VaR:{' '}
              <span className="font-medium text-slate-700 dark:text-slate-300 tabular-nums">
                {summaryValue(node.incrementalVar, topContributors)}
              </span>
            </span>
          )}
        </div>
      )}
    </div>
  )
}

/**
 * A hard zero marginal/incremental VaR next to non-zero contributions means
 * "not computed for this run", not "zero risk" — showing $0.00 there is a
 * contradiction (UX review). Render the not-available sentinel instead.
 */
function summaryValue(
  value: string,
  topContributors: HierarchyNodeRiskDto['topContributors'],
): string {
  const contributionsNonZero = topContributors.some((c) => Number(c.varContribution) !== 0)
  if (contributionsNonZero && Number(value) === 0) return EM_DASH
  return formatMoney(value, 'USD')
}

function childLevelOf(level: string): string {
  switch (level) {
    case 'FIRM':
      return 'Division'
    case 'DIVISION':
      return 'Desk'
    case 'DESK':
      return 'Book'
    default:
      return 'Entity'
  }
}

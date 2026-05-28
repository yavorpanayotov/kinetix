import React, { memo, useMemo, useState } from 'react'
import { ChevronDown, ChevronUp, Settings2 } from 'lucide-react'
import type { EodTimelineEntryDto } from '../types'
import { changeColorClass } from '../utils/changeIndicators'
import { formatCurrency } from '../utils/format'
import { Badge, Button } from './ui'

interface EodDailyGridProps {
  entries: EodTimelineEntryDto[]
  selectedDate: string | null
  compareDates: string[]
  onSelectDate: (date: string) => void
  onCompareDatesChange: (dates: string[]) => void
  onCompare: () => void
}

type SortField = 'valuationDate' | 'varValue' | 'expectedShortfall' | 'pvValue' | 'varChange' | 'esChange'
type SortDir = 'asc' | 'desc'

function formatDod(value: number | null, pct: number | null): { text: string; className: string } {
  if (value === null) return { text: '\u2014', className: 'text-slate-400 dark:text-slate-500' }
  const arrow = value > 0 ? '\u25b2' : value < 0 ? '\u25bc' : ''
  const sign = value > 0 ? '+' : ''
  const text = `${arrow} ${sign}${formatCurrency(value)}`
  const smallChange = Math.abs(pct ?? 0) < 0.5
  const className = smallChange ? 'text-slate-400 dark:text-slate-500' : changeColorClass(value)
  return { text, className }
}

function CellValue({ value }: { value: number | null }) {
  if (value === null) return <span className="text-slate-400 dark:text-slate-500">{'\u2014'}</span>
  return <span>{formatCurrency(value)}</span>
}

interface RowProps {
  entry: EodTimelineEntryDto
  isSelected: boolean
  isCompareChecked: boolean
  canAddCompare: boolean
  visibleHiddenCols: boolean
  onSelect: () => void
  onToggleCompare: () => void
}

const EodGridRow = memo(function EodGridRow({
  entry,
  isSelected,
  isCompareChecked,
  canAddCompare,
  visibleHiddenCols,
  onSelect,
  onToggleCompare,
}: RowProps) {
  const isMissing = entry.varValue === null
  const varDod = formatDod(entry.varChange, entry.varChangePct)
  const esDod = formatDod(entry.esChange, null)

  const rowClass = [
    'border-b text-sm transition-colors',
    isMissing
      ? 'bg-red-50 dark:bg-red-900/10 cursor-not-allowed'
      : isSelected
        ? 'bg-indigo-50 dark:bg-indigo-900/20 ring-1 ring-primary-500 cursor-pointer'
        : 'hover:bg-slate-50 dark:hover:bg-surface-700/50 cursor-pointer',
    'border-slate-100 dark:border-surface-700',
  ].join(' ')

  const handleRowClick = () => {
    if (!isMissing) onSelect()
  }

  const handleRowKeyDown = (e: React.KeyboardEvent) => {
    if (!isMissing && (e.key === 'Enter' || e.key === ' ')) {
      e.preventDefault()
      onSelect()
    }
  }

  const promotedAt = entry.promotedAt
    ? new Date(entry.promotedAt).toLocaleString('en-GB', {
        day: '2-digit',
        month: '2-digit',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
      })
    : '\u2014'

  return (
    <tr
      data-testid={`eod-row-${entry.valuationDate}`}
      className={rowClass}
      onClick={handleRowClick}
      onKeyDown={handleRowKeyDown}
      tabIndex={isMissing ? -1 : 0}
      role="row"
      aria-selected={isSelected}
    >
      {/* Checkbox */}
      <td className="py-2 pl-3 pr-2 w-8" role="gridcell">
        <input
          type="checkbox"
          aria-label={`Select ${entry.valuationDate} for comparison`}
          checked={isCompareChecked}
          disabled={isMissing || (!isCompareChecked && !canAddCompare)}
          onChange={(e) => {
            e.stopPropagation()
            onToggleCompare()
          }}
          onClick={(e) => e.stopPropagation()}
          className="rounded border-slate-300 text-indigo-600 focus:ring-indigo-500 disabled:opacity-40"
        />
      </td>

      {/* Date */}
      <td className="py-2 pr-3 font-mono text-xs font-medium text-slate-800 dark:text-slate-200" role="gridcell">
        {entry.valuationDate}
      </td>

      {/* VaR */}
      <td className="py-2 pr-3 text-right font-mono text-xs" role="gridcell">
        <CellValue value={entry.varValue} />
      </td>

      {/* VaR DoD */}
      <td className={`py-2 pr-3 text-right font-mono text-xs ${varDod.className}`} role="gridcell">
        {varDod.text}
      </td>

      {/* ES */}
      <td className="py-2 pr-3 text-right font-mono text-xs" role="gridcell">
        <CellValue value={entry.expectedShortfall} />
      </td>

      {/* ES DoD */}
      <td className={`py-2 pr-3 text-right font-mono text-xs ${esDod.className}`} role="gridcell">
        {esDod.text}
      </td>

      {/* PV */}
      <td
        data-testid={`eod-pv-cell-${entry.valuationDate}`}
        className="py-2 pr-3 text-right font-mono text-xs"
        role="gridcell"
      >
        <CellValue value={entry.pvValue} />
      </td>

      {/* Promoted By */}
      <td className="py-2 pr-3 text-xs text-slate-600 dark:text-slate-400" role="gridcell">
        {entry.promotedBy ?? '\u2014'}
      </td>

      {/* Status */}
      <td className="py-2 pr-3" role="gridcell">
        {isMissing ? (
          <Badge variant="critical" data-testid={`missing-badge-${entry.valuationDate}`}>
            <span aria-label="Missing EOD snapshot">MISSING</span>
          </Badge>
        ) : entry.promotedBy ? (
          <Badge variant="eod">Promoted</Badge>
        ) : (
          <Badge variant="neutral">Not promoted</Badge>
        )}
      </td>

      {/* Hidden cols */}
      {visibleHiddenCols && (
        <>
          <td className="py-2 pr-3 text-right font-mono text-xs" role="gridcell">
            {entry.delta !== null ? entry.delta.toFixed(4) : '\u2014'}
          </td>
          <td className="py-2 pr-3 text-right font-mono text-xs" role="gridcell">
            {entry.gamma !== null ? entry.gamma.toFixed(4) : '\u2014'}
          </td>
          <td className="py-2 pr-3 text-right font-mono text-xs" role="gridcell">
            {entry.vega !== null ? entry.vega.toFixed(2) : '\u2014'}
          </td>
          <td className="py-2 pr-3 text-right font-mono text-xs" role="gridcell">
            {entry.theta !== null ? entry.theta.toFixed(2) : '\u2014'}
          </td>
          <td className="py-2 pr-3 text-right font-mono text-xs" role="gridcell">
            {entry.rho !== null ? entry.rho.toFixed(2) : '\u2014'}
          </td>
          <td className="py-2 pr-3 text-xs text-slate-600 dark:text-slate-400" role="gridcell">
            {promotedAt}
          </td>
        </>
      )}
    </tr>
  )
})

function SortHeader({
  label,
  field,
  sortField,
  sortDir,
  onSort,
}: {
  label: string
  field: SortField
  sortField: SortField
  sortDir: SortDir
  onSort: (f: SortField) => void
}) {
  const isActive = sortField === field
  return (
    <th
      data-testid={`eod-sort-${field}`}
      className="py-2 pr-3 text-right text-xs font-medium text-slate-500 dark:text-slate-400 cursor-pointer select-none whitespace-nowrap"
      onClick={() => onSort(field)}
      aria-sort={isActive ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'}
    >
      {label}
      {isActive && (
        sortDir === 'desc'
          ? <ChevronDown className="inline h-3 w-3 ml-0.5" />
          : <ChevronUp className="inline h-3 w-3 ml-0.5" />
      )}
    </th>
  )
}

export function EodDailyGrid({
  entries,
  selectedDate,
  compareDates,
  onSelectDate,
  onCompareDatesChange,
  onCompare,
}: EodDailyGridProps) {
  const [sortField, setSortField] = useState<SortField>('valuationDate')
  const [sortDir, setSortDir] = useState<SortDir>('desc')
  const [showHiddenCols, setShowHiddenCols] = useState(false)
  const [colMenuOpen, setColMenuOpen] = useState(false)

  const handleSort = (field: SortField) => {
    if (sortField === field) {
      setSortDir((prev) => (prev === 'desc' ? 'asc' : 'desc'))
    } else {
      setSortField(field)
      setSortDir('desc')
    }
  }

  const sorted = useMemo(() => {
    return [...entries].sort((a, b) => {
      let aVal: number | string | null
      let bVal: number | string | null
      switch (sortField) {
        case 'valuationDate':
          aVal = a.valuationDate
          bVal = b.valuationDate
          break
        case 'varValue':
          aVal = a.varValue
          bVal = b.varValue
          break
        case 'expectedShortfall':
          aVal = a.expectedShortfall
          bVal = b.expectedShortfall
          break
        case 'pvValue':
          aVal = a.pvValue
          bVal = b.pvValue
          break
        case 'varChange':
          aVal = a.varChange
          bVal = b.varChange
          break
        case 'esChange':
          aVal = a.esChange
          bVal = b.esChange
          break
        default:
          aVal = a.valuationDate
          bVal = b.valuationDate
      }

      // Nulls sort last
      if (aVal === null && bVal === null) return 0
      if (aVal === null) return 1
      if (bVal === null) return -1

      const cmp = aVal < bVal ? -1 : aVal > bVal ? 1 : 0
      return sortDir === 'asc' ? cmp : -cmp
    })
  }, [entries, sortField, sortDir])

  const handleToggleCompare = (date: string) => {
    if (compareDates.includes(date)) {
      onCompareDatesChange(compareDates.filter((d) => d !== date))
    } else if (compareDates.length < 2) {
      onCompareDatesChange([...compareDates, date])
    } else {
      // Replace oldest with new
      onCompareDatesChange([compareDates[1], date])
    }
  }

  const canAddCompare = compareDates.length < 2

  return (
    <div data-testid="eod-daily-grid" className="bg-white dark:bg-surface-800 rounded-lg border border-slate-200 dark:border-surface-700 shadow-sm overflow-hidden">
      {/* Comparison bar */}
      {compareDates.length > 0 && (
        <div
          data-testid="eod-comparison-bar"
          className="sticky top-0 z-10 flex items-center gap-3 px-4 py-2 bg-indigo-50 dark:bg-indigo-900/30 border-b border-indigo-200 dark:border-indigo-700"
        >
          <span className="text-xs font-medium text-indigo-700 dark:text-indigo-300">
            {compareDates.length === 1
              ? `${compareDates[0]} selected`
              : `${compareDates[0]} vs ${compareDates[1]}`}
          </span>
          {compareDates.length === 2 && (
            <Button
              data-testid="eod-compare-btn"
              size="sm"
              variant="primary"
              onClick={onCompare}
            >
              Compare
            </Button>
          )}
          <Button
            data-testid="eod-clear-compare"
            size="sm"
            variant="secondary"
            onClick={() => onCompareDatesChange([])}
          >
            Clear
          </Button>
        </div>
      )}

      {/* Column visibility toggle */}
      <div className="flex justify-end px-3 py-2 border-b border-slate-100 dark:border-surface-700">
        <div className="relative">
          <button
            data-testid="eod-col-toggle"
            onClick={() => setColMenuOpen((prev) => !prev)}
            className="inline-flex items-center gap-1 text-xs text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200"
            aria-label="Toggle column visibility"
          >
            <Settings2 className="h-3.5 w-3.5" />
            Columns
          </button>
          {colMenuOpen && (
            <div className="absolute right-0 top-full mt-1 bg-white dark:bg-surface-800 border border-slate-200 dark:border-surface-600 rounded shadow-lg p-3 z-20 w-40">
              <label className="flex items-center gap-2 text-xs text-slate-700 dark:text-slate-300 cursor-pointer">
                <input
                  type="checkbox"
                  checked={showHiddenCols}
                  onChange={(e) => {
                    setShowHiddenCols(e.target.checked)
                    setColMenuOpen(false)
                  }}
                  className="rounded border-slate-300 text-indigo-600"
                />
                Greeks &amp; Promoted At
              </label>
            </div>
          )}
        </div>
      </div>

      <div className="overflow-x-auto">
        <table
          className="min-w-full text-sm"
          role="grid"
          aria-rowcount={sorted.length}
          aria-label="EOD daily risk history"
        >
          <thead>
            <tr className="text-left border-b border-slate-200 dark:border-surface-700">
              <th className="py-2 pl-3 pr-2 w-8" role="columnheader" />
              <th
                data-testid="eod-sort-valuationDate"
                className="py-2 pr-3 text-xs font-medium text-slate-500 dark:text-slate-400 cursor-pointer select-none"
                onClick={() => handleSort('valuationDate')}
                aria-sort={sortField === 'valuationDate' ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'}
              >
                Date
                {sortField === 'valuationDate' && (
                  sortDir === 'desc'
                    ? <ChevronDown className="inline h-3 w-3 ml-0.5" />
                    : <ChevronUp className="inline h-3 w-3 ml-0.5" />
                )}
              </th>
              <SortHeader label="VaR" field="varValue" sortField={sortField} sortDir={sortDir} onSort={handleSort} />
              <SortHeader label="VaR DoD" field="varChange" sortField={sortField} sortDir={sortDir} onSort={handleSort} />
              <SortHeader label="ES" field="expectedShortfall" sortField={sortField} sortDir={sortDir} onSort={handleSort} />
              <SortHeader label="ES DoD" field="esChange" sortField={sortField} sortDir={sortDir} onSort={handleSort} />
              <SortHeader label="PV" field="pvValue" sortField={sortField} sortDir={sortDir} onSort={handleSort} />
              <th className="py-2 pr-3 text-xs font-medium text-slate-500 dark:text-slate-400" role="columnheader">Promoted By</th>
              <th className="py-2 pr-3 text-xs font-medium text-slate-500 dark:text-slate-400" role="columnheader">Status</th>
              {showHiddenCols && (
                <>
                  <th className="py-2 pr-3 text-right text-xs font-medium text-slate-500 dark:text-slate-400" role="columnheader">Delta</th>
                  <th className="py-2 pr-3 text-right text-xs font-medium text-slate-500 dark:text-slate-400" role="columnheader">Gamma</th>
                  <th className="py-2 pr-3 text-right text-xs font-medium text-slate-500 dark:text-slate-400" role="columnheader">Vega</th>
                  <th className="py-2 pr-3 text-right text-xs font-medium text-slate-500 dark:text-slate-400" role="columnheader">Theta</th>
                  <th className="py-2 pr-3 text-right text-xs font-medium text-slate-500 dark:text-slate-400" role="columnheader">Rho</th>
                  <th className="py-2 pr-3 text-xs font-medium text-slate-500 dark:text-slate-400" role="columnheader">Promoted At</th>
                </>
              )}
            </tr>
          </thead>
          <tbody>
            {sorted.map((entry) => (
              <EodGridRow
                key={entry.valuationDate}
                entry={entry}
                isSelected={selectedDate === entry.valuationDate}
                isCompareChecked={compareDates.includes(entry.valuationDate)}
                canAddCompare={canAddCompare}
                visibleHiddenCols={showHiddenCols}
                onSelect={() => onSelectDate(entry.valuationDate)}
                onToggleCompare={() => handleToggleCompare(entry.valuationDate)}
              />
            ))}
          </tbody>
        </table>

        {sorted.length === 0 && (
          <div
            data-testid="eod-grid-empty"
            className="py-12 text-center text-sm text-slate-400 dark:text-slate-500"
          >
            No EOD history for this period.
          </div>
        )}
      </div>
    </div>
  )
}

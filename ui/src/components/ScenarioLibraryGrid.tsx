import { useState, useMemo } from 'react'
import { ChevronUp, ChevronDown, ChevronsUpDown } from 'lucide-react'
import type { StressScenarioDto } from '../types'
import { Input, Select, Spinner, ErrorCard } from './ui'

type SortField = 'name' | 'status' | 'type'
type SortDirection = 'asc' | 'desc'

const TYPE_LABELS: Record<string, string> = {
  PARAMETRIC: 'Parametric',
  HISTORICAL_REPLAY: 'Historical Replay',
  REVERSE_STRESS: 'Reverse Stress',
}

const TYPE_COLORS: Record<string, string> = {
  PARAMETRIC: 'bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-300',
  HISTORICAL_REPLAY: 'bg-violet-100 text-violet-800 dark:bg-violet-900/30 dark:text-violet-300',
  REVERSE_STRESS: 'bg-orange-100 text-orange-800 dark:bg-orange-900/30 dark:text-orange-300',
}

const STATUS_COLORS: Record<string, string> = {
  DRAFT: 'bg-slate-100 text-slate-700 dark:bg-slate-700 dark:text-slate-300',
  PENDING_APPROVAL: 'bg-amber-100 text-amber-800 dark:bg-amber-900/30 dark:text-amber-300',
  APPROVED: 'bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-300',
  RETIRED: 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300',
}

interface ScenarioLibraryGridProps {
  scenarios: StressScenarioDto[]
  loading: boolean
  error: string | null
}

function SortIcon({
  field,
  sortField,
  sortDir,
}: {
  field: SortField
  sortField: SortField
  sortDir: SortDirection
}) {
  if (sortField !== field) return <ChevronsUpDown className="h-3 w-3 inline ml-0.5 opacity-40" />
  if (sortDir === 'asc') return <ChevronUp className="h-3 w-3 inline ml-0.5" />
  return <ChevronDown className="h-3 w-3 inline ml-0.5" />
}

export function ScenarioLibraryGrid({ scenarios, loading, error }: ScenarioLibraryGridProps) {
  const [search, setSearch] = useState('')
  const [typeFilter, setTypeFilter] = useState('')
  const [sortField, setSortField] = useState<SortField>('name')
  const [sortDir, setSortDir] = useState<SortDirection>('asc')

  function handleSortClick(field: SortField) {
    if (sortField === field) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'))
    } else {
      setSortField(field)
      setSortDir('asc')
    }
  }

  const filtered = useMemo(() => {
    let result = scenarios

    if (search.trim()) {
      const term = search.trim().toLowerCase()
      result = result.filter(
        (s) =>
          s.name.toLowerCase().includes(term) || s.description.toLowerCase().includes(term),
      )
    }

    if (typeFilter) {
      result = result.filter((s) => (s.scenarioType ?? 'PARAMETRIC') === typeFilter)
    }

    result = [...result].sort((a, b) => {
      let aVal: string
      let bVal: string
      if (sortField === 'name') {
        aVal = a.name
        bVal = b.name
      } else if (sortField === 'status') {
        aVal = a.status
        bVal = b.status
      } else {
        aVal = a.scenarioType ?? 'PARAMETRIC'
        bVal = b.scenarioType ?? 'PARAMETRIC'
      }
      const cmp = aVal.localeCompare(bVal)
      return sortDir === 'asc' ? cmp : -cmp
    })

    return result
  }, [scenarios, search, typeFilter, sortField, sortDir])

  return (
    <div data-testid="scenario-library-grid" className="space-y-3">
      {/* Controls */}
      <div className="flex items-center gap-2">
        <Input
          data-testid="scenario-library-search"
          type="search"
          placeholder="Search scenarios..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="flex-1"
          aria-label="Search scenarios"
        />
        <Select
          data-testid="scenario-library-type-filter"
          value={typeFilter}
          onChange={(e) => setTypeFilter(e.target.value)}
          aria-label="Filter by type"
        >
          <option value="">All types</option>
          <option value="PARAMETRIC">Parametric</option>
          <option value="HISTORICAL_REPLAY">Historical Replay</option>
          <option value="REVERSE_STRESS">Reverse Stress</option>
        </Select>
      </div>

      {/* Loading */}
      {loading && (
        <div data-testid="scenario-library-loading" className="flex items-center gap-2 text-slate-500 text-sm py-4">
          <Spinner size="sm" />
          Loading scenarios...
        </div>
      )}

      {/* Error */}
      {!loading && error && (
        <div className="py-2">
          <ErrorCard message={error} data-testid="scenario-library-error" />
        </div>
      )}

      {/* Table */}
      {!loading && !error && (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left text-slate-600 dark:text-slate-400">
                <th className="py-2 pr-2">
                  <button
                    data-testid="sort-by-name"
                    onClick={() => handleSortClick('name')}
                    className="hover:text-slate-900 dark:hover:text-slate-100 transition-colors font-semibold"
                  >
                    Name
                    <SortIcon field="name" sortField={sortField} sortDir={sortDir} />
                  </button>
                </th>
                <th className="py-2 pr-2">
                  <button
                    data-testid="sort-by-type"
                    onClick={() => handleSortClick('type')}
                    className="hover:text-slate-900 dark:hover:text-slate-100 transition-colors font-semibold"
                  >
                    Type
                    <SortIcon field="type" sortField={sortField} sortDir={sortDir} />
                  </button>
                </th>
                <th className="py-2 pr-2">
                  <button
                    data-testid="sort-by-status"
                    onClick={() => handleSortClick('status')}
                    className="hover:text-slate-900 dark:hover:text-slate-100 transition-colors font-semibold"
                  >
                    Status
                    <SortIcon field="status" sortField={sortField} sortDir={sortDir} />
                  </button>
                </th>
                <th className="py-2 text-right">Created by</th>
              </tr>
            </thead>
            <tbody>
              {filtered.length === 0 ? (
                <tr>
                  <td colSpan={4}>
                    <div
                      data-testid="scenario-library-empty"
                      className="text-slate-500 text-sm py-6 text-center"
                    >
                      No scenarios match your filters.
                    </div>
                  </td>
                </tr>
              ) : (
                filtered.map((scenario) => {
                  const type = scenario.scenarioType ?? 'PARAMETRIC'
                  return (
                    <tr
                      key={scenario.id}
                      data-testid="scenario-library-row"
                      className="border-b hover:bg-slate-50 dark:hover:bg-slate-800 transition-colors"
                    >
                      <td className="py-2 pr-2">
                        <span className="font-medium text-slate-900 dark:text-slate-100">
                          {scenario.name}
                        </span>
                        {scenario.description && (
                          <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5 truncate max-w-[220px]">
                            {scenario.description}
                          </p>
                        )}
                      </td>
                      <td className="py-2 pr-2">
                        <span
                          data-testid="scenario-type-badge"
                          className={`text-xs font-medium px-2 py-0.5 rounded-full ${TYPE_COLORS[type] ?? ''}`}
                        >
                          {TYPE_LABELS[type] ?? type}
                        </span>
                      </td>
                      <td className="py-2 pr-2">
                        <span
                          className={`text-xs font-medium px-2 py-0.5 rounded-full ${STATUS_COLORS[scenario.status] ?? ''}`}
                        >
                          {scenario.status}
                        </span>
                      </td>
                      <td className="py-2 text-right text-slate-600 dark:text-slate-400">
                        {scenario.createdBy}
                      </td>
                    </tr>
                  )
                })
              )}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

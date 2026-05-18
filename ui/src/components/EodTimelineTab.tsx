import { useState } from 'react'
import { CalendarDays } from 'lucide-react'
import type { EodTimelineEntryDto } from '../types'
import { useEodTimeline } from '../hooks/useEodTimeline'
import { EodDateRangePicker } from './EodDateRangePicker'
import { EodTrendChart } from './EodTrendChart'
import { EodDailyGrid } from './EodDailyGrid'
import { EodDrillPanel } from './EodDrillPanel'
import { EmptyState, ErrorCard } from './ui'

interface EodTimelineTabProps {
  bookId: string | null
}

export function EodTimelineTab({ bookId }: EodTimelineTabProps) {
  const { entries, loading, error, from, to, setFrom, setTo, refresh } = useEodTimeline(bookId)
  const [selectedDate, setSelectedDate] = useState<string | null>(null)
  const [compareDates, setCompareDates] = useState<string[]>([])
  const [showComparison, setShowComparison] = useState(false)

  if (!bookId) {
    return (
      <EmptyState
        icon={<CalendarDays className="h-10 w-10" />}
        title="No book selected"
        description="Select a book to view EOD history."
      />
    )
  }

  const selectedEntry = selectedDate
    ? entries.find((e) => e.valuationDate === selectedDate) ?? null
    : null

  const compareEntry: EodTimelineEntryDto | null =
    showComparison && compareDates.length === 2
      ? (entries.find((e) => e.valuationDate === compareDates.find((d) => d !== selectedDate)) ?? null)
      : null

  const handleSelectDate = (date: string) => {
    setSelectedDate((prev) => (prev === date ? null : date))
    setShowComparison(false)
  }

  const handleRangeChange = (newFrom: string, newTo: string) => {
    setFrom(newFrom)
    setTo(newTo)
    setSelectedDate(null)
    setCompareDates([])
    setShowComparison(false)
  }

  const handleCompare = () => {
    if (compareDates.length === 2) {
      setSelectedDate(compareDates[0])
      setShowComparison(true)
    }
  }

  const handleCloseDrill = () => {
    setSelectedDate(null)
    setShowComparison(false)
  }

  return (
    <div className="space-y-4" data-testid="eod-timeline-tab">
      {/* Control bar */}
      <div className="flex items-center justify-between flex-wrap gap-2">
        <EodDateRangePicker from={from} to={to} onRangeChange={handleRangeChange} />
      </div>

      {/* Error state */}
      {error && (
        <ErrorCard
          message={error}
          onRetry={refresh}
          retryTestId="eod-retry-btn"
          data-testid="eod-error-banner"
        />
      )}

      {/* Trend chart */}
      <div style={{ height: 260 }} className="overflow-hidden">
        <EodTrendChart
          entries={entries}
          selectedDate={selectedDate}
          onSelectDate={handleSelectDate}
          isLoading={loading}
        />
      </div>

      {/* Daily grid */}
      {!loading && !error && entries.length === 0 ? (
        <EmptyState
          icon={<CalendarDays className="h-8 w-8" />}
          title="No EOD history for this period"
          description="Try widening the date range or checking that EOD jobs have been promoted."
        />
      ) : (
        <EodDailyGrid
          entries={entries}
          selectedDate={selectedDate}
          compareDates={compareDates}
          onSelectDate={handleSelectDate}
          onCompareDatesChange={setCompareDates}
          onCompare={handleCompare}
        />
      )}

      {/* Drill panel */}
      {selectedEntry && (
        <EodDrillPanel
          bookId={bookId}
          entry={selectedEntry}
          compareEntry={compareEntry}
          onClose={handleCloseDrill}
        />
      )}
    </div>
  )
}

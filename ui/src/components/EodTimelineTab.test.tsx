import { render, screen, fireEvent } from '@testing-library/react'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { EodTimelineTab } from './EodTimelineTab'
import type { EodTimelineEntryDto } from '../types'
import type { UseEodTimelineResult } from '../hooks/useEodTimeline'

vi.mock('../hooks/useEodTimeline', () => ({
  useEodTimeline: vi.fn(),
}))

vi.mock('./EodTrendChart', () => ({
  EodTrendChart: ({ entries, isLoading }: { entries: EodTimelineEntryDto[]; isLoading: boolean }) => (
    <div data-testid="eod-trend-chart">
      {isLoading ? 'loading-chart' : `chart-entries-${entries.length}`}
    </div>
  ),
}))

vi.mock('./EodDailyGrid', () => ({
  EodDailyGrid: ({
    entries,
    onSelectDate,
  }: {
    entries: EodTimelineEntryDto[]
    onSelectDate: (date: string) => void
  }) => (
    <div data-testid="eod-daily-grid">
      {entries.map((e) => (
        <button key={e.valuationDate} data-testid={`grid-row-${e.valuationDate}`} onClick={() => onSelectDate(e.valuationDate)}>
          {e.valuationDate}
        </button>
      ))}
    </div>
  ),
}))

vi.mock('./EodDrillPanel', () => ({
  EodDrillPanel: ({ entry, onClose }: { entry: EodTimelineEntryDto; onClose: () => void }) => (
    <div data-testid="eod-drill-panel">
      drill-{entry.valuationDate}
      <button data-testid="drill-close-btn" onClick={onClose}>close</button>
    </div>
  ),
}))

vi.mock('./EodDateRangePicker', () => ({
  EodDateRangePicker: ({ from, to }: { from: string; to: string }) => (
    <div data-testid="eod-range-picker">{from}/{to}</div>
  ),
}))

import { useEodTimeline } from '../hooks/useEodTimeline'
const mockUseEodTimeline = useEodTimeline as unknown as ReturnType<typeof vi.fn>

function entry(valuationDate: string, varValue: number = 100_000): EodTimelineEntryDto {
  return {
    valuationDate,
    jobId: `job-${valuationDate}`,
    varValue,
    expectedShortfall: varValue * 1.5,
    pvValue: 5_000_000,
    delta: 0.5,
    gamma: 0.01,
    vega: 200,
    theta: -50,
    rho: 25,
    promotedAt: `${valuationDate}T19:00:00Z`,
    promotedBy: 'risk-manager',
    varChange: null,
    varChangePct: null,
    esChange: null,
    calculationType: 'PARAMETRIC',
    confidenceLevel: 0.99,
  }
}

function mockHookState(overrides: Partial<UseEodTimelineResult> = {}): UseEodTimelineResult {
  return {
    entries: [],
    loading: false,
    error: null,
    from: '2026-03-01',
    to: '2026-04-01',
    setFrom: vi.fn(),
    setTo: vi.fn(),
    refresh: vi.fn(),
    ...overrides,
  }
}

describe('EodTimelineTab', () => {
  beforeEach(() => {
    mockUseEodTimeline.mockReset()
  })

  it('renders the no-book empty state when no bookId is selected', () => {
    mockUseEodTimeline.mockReturnValue(mockHookState())

    render(<EodTimelineTab bookId={null} />)

    expect(screen.getByText('No book selected')).toBeInTheDocument()
    expect(screen.queryByTestId('eod-timeline-tab')).not.toBeInTheDocument()
  })

  it('renders the trend chart and daily grid when entries are present', () => {
    mockUseEodTimeline.mockReturnValue(mockHookState({
      entries: [entry('2026-03-14'), entry('2026-03-15')],
    }))

    render(<EodTimelineTab bookId="BOOK-001" />)

    expect(screen.getByTestId('eod-timeline-tab')).toBeInTheDocument()
    expect(screen.getByTestId('eod-trend-chart')).toHaveTextContent('chart-entries-2')
    expect(screen.getByTestId('grid-row-2026-03-14')).toBeInTheDocument()
    expect(screen.getByTestId('grid-row-2026-03-15')).toBeInTheDocument()
  })

  it('renders the no-history empty state when there are no entries and not loading or erroring', () => {
    mockUseEodTimeline.mockReturnValue(mockHookState({ entries: [], loading: false, error: null }))

    render(<EodTimelineTab bookId="BOOK-001" />)

    expect(screen.getByText('No EOD history for this period')).toBeInTheDocument()
    expect(screen.queryByTestId('eod-daily-grid')).not.toBeInTheDocument()
  })

  it('does not render the no-history empty state while loading', () => {
    mockUseEodTimeline.mockReturnValue(mockHookState({ entries: [], loading: true }))

    render(<EodTimelineTab bookId="BOOK-001" />)

    expect(screen.queryByText('No EOD history for this period')).not.toBeInTheDocument()
    expect(screen.getByTestId('eod-trend-chart')).toHaveTextContent('loading-chart')
  })

  it('shows the error banner with the message and a retry button when the hook reports an error', () => {
    const refresh = vi.fn()
    mockUseEodTimeline.mockReturnValue(mockHookState({
      entries: [],
      error: 'Failed to load EOD timeline: 500',
      refresh,
    }))

    render(<EodTimelineTab bookId="BOOK-001" />)

    const banner = screen.getByTestId('eod-error-banner')
    expect(banner).toHaveTextContent('Failed to load EOD timeline: 500')
    // Banner is rendered via the shared ErrorCard which carries role="alert".
    expect(banner.getAttribute('role')).toBe('alert')

    fireEvent.click(screen.getByTestId('eod-retry-btn'))
    expect(refresh).toHaveBeenCalledTimes(1)
  })

  it('opens the drill panel when a grid row is selected and closes it via the drill close button', () => {
    mockUseEodTimeline.mockReturnValue(mockHookState({
      entries: [entry('2026-03-14'), entry('2026-03-15')],
    }))

    render(<EodTimelineTab bookId="BOOK-001" />)

    expect(screen.queryByTestId('eod-drill-panel')).not.toBeInTheDocument()

    fireEvent.click(screen.getByTestId('grid-row-2026-03-14'))
    expect(screen.getByTestId('eod-drill-panel')).toHaveTextContent('drill-2026-03-14')

    fireEvent.click(screen.getByTestId('drill-close-btn'))
    expect(screen.queryByTestId('eod-drill-panel')).not.toBeInTheDocument()
  })

  it('toggles the drill panel off when the same grid row is clicked twice', () => {
    mockUseEodTimeline.mockReturnValue(mockHookState({
      entries: [entry('2026-03-14')],
    }))

    render(<EodTimelineTab bookId="BOOK-001" />)

    fireEvent.click(screen.getByTestId('grid-row-2026-03-14'))
    expect(screen.getByTestId('eod-drill-panel')).toBeInTheDocument()

    fireEvent.click(screen.getByTestId('grid-row-2026-03-14'))
    expect(screen.queryByTestId('eod-drill-panel')).not.toBeInTheDocument()
  })

  it('closes the drill panel when Escape is pressed at the window level', () => {
    mockUseEodTimeline.mockReturnValue(mockHookState({
      entries: [entry('2026-03-14')],
    }))

    render(<EodTimelineTab bookId="BOOK-001" />)

    fireEvent.click(screen.getByTestId('grid-row-2026-03-14'))
    expect(screen.getByTestId('eod-drill-panel')).toBeInTheDocument()

    fireEvent.keyDown(window, { key: 'Escape' })
    expect(screen.queryByTestId('eod-drill-panel')).not.toBeInTheDocument()
  })

  it('does not close the drill panel on Escape when an input is focused', () => {
    mockUseEodTimeline.mockReturnValue(mockHookState({
      entries: [entry('2026-03-14')],
    }))

    render(<EodTimelineTab bookId="BOOK-001" />)

    fireEvent.click(screen.getByTestId('grid-row-2026-03-14'))
    expect(screen.getByTestId('eod-drill-panel')).toBeInTheDocument()

    // Simulate pressing Escape while an input element is the event target.
    // Dispatching keyDown on the input makes it the event.target and the
    // handler's input-focused guard should prevent the drawer from closing.
    const input = document.createElement('input')
    document.body.appendChild(input)
    fireEvent.keyDown(input, { key: 'Escape' })
    // Panel should still be visible — the guard skips Escape when typing
    expect(screen.getByTestId('eod-drill-panel')).toBeInTheDocument()
    document.body.removeChild(input)
  })

  it('resets the drill panel state when the component unmounts', () => {
    mockUseEodTimeline.mockReturnValue(mockHookState({
      entries: [entry('2026-03-14')],
    }))

    const { unmount } = render(<EodTimelineTab bookId="BOOK-001" />)

    fireEvent.click(screen.getByTestId('grid-row-2026-03-14'))
    expect(screen.getByTestId('eod-drill-panel')).toBeInTheDocument()

    // Unmount simulates the user switching to a different top-level tab.
    // The drawer must not remain in the DOM (stale overlay).
    unmount()
    expect(screen.queryByTestId('eod-drill-panel')).not.toBeInTheDocument()
  })
})

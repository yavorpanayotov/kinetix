import { render, screen, fireEvent } from '@testing-library/react'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import type { VaRHistoryEntry } from '../hooks/useVaR'
import type { TimeRange, TradeAnnotationDto } from '../types'
import { VaRTrendChart } from './VaRTrendChart'

const CONTAINER_WIDTH = 800

let observeCalls = 0

class FakeResizeObserver {
  callback: ResizeObserverCallback
  constructor(callback: ResizeObserverCallback) {
    this.callback = callback
  }
  observe() {
    observeCalls++
    this.callback(
      [{ contentRect: { width: CONTAINER_WIDTH } } as unknown as ResizeObserverEntry],
      this as unknown as ResizeObserver,
    )
  }
  unobserve() {}
  disconnect() {}
}

beforeEach(() => {
  observeCalls = 0
  vi.stubGlobal('ResizeObserver', FakeResizeObserver)
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(CONTAINER_WIDTH)
})

const history: VaRHistoryEntry[] = [
  { varValue: 1_200_000, expectedShortfall: 1_500_000, calculatedAt: '2025-01-15T10:00:00Z', confidenceLevel: 'CL_95' },
  { varValue: 1_300_000, expectedShortfall: 1_600_000, calculatedAt: '2025-01-15T10:30:00Z', confidenceLevel: 'CL_95' },
  { varValue: 1_250_000, expectedShortfall: 1_550_000, calculatedAt: '2025-01-15T11:00:00Z', confidenceLevel: 'CL_95' },
  { varValue: 1_400_000, expectedShortfall: 1_700_000, calculatedAt: '2025-01-15T11:30:00Z', confidenceLevel: 'CL_95' },
  { varValue: 1_350_000, expectedShortfall: 1_650_000, calculatedAt: '2025-01-15T12:00:00Z', confidenceLevel: 'CL_95' },
]

describe('VaRTrendChart', () => {
  it('renders empty state for zero data points', () => {
    render(<VaRTrendChart history={[]} />)

    expect(screen.getByTestId('var-trend-chart')).toHaveTextContent('No calculations yet for this time range.')
  })

  it('shows message instead of chart for single data point', () => {
    render(<VaRTrendChart history={[history[0]]} />)

    const panel = screen.getByTestId('var-trend-chart')
    expect(panel).toHaveTextContent('Needs at least 2 calculations to draw a trend.')
    expect(panel.querySelector('svg')).not.toBeInTheDocument()
  })

  it('renders skeleton when isLoading is true and history is empty', () => {
    render(<VaRTrendChart history={[]} isLoading />)

    const chart = screen.getByTestId('var-trend-chart')
    const skeleton = chart.querySelector('[role="status"]')
    expect(skeleton).toBeInTheDocument()
    expect(skeleton).toHaveAttribute('aria-label', 'Loading chart data')
    expect(chart).not.toHaveTextContent('No calculations yet')
  })

  it('renders skeleton when isLoading is true and history has one entry', () => {
    render(<VaRTrendChart history={[history[0]]} isLoading />)

    const chart = screen.getByTestId('var-trend-chart')
    const skeleton = chart.querySelector('[role="status"]')
    expect(skeleton).toBeInTheDocument()
    expect(chart).not.toHaveTextContent('Needs at least 2 calculations')
  })

  it('shows empty state text when isLoading is false and history is empty', () => {
    render(<VaRTrendChart history={[]} isLoading={false} />)

    expect(screen.getByTestId('var-trend-chart')).toHaveTextContent('No calculations yet for this time range.')
  })

  it('renders the chart panel with header', () => {
    render(<VaRTrendChart history={history} />)

    const panel = screen.getByTestId('var-trend-chart')
    expect(panel).toBeInTheDocument()
    expect(panel).toHaveTextContent('VaR Trend')
  })

  it('displays the latest VaR value in the header', () => {
    render(<VaRTrendChart history={history} />)

    expect(screen.getByTestId('var-trend-chart')).toHaveTextContent('$1,350,000')
  })

  it('renders an SVG with a polyline for the data', () => {
    render(<VaRTrendChart history={history} />)

    const svg = screen.getByTestId('var-trend-chart').querySelector('svg')
    expect(svg).toBeInTheDocument()

    const polyline = svg!.querySelector('polyline[stroke="#6366f1"]')
    expect(polyline).toBeInTheDocument()
  })

  it('renders an area fill polygon', () => {
    render(<VaRTrendChart history={history} />)

    const svg = screen.getByTestId('var-trend-chart').querySelector('svg')
    const polygon = svg!.querySelector('polygon[fill="rgba(99, 102, 241, 0.15)"]')
    expect(polygon).toBeInTheDocument()
  })

  it('renders Y-axis grid lines with labels', () => {
    render(<VaRTrendChart history={history} />)

    const svg = screen.getByTestId('var-trend-chart').querySelector('svg')
    const gridLines = svg!.querySelectorAll('line[stroke-dasharray]')
    expect(gridLines.length).toBeGreaterThanOrEqual(3)

    const yLabels = svg!.querySelectorAll('text[text-anchor="end"]')
    expect(yLabels.length).toBeGreaterThanOrEqual(3)
  })

  describe('Y-axis auto-fit', () => {
    // Parse a compact-currency label ("$190K", "$1.2M", "$0", "$3M") back to a
    // numeric value so we can reason about the rendered domain.
    const parseCompactCurrency = (raw: string): number => {
      const m = /^(-)?\$([0-9.]+)([KMB]?)$/.exec(raw.trim())
      if (!m) return NaN
      const sign = m[1] ? -1 : 1
      const num = parseFloat(m[2])
      const unit = m[3]
      const scale = unit === 'B' ? 1_000_000_000 : unit === 'M' ? 1_000_000 : unit === 'K' ? 1_000 : 1
      return sign * num * scale
    }

    const readYAxisLabels = (): string[] => {
      const svg = screen.getByTestId('var-trend-chart').querySelector('svg')!
      return Array.from(svg.querySelectorAll('text[text-anchor="end"]')).map(
        (el) => el.textContent ?? '',
      )
    }

    it('keeps the computed Y-domain max within 1.5x of the max series value', () => {
      // VaR / ES both around 190K — typical trader's-view scenario where the
      // recent VaR was ~$190K and we must not blow the axis up to ~$3M.
      const tightHistory: VaRHistoryEntry[] = [
        { varValue: 190_000, expectedShortfall: 230_000, calculatedAt: '2025-01-15T10:00:00Z', confidenceLevel: 'CL_95' },
        { varValue: 195_000, expectedShortfall: 235_000, calculatedAt: '2025-01-15T10:30:00Z', confidenceLevel: 'CL_95' },
        { varValue: 188_000, expectedShortfall: 228_000, calculatedAt: '2025-01-15T11:00:00Z', confidenceLevel: 'CL_95' },
        { varValue: 192_000, expectedShortfall: 232_000, calculatedAt: '2025-01-15T11:30:00Z', confidenceLevel: 'CL_95' },
        { varValue: 191_000, expectedShortfall: 231_000, calculatedAt: '2025-01-15T12:00:00Z', confidenceLevel: 'CL_95' },
      ]

      render(<VaRTrendChart history={tightHistory} />)

      const labels = readYAxisLabels()
      expect(labels.length).toBeGreaterThan(0)

      const maxLabelValue = Math.max(...labels.map(parseCompactCurrency).filter((v) => !Number.isNaN(v)))
      const maxSeriesValue = Math.max(
        ...tightHistory.flatMap((e) => [e.varValue, e.expectedShortfall]),
      )

      expect(maxLabelValue).toBeLessThanOrEqual(maxSeriesValue * 1.5)
    })

    it('does not show $1M / $2M / $3M labels when all series values are around $190K', () => {
      const smallHistory: VaRHistoryEntry[] = Array.from({ length: 6 }, (_, i) => ({
        varValue: 190_000 + i * 1_000,
        expectedShortfall: 238_000 + i * 1_000,
        calculatedAt: `2025-01-15T1${i}:00:00Z`,
        confidenceLevel: 'CL_95',
      }))

      render(<VaRTrendChart history={smallHistory} />)

      const labels = readYAxisLabels()
      // No grid label should jump into the millions when the data is in the
      // 200K range — this is the trader's "looks like a flatline" complaint.
      expect(labels.some((l) => /\$\d+(\.\d+)?M/.test(l))).toBe(false)
    })

    it('clamps Y-domain top to dataMax * ~1.2 even when the series is a single tight cluster', () => {
      const dataMax = 190_000
      const constantHistory: VaRHistoryEntry[] = Array.from({ length: 5 }, (_, i) => ({
        varValue: dataMax,
        expectedShortfall: dataMax,
        calculatedAt: `2025-01-15T1${i}:00:00Z`,
        confidenceLevel: 'CL_95',
      }))

      render(<VaRTrendChart history={constantHistory} />)

      const labels = readYAxisLabels()
      const maxLabelValue = Math.max(...labels.map(parseCompactCurrency).filter((v) => !Number.isNaN(v)))

      // Axis top should be close to dataMax — well below a hard-coded 3M scale.
      expect(maxLabelValue).toBeLessThanOrEqual(dataMax * 1.5)
      expect(maxLabelValue).toBeGreaterThan(0)
    })

    it('ignores zero-sentinel ES values (null fallback) when fitting the Y-domain', () => {
      // useVaR.loadHistory converts null expectedShortfall to 0. The chart
      // should not let those sentinel zeros drag the Y-axis bottom below the
      // meaningful VaR range — VaR is conventionally non-negative, and the
      // Y-domain max must stay tight to the real data.
      const sentinelHistory: VaRHistoryEntry[] = [
        { varValue: 190_000, expectedShortfall: 0, calculatedAt: '2025-01-15T10:00:00Z', confidenceLevel: 'CL_95' },
        { varValue: 192_000, expectedShortfall: 0, calculatedAt: '2025-01-15T10:30:00Z', confidenceLevel: 'CL_95' },
        { varValue: 188_000, expectedShortfall: 0, calculatedAt: '2025-01-15T11:00:00Z', confidenceLevel: 'CL_95' },
        { varValue: 195_000, expectedShortfall: 0, calculatedAt: '2025-01-15T11:30:00Z', confidenceLevel: 'CL_95' },
      ]

      render(<VaRTrendChart history={sentinelHistory} />)

      const labels = readYAxisLabels()
      const labelValues = labels.map(parseCompactCurrency).filter((v) => !Number.isNaN(v))
      const maxLabelValue = Math.max(...labelValues)
      const maxVarValue = Math.max(...sentinelHistory.map((e) => e.varValue))

      // Domain max should remain anchored to the real VaR values, not pulled
      // down/up by zero sentinels.
      expect(maxLabelValue).toBeLessThanOrEqual(maxVarValue * 1.5)
      expect(maxLabelValue).toBeGreaterThanOrEqual(maxVarValue * 0.9)
    })
  })

  it('renders X-axis timestamp labels', () => {
    render(<VaRTrendChart history={history} />)

    const svg = screen.getByTestId('var-trend-chart').querySelector('svg')
    const xLabels = svg!.querySelectorAll('text[text-anchor="middle"]')
    expect(xLabels.length).toBeGreaterThanOrEqual(2)
  })

  it('shows crosshair and tooltip on hover', () => {
    render(<VaRTrendChart history={history} />)

    const svg = screen.getByTestId('var-trend-chart').querySelector('svg')!
    fireEvent.mouseMove(svg, { clientX: 200, clientY: 100 })

    const crosshair = svg.querySelector('line[data-testid="crosshair"]')
    expect(crosshair).toBeInTheDocument()

    const tooltip = screen.getByTestId('var-trend-tooltip')
    expect(tooltip).toBeInTheDocument()
  })

  it('shows hover dot on mousemove', () => {
    render(<VaRTrendChart history={history} />)

    const svg = screen.getByTestId('var-trend-chart').querySelector('svg')!
    fireEvent.mouseMove(svg, { clientX: 200, clientY: 100 })

    const dot = svg.querySelector('circle[data-testid="hover-dot"]')
    expect(dot).toBeInTheDocument()
  })

  it('hides crosshair and tooltip on mouse leave', () => {
    render(<VaRTrendChart history={history} />)

    const svg = screen.getByTestId('var-trend-chart').querySelector('svg')!
    fireEvent.mouseMove(svg, { clientX: 200, clientY: 100 })
    expect(screen.getByTestId('var-trend-tooltip')).toBeInTheDocument()

    fireEvent.mouseLeave(svg)
    expect(screen.queryByTestId('var-trend-tooltip')).not.toBeInTheDocument()
  })

  it('uses ResizeObserver for responsive width', () => {
    render(<VaRTrendChart history={history} />)

    expect(observeCalls).toBeGreaterThan(0)
  })

  it('attaches ResizeObserver when history transitions from empty to data', () => {
    const { rerender } = render(<VaRTrendChart history={[]} />)

    // Placeholder render — no ref attached, observer may have run but on null
    const initialCalls = observeCalls

    // Transition to having data — should re-attach ResizeObserver
    rerender(<VaRTrendChart history={history} />)

    expect(observeCalls).toBeGreaterThan(initialCalls)
  })

  it('X-axis labels reflect the selected time range, not just the data extent', () => {
    // All data is within 2 hours, but the Custom timeRange covers 7 days
    const weekRange: TimeRange = {
      from: '2025-01-08T12:00:00Z',
      to: '2025-01-15T12:00:00Z',
      label: 'Custom',
    }

    const { rerender } = render(
      <VaRTrendChart history={history} timeRange={weekRange} />,
    )

    const svg = screen.getByTestId('var-trend-chart').querySelector('svg')!
    const weekLabels = Array.from(svg.querySelectorAll('text[text-anchor="middle"]')).map(
      (el) => el.textContent,
    )

    // 7-day range uses "Mon DD" format (from formatChartTime)
    expect(weekLabels.some((l) => l?.includes('Jan'))).toBe(true)

    // Switch to a 1-hour Custom range — labels should change to "HH:MM" format
    const hourRange: TimeRange = {
      from: '2025-01-15T11:00:00Z',
      to: '2025-01-15T12:00:00Z',
      label: 'Custom',
    }

    rerender(<VaRTrendChart history={history} timeRange={hourRange} />)

    const hourLabels = Array.from(svg.querySelectorAll('text[text-anchor="middle"]')).map(
      (el) => el.textContent,
    )

    // 1-hour range uses "HH:MM" format — should NOT contain "Jan"
    expect(hourLabels.some((l) => l?.includes('Jan'))).toBe(false)
    expect(hourLabels.some((l) => /^\d{2}:\d{2}$/.test(l ?? ''))).toBe(true)
  })

  it('renders ES polyline with amber stroke', () => {
    render(<VaRTrendChart history={history} />)

    const svg = screen.getByTestId('var-trend-chart').querySelector('svg')!
    const polylines = svg.querySelectorAll('polyline')
    const esPolyline = Array.from(polylines).find((p) => p.getAttribute('stroke') === '#f59e0b')
    expect(esPolyline).toBeInTheDocument()
  })

  it('renders ES area fill polygon', () => {
    render(<VaRTrendChart history={history} />)

    const svg = screen.getByTestId('var-trend-chart').querySelector('svg')!
    const polygons = svg.querySelectorAll('polygon')
    const esArea = Array.from(polygons).find((p) => p.getAttribute('fill') === 'rgba(245, 158, 11, 0.10)')
    expect(esArea).toBeInTheDocument()
  })

  it('shows ES hover dot on mousemove', () => {
    render(<VaRTrendChart history={history} />)

    const svg = screen.getByTestId('var-trend-chart').querySelector('svg')!
    fireEvent.mouseMove(svg, { clientX: 200, clientY: 100 })

    const dot = svg.querySelector('circle[data-testid="hover-dot-es"]')
    expect(dot).toBeInTheDocument()
    expect(dot).toHaveAttribute('fill', '#f59e0b')
  })

  it('renders a legend with VaR and ES labels', () => {
    render(<VaRTrendChart history={history} />)

    const chart = screen.getByTestId('var-trend-chart')
    expect(chart).toHaveTextContent('VaR')
    expect(chart).toHaveTextContent('ES')

    const legend = chart.querySelector('.bg-indigo-500')
    expect(legend).toBeInTheDocument()
    const esLegend = chart.querySelector('.bg-amber-500')
    expect(esLegend).toBeInTheDocument()
  })

  describe('legend toggle interaction', () => {
    it('renders legend items as button elements for accessibility', () => {
      render(<VaRTrendChart history={history} />)

      const varButton = screen.getByTestId('legend-toggle-var')
      const esButton = screen.getByTestId('legend-toggle-es')

      expect(varButton.tagName).toBe('BUTTON')
      expect(esButton.tagName).toBe('BUTTON')
    })

    it('has cursor-pointer on legend buttons', () => {
      render(<VaRTrendChart history={history} />)

      const varButton = screen.getByTestId('legend-toggle-var')
      expect(varButton).toHaveStyle({ cursor: 'pointer' })
    })

    it('clicking VaR legend button isolates VaR series and hides ES', () => {
      render(<VaRTrendChart history={history} />)

      fireEvent.click(screen.getByTestId('legend-toggle-var'))

      const chart = screen.getByTestId('var-trend-chart')
      const svg = chart.querySelector('svg')!

      // VaR polyline should be fully visible (opacity 1)
      const varPolyline = svg.querySelector('polyline[stroke="#6366f1"]')
      expect(varPolyline).toHaveAttribute('opacity', '1')

      // ES polyline should be dimmed (opacity 0.35)
      const esPolyline = svg.querySelector('polyline[stroke="#f59e0b"]')
      expect(esPolyline).toHaveAttribute('opacity', '0.35')
    })

    it('clicking ES legend button isolates ES series and hides VaR', () => {
      render(<VaRTrendChart history={history} />)

      fireEvent.click(screen.getByTestId('legend-toggle-es'))

      const chart = screen.getByTestId('var-trend-chart')
      const svg = chart.querySelector('svg')!

      // ES polyline should be fully visible (opacity 1)
      const esPolyline = svg.querySelector('polyline[stroke="#f59e0b"]')
      expect(esPolyline).toHaveAttribute('opacity', '1')

      // VaR polyline should be dimmed (opacity 0.35)
      const varPolyline = svg.querySelector('polyline[stroke="#6366f1"]')
      expect(varPolyline).toHaveAttribute('opacity', '0.35')
    })

    it('clicking the isolated series again restores all series to full opacity', () => {
      render(<VaRTrendChart history={history} />)

      // Isolate VaR
      fireEvent.click(screen.getByTestId('legend-toggle-var'))
      // Click isolated VaR again — should restore
      fireEvent.click(screen.getByTestId('legend-toggle-var'))

      const svg = screen.getByTestId('var-trend-chart').querySelector('svg')!

      const varPolyline = svg.querySelector('polyline[stroke="#6366f1"]')
      const esPolyline = svg.querySelector('polyline[stroke="#f59e0b"]')

      expect(varPolyline).toHaveAttribute('opacity', '1')
      expect(esPolyline).toHaveAttribute('opacity', '1')
    })

    it('applies line-through text decoration to hidden series label', () => {
      render(<VaRTrendChart history={history} />)

      fireEvent.click(screen.getByTestId('legend-toggle-var'))

      // ES button label text should have line-through
      const esButton = screen.getByTestId('legend-toggle-es')
      const esLabel = esButton.querySelector('[data-testid="legend-label-es"]')
      expect(esLabel).toHaveStyle({ textDecoration: 'line-through' })
    })

    it('removes line-through when series is restored', () => {
      render(<VaRTrendChart history={history} />)

      fireEvent.click(screen.getByTestId('legend-toggle-var'))
      fireEvent.click(screen.getByTestId('legend-toggle-var'))

      const esLabel = screen.getByTestId('legend-label-es')
      expect(esLabel).not.toHaveStyle({ textDecoration: 'line-through' })
    })

    it('rescales Y-axis to only visible series data when one series is isolated', () => {
      // VaR values: 1.2M to 1.4M; ES values: 1.5M to 1.7M
      // When only VaR is visible, Y-axis max should not reach 1.7M
      render(<VaRTrendChart history={history} />)

      // Initially both series visible — Y axis covers both ranges
      const svgBefore = screen.getByTestId('var-trend-chart').querySelector('svg')!
      const yLabelsBefore = Array.from(svgBefore.querySelectorAll('text[text-anchor="end"]'))
        .map((el) => el.textContent ?? '')
        .join(' ')

      // Isolate VaR only
      fireEvent.click(screen.getByTestId('legend-toggle-var'))

      const svgAfter = screen.getByTestId('var-trend-chart').querySelector('svg')!
      const yLabelsAfter = Array.from(svgAfter.querySelectorAll('text[text-anchor="end"]'))
        .map((el) => el.textContent ?? '')
        .join(' ')

      // After isolating VaR (max ~1.4M), ES max values (~1.7M) should no longer appear on axis
      expect(yLabelsBefore).not.toEqual(yLabelsAfter)
    })

    it('Ctrl+click toggles individual series without affecting others', () => {
      render(<VaRTrendChart history={history} />)

      fireEvent.click(screen.getByTestId('legend-toggle-es'), { ctrlKey: true })

      const svg = screen.getByTestId('var-trend-chart').querySelector('svg')!
      expect(svg.querySelector('polyline[stroke="#6366f1"]')).toHaveAttribute('opacity', '1')
      expect(svg.querySelector('polyline[stroke="#f59e0b"]')).toHaveAttribute('opacity', '0.35')
    })

    it('Ctrl+click restores a hidden series without affecting others', () => {
      render(<VaRTrendChart history={history} />)

      // Click VaR to isolate (hides ES)
      fireEvent.click(screen.getByTestId('legend-toggle-var'))
      // Ctrl+click ES to bring it back
      fireEvent.click(screen.getByTestId('legend-toggle-es'), { ctrlKey: true })

      const svg = screen.getByTestId('var-trend-chart').querySelector('svg')!
      expect(svg.querySelector('polyline[stroke="#6366f1"]')).toHaveAttribute('opacity', '1')
      expect(svg.querySelector('polyline[stroke="#f59e0b"]')).toHaveAttribute('opacity', '1')
    })

    it('Ctrl+click does not hide the last visible series', () => {
      render(<VaRTrendChart history={history} />)

      // Ctrl+click ES to hide it
      fireEvent.click(screen.getByTestId('legend-toggle-es'), { ctrlKey: true })
      // Try Ctrl+click VaR — should NOT hide because it is the last visible
      fireEvent.click(screen.getByTestId('legend-toggle-var'), { ctrlKey: true })

      const svg = screen.getByTestId('var-trend-chart').querySelector('svg')!
      expect(svg.querySelector('polyline[stroke="#6366f1"]')).toHaveAttribute('opacity', '1')
    })

    it('Cmd+click (metaKey) works the same as Ctrl+click', () => {
      render(<VaRTrendChart history={history} />)

      fireEvent.click(screen.getByTestId('legend-toggle-es'), { metaKey: true })

      const svg = screen.getByTestId('var-trend-chart').querySelector('svg')!
      expect(svg.querySelector('polyline[stroke="#6366f1"]')).toHaveAttribute('opacity', '1')
      expect(svg.querySelector('polyline[stroke="#f59e0b"]')).toHaveAttribute('opacity', '0.35')
    })

    it('area fills are also dimmed when their series is hidden', () => {
      render(<VaRTrendChart history={history} />)

      fireEvent.click(screen.getByTestId('legend-toggle-var'))

      const svg = screen.getByTestId('var-trend-chart').querySelector('svg')!

      // VaR area fill should be visible (opacity 1)
      const varArea = svg.querySelector('polygon[fill="rgba(99, 102, 241, 0.15)"]')
      expect(varArea).toHaveAttribute('opacity', '1')

      // ES area fill should be dimmed (opacity 0.35)
      const esArea = svg.querySelector('polygon[fill="rgba(245, 158, 11, 0.10)"]')
      expect(esArea).toHaveAttribute('opacity', '0.35')
    })
  })

  describe('zoom interaction', () => {
    it('renders reset zoom button when zoomDepth > 0', () => {
      render(
        <VaRTrendChart
          history={history}
          onZoom={vi.fn()}
          zoomDepth={1}
          onResetZoom={vi.fn()}
        />,
      )

      expect(screen.getByTestId('reset-zoom')).toBeInTheDocument()
      expect(screen.getByTestId('reset-zoom')).toHaveTextContent('Reset zoom')
    })

    it('hides reset zoom button when zoomDepth is 0', () => {
      render(
        <VaRTrendChart
          history={history}
          onZoom={vi.fn()}
          zoomDepth={0}
          onResetZoom={vi.fn()}
        />,
      )

      expect(screen.queryByTestId('reset-zoom')).not.toBeInTheDocument()
    })

    it('calls onResetZoom when reset zoom button is clicked', () => {
      const onResetZoom = vi.fn()

      render(
        <VaRTrendChart
          history={history}
          onZoom={vi.fn()}
          zoomDepth={2}
          onResetZoom={onResetZoom}
        />,
      )

      fireEvent.click(screen.getByTestId('reset-zoom'))
      expect(onResetZoom).toHaveBeenCalledTimes(1)
    })

    it('calls onZoom with a Custom TimeRange on brush drag', () => {
      const onZoom = vi.fn()

      render(
        <VaRTrendChart
          history={history}
          onZoom={onZoom}
          zoomDepth={0}
          onResetZoom={vi.fn()}
        />,
      )

      const svg = screen.getByTestId('var-trend-chart').querySelector('svg')!

      fireEvent.mouseDown(svg, { clientX: 100, clientY: 50 })
      fireEvent.mouseMove(svg, { clientX: 300, clientY: 50 })
      fireEvent.mouseUp(svg)

      expect(onZoom).toHaveBeenCalledTimes(1)
      const zoomRange = onZoom.mock.calls[0][0] as TimeRange
      expect(zoomRange.label).toBe('Custom')
      expect(zoomRange.from).toBeDefined()
      expect(zoomRange.to).toBeDefined()
    })

    it('renders brush overlay rect during drag', () => {
      render(
        <VaRTrendChart
          history={history}
          onZoom={vi.fn()}
          zoomDepth={0}
          onResetZoom={vi.fn()}
        />,
      )

      const svg = screen.getByTestId('var-trend-chart').querySelector('svg')!

      fireEvent.mouseDown(svg, { clientX: 100, clientY: 50 })
      fireEvent.mouseMove(svg, { clientX: 300, clientY: 50 })

      const overlay = svg.querySelector('rect[fill="rgba(99, 102, 241, 0.2)"]')
      expect(overlay).toBeInTheDocument()
    })
  })

  describe('leading void and gap indicators', () => {
    it('renders leading void when data starts after the time range begins', () => {
      // Data starts at 11:00 but time range starts at 08:00 — large leading void
      const timeRange: TimeRange = {
        from: '2025-01-15T08:00:00Z',
        to: '2025-01-15T13:00:00Z',
        label: 'Custom',
      }

      render(<VaRTrendChart history={history} timeRange={timeRange} />)

      expect(screen.getByTestId('leading-void')).toBeInTheDocument()
    })

    it('does not render leading void when data starts near the time range start', () => {
      // Data starts at 10:00 and time range starts at 10:00 — no leading void
      const timeRange: TimeRange = {
        from: '2025-01-15T10:00:00Z',
        to: '2025-01-15T12:00:00Z',
        label: 'Custom',
      }

      render(<VaRTrendChart history={history} timeRange={timeRange} />)

      expect(screen.queryByTestId('leading-void')).not.toBeInTheDocument()
    })

    it('shows "No data" text when the leading void is wide', () => {
      // Data starts at 11:00 but time range starts at 06:00 — very wide void (>30%)
      const lateHistory: VaRHistoryEntry[] = [
        { varValue: 100, expectedShortfall: 120, calculatedAt: '2025-01-15T11:00:00Z', confidenceLevel: 'CL_95' },
        { varValue: 110, expectedShortfall: 130, calculatedAt: '2025-01-15T11:30:00Z', confidenceLevel: 'CL_95' },
      ]
      const timeRange: TimeRange = {
        from: '2025-01-15T06:00:00Z',
        to: '2025-01-15T12:00:00Z',
        label: 'Custom',
      }

      render(<VaRTrendChart history={lateHistory} timeRange={timeRange} />)

      const voidGroup = screen.getByTestId('leading-void')
      const noDataTexts = voidGroup.querySelectorAll('text')
      const hasNoDataLabel = Array.from(noDataTexts).some((t) => t.textContent === 'No data')
      expect(hasNoDataLabel).toBe(true)
    })

    it('shows coverage annotation with data start time and count', () => {
      const timeRange: TimeRange = {
        from: '2025-01-15T08:00:00Z',
        to: '2025-01-15T13:00:00Z',
        label: 'Custom',
      }

      render(<VaRTrendChart history={history} timeRange={timeRange} />)

      const annotation = screen.getByTestId('coverage-annotation')
      expect(annotation).toHaveTextContent('5 calculations')
    })

    it('does not show coverage annotation when there is no leading void', () => {
      const timeRange: TimeRange = {
        from: '2025-01-15T10:00:00Z',
        to: '2025-01-15T12:00:00Z',
        label: 'Custom',
      }

      render(<VaRTrendChart history={history} timeRange={timeRange} />)

      expect(screen.queryByTestId('coverage-annotation')).not.toBeInTheDocument()
    })

    it('renders gap regions for large gaps between data points', () => {
      // Create history with a large gap in the middle
      const gappedHistory: VaRHistoryEntry[] = [
        { varValue: 100, expectedShortfall: 120, calculatedAt: '2025-01-15T10:00:00Z', confidenceLevel: 'CL_95' },
        { varValue: 110, expectedShortfall: 130, calculatedAt: '2025-01-15T10:15:00Z', confidenceLevel: 'CL_95' },
        { varValue: 120, expectedShortfall: 140, calculatedAt: '2025-01-15T10:30:00Z', confidenceLevel: 'CL_95' },
        // 3-hour gap (much larger than the 15-min interval)
        { varValue: 130, expectedShortfall: 150, calculatedAt: '2025-01-15T13:30:00Z', confidenceLevel: 'CL_95' },
        { varValue: 140, expectedShortfall: 160, calculatedAt: '2025-01-15T13:45:00Z', confidenceLevel: 'CL_95' },
        { varValue: 150, expectedShortfall: 170, calculatedAt: '2025-01-15T14:00:00Z', confidenceLevel: 'CL_95' },
      ]

      render(<VaRTrendChart history={gappedHistory} />)

      const gaps = screen.getAllByTestId('gap-region')
      expect(gaps.length).toBe(1)
    })

    it('does not render gap regions when data is evenly spaced', () => {
      render(<VaRTrendChart history={history} />)

      expect(screen.queryByTestId('gap-region')).not.toBeInTheDocument()
    })

    it('suppresses tooltip in the leading void zone', () => {
      const timeRange: TimeRange = {
        from: '2025-01-15T08:00:00Z',
        to: '2025-01-15T13:00:00Z',
        label: 'Custom',
      }

      render(<VaRTrendChart history={history} timeRange={timeRange} />)

      const svg = screen.getByTestId('var-trend-chart').querySelector('svg')!

      // Move mouse to the far left — inside the void zone
      fireEvent.mouseMove(svg, { clientX: 10, clientY: 100 })

      expect(screen.queryByTestId('var-trend-tooltip')).not.toBeInTheDocument()
    })
  })

  describe('trade annotations', () => {
    const makeAnnotation = (
      timestamp: string,
      overrides: Partial<TradeAnnotationDto> = {},
    ): TradeAnnotationDto => ({
      timestamp,
      instrumentId: 'AAPL',
      side: 'BUY',
      quantity: '100',
      tradeId: 'T001',
      ...overrides,
    })

    it('renders a trade marker for each annotation inside the visible time range', () => {
      const annotations = [
        makeAnnotation('2025-01-15T10:30:00Z', { tradeId: 'T001' }),
        makeAnnotation('2025-01-15T11:00:00Z', { tradeId: 'T002', side: 'SELL' }),
      ]

      const { container } = render(
        <VaRTrendChart history={history} tradeAnnotations={annotations} />,
      )

      const markers = container.querySelectorAll('[data-testid="trade-marker"]')
      expect(markers.length).toBe(2)
    })

    it('renders no trade markers when tradeAnnotations is empty', () => {
      const { container } = render(
        <VaRTrendChart history={history} tradeAnnotations={[]} />,
      )

      const markers = container.querySelectorAll('[data-testid="trade-marker"]')
      expect(markers.length).toBe(0)
    })

    it('renders no trade markers when tradeAnnotations prop is omitted', () => {
      const { container } = render(<VaRTrendChart history={history} />)

      const markers = container.querySelectorAll('[data-testid="trade-marker"]')
      expect(markers.length).toBe(0)
    })

    it('skips trade annotations outside the visible time extent', () => {
      const timeRange: TimeRange = {
        from: '2025-01-15T10:00:00Z',
        to: '2025-01-15T12:00:00Z',
        label: 'Custom',
      }
      const annotations = [
        makeAnnotation('2025-01-15T11:00:00Z', { tradeId: 'in-range' }),
        makeAnnotation('2025-01-14T00:00:00Z', { tradeId: 'before' }),
        makeAnnotation('2025-01-16T00:00:00Z', { tradeId: 'after' }),
      ]

      const { container } = render(
        <VaRTrendChart history={history} timeRange={timeRange} tradeAnnotations={annotations} />,
      )

      const markers = container.querySelectorAll('[data-testid="trade-marker"]')
      expect(markers.length).toBe(1)
    })
  })

  describe('stress windows', () => {
    it('renders a stress band that overlaps the visible time range', () => {
      const timeRange: TimeRange = {
        from: '2025-01-15T10:00:00Z',
        to: '2025-01-15T12:00:00Z',
        label: 'Custom',
      }
      const stressWindows = [
        { label: '2020-analog', start: '2025-01-15T10:30:00Z', end: '2025-01-15T11:00:00Z' },
      ]

      render(<VaRTrendChart history={history} timeRange={timeRange} stressWindows={stressWindows} />)

      expect(screen.getByTestId('stress-band-2020-analog')).toBeInTheDocument()
      expect(screen.getByTestId('stress-label-2020-analog')).toHaveTextContent('2020-analog')
    })

    it('does not render a stress band that lies entirely outside the visible range', () => {
      const timeRange: TimeRange = {
        from: '2025-01-15T10:00:00Z',
        to: '2025-01-15T12:00:00Z',
        label: 'Custom',
      }
      const stressWindows = [
        { label: 'far-past', start: '2024-01-01T00:00:00Z', end: '2024-01-02T00:00:00Z' },
      ]

      render(<VaRTrendChart history={history} timeRange={timeRange} stressWindows={stressWindows} />)

      expect(screen.queryByTestId('stress-band-far-past')).not.toBeInTheDocument()
    })

    it('renders multiple bands when several windows are visible', () => {
      const timeRange: TimeRange = {
        from: '2025-01-15T10:00:00Z',
        to: '2025-01-15T12:00:00Z',
        label: 'Custom',
      }
      const stressWindows = [
        { label: '2020-analog', start: '2025-01-15T10:15:00Z', end: '2025-01-15T10:30:00Z' },
        { label: '2022-analog', start: '2025-01-15T11:15:00Z', end: '2025-01-15T11:45:00Z' },
      ]

      render(<VaRTrendChart history={history} timeRange={timeRange} stressWindows={stressWindows} />)

      expect(screen.getByTestId('stress-band-2020-analog')).toBeInTheDocument()
      expect(screen.getByTestId('stress-band-2022-analog')).toBeInTheDocument()
    })
  })
})

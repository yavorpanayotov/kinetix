import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { CounterpartyRiskDashboard } from './CounterpartyRiskDashboard'

vi.mock('../hooks/useCounterpartyRisk', () => ({
  useCounterpartyRisk: vi.fn(),
}))

import { useCounterpartyRisk } from '../hooks/useCounterpartyRisk'

const mockUseCounterpartyRisk = vi.mocked(useCounterpartyRisk)

const SAMPLE_EXPOSURE = {
  counterpartyId: 'CP-GS',
  calculatedAt: '2026-03-24T10:00:00Z',
  currentNetExposure: 2_000_000,
  peakPfe: 1_800_000,
  cva: 12_500,
  cvaEstimated: false,
  currency: 'USD',
  pfeProfile: [
    { tenor: '1Y', tenorYears: 1, expectedExposure: 1_500_000, pfe95: 1_800_000, pfe99: 2_000_000 },
    { tenor: '2Y', tenorYears: 2, expectedExposure: 1_200_000, pfe95: 1_500_000, pfe99: 1_700_000 },
  ],
}

const HIGH_EXPOSURE = {
  ...SAMPLE_EXPOSURE,
  counterpartyId: 'CP-JPM',
  currentNetExposure: 6_000_000,
  peakPfe: 7_000_000,
  cva: null,
  cvaEstimated: false,
}

// 12-counterparty fleet: 9 small (each 100k * (i+1) → 100k..900k), 3 large
// (5M, 6M, 8M). Top decile threshold lands on the 3rd-from-top.
function buildFleet() {
  const tail = Array.from({ length: 9 }, (_, i) => ({
    ...SAMPLE_EXPOSURE,
    counterpartyId: `CP-SMALL-${i}`,
    currentNetExposure: 100_000 * (i + 1),
  }))
  const top = [
    { ...SAMPLE_EXPOSURE, counterpartyId: 'CP-BIG-1', currentNetExposure: 5_000_000 },
    { ...SAMPLE_EXPOSURE, counterpartyId: 'CP-BIG-2', currentNetExposure: 6_000_000 },
    { ...SAMPLE_EXPOSURE, counterpartyId: 'CP-BIG-3', currentNetExposure: 8_000_000 },
  ]
  return [...tail, ...top]
}

const defaultHook = {
  exposures: [],
  selected: null,
  history: [],
  loading: false,
  computing: false,
  error: null,
  selectCounterparty: vi.fn(),
  computePFE: vi.fn(),
  computeCVA: vi.fn(),
  refresh: vi.fn(),
}

describe('CounterpartyRiskDashboard', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    mockUseCounterpartyRisk.mockReturnValue(defaultHook)
  })

  it('renders the dashboard container', () => {
    render(<CounterpartyRiskDashboard />)
    expect(screen.getByTestId('counterparty-risk-dashboard')).toBeInTheDocument()
  })

  it('shows empty state when there are no exposures', () => {
    render(<CounterpartyRiskDashboard />)
    expect(screen.getByTestId('counterparty-empty-state')).toBeInTheDocument()
  })

  it('renders counterparty rows when exposures are loaded', () => {
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: [SAMPLE_EXPOSURE, HIGH_EXPOSURE],
    })

    render(<CounterpartyRiskDashboard />)

    expect(screen.getByTestId('counterparty-row-CP-GS')).toBeInTheDocument()
    expect(screen.getByTestId('counterparty-row-CP-JPM')).toBeInTheDocument()
  })

  it('flags top-decile exposures (percentile threshold) when the universe is large enough', () => {
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: buildFleet(),
    })

    render(<CounterpartyRiskDashboard />)

    // 12 CPs, 90th percentile lands at index floor(12*0.9)=10, value = 6M.
    // CP-BIG-2 (6M) and CP-BIG-3 (8M) are flagged; CP-BIG-1 (5M) is not.
    expect(screen.getByTestId('wwf-badge-CP-BIG-2')).toBeInTheDocument()
    expect(screen.getByTestId('wwf-badge-CP-BIG-3')).toBeInTheDocument()
    expect(screen.queryByTestId('wwf-badge-CP-BIG-1')).not.toBeInTheDocument()
    expect(screen.queryByTestId('wwf-badge-CP-SMALL-0')).not.toBeInTheDocument()
  })

  it('shows the agreement-expired pill when agreementStatus is EXPIRED', () => {
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: [{ ...SAMPLE_EXPOSURE, agreementStatus: 'EXPIRED' }],
    })

    render(<CounterpartyRiskDashboard />)

    expect(screen.getByTestId('agreement-expired-pill-CP-GS')).toHaveTextContent('Agreement Expired')
  })

  it('does not show the agreement-expired pill when agreementStatus is ACTIVE or absent', () => {
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: [
        { ...SAMPLE_EXPOSURE, counterpartyId: 'CP-A', agreementStatus: 'ACTIVE' },
        { ...SAMPLE_EXPOSURE, counterpartyId: 'CP-B' },
      ],
    })

    render(<CounterpartyRiskDashboard />)

    expect(screen.queryByTestId('agreement-expired-pill-CP-A')).not.toBeInTheDocument()
    expect(screen.queryByTestId('agreement-expired-pill-CP-B')).not.toBeInTheDocument()
  })

  it('does not flag any counterparty when the universe is too small for a percentile', () => {
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: [HIGH_EXPOSURE],
    })

    render(<CounterpartyRiskDashboard />)

    // Below 10 counterparties the percentile threshold disengages.
    expect(screen.queryByTestId('wwf-flag-CP-JPM')).not.toBeInTheDocument()
    expect(screen.queryByTestId('wwf-badge-CP-JPM')).not.toBeInTheDocument()
  })

  it('filters rows when the user types a search query', () => {
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: buildFleet(),
    })

    render(<CounterpartyRiskDashboard />)

    const search = screen.getByTestId('counterparty-search')
    fireEvent.change(search, { target: { value: 'BIG' } })

    expect(screen.getByTestId('counterparty-row-CP-BIG-1')).toBeInTheDocument()
    expect(screen.getByTestId('counterparty-row-CP-BIG-2')).toBeInTheDocument()
    expect(screen.queryByTestId('counterparty-row-CP-SMALL-0')).not.toBeInTheDocument()
  })

  it('shows a no-results message when the search query matches nothing', () => {
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: buildFleet(),
    })

    render(<CounterpartyRiskDashboard />)

    fireEvent.change(screen.getByTestId('counterparty-search'), { target: { value: 'XYZ' } })

    expect(screen.getByTestId('counterparty-no-search-results')).toBeInTheDocument()
  })

  it('default sort is by net exposure descending', () => {
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: buildFleet(),
    })

    render(<CounterpartyRiskDashboard />)

    const rows = screen.getAllByTestId(/^counterparty-row-/)
    expect(rows[0]).toHaveAttribute('data-testid', 'counterparty-row-CP-BIG-3')
    expect(rows[1]).toHaveAttribute('data-testid', 'counterparty-row-CP-BIG-2')
    expect(rows[2]).toHaveAttribute('data-testid', 'counterparty-row-CP-BIG-1')
  })

  it('clicking a column header toggles the sort direction', () => {
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: buildFleet(),
    })

    render(<CounterpartyRiskDashboard />)

    // Click net exposure header — already active desc — should flip to asc.
    fireEvent.click(screen.getByTestId('sort-header-currentNetExposure'))

    const rows = screen.getAllByTestId(/^counterparty-row-/)
    expect(rows[0]).toHaveAttribute('data-testid', 'counterparty-row-CP-SMALL-0')
  })

  it('clicking a different column switches sort to that column', () => {
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: buildFleet(),
    })

    render(<CounterpartyRiskDashboard />)

    fireEvent.click(screen.getByTestId('sort-header-counterpartyId'))

    const rows = screen.getAllByTestId(/^counterparty-row-/)
    // CP-BIG-1 is the alphabetically smallest among 'CP-BIG-*' / 'CP-SMALL-*'.
    expect(rows[0]).toHaveAttribute('data-testid', 'counterparty-row-CP-BIG-1')
  })

  it('calls selectCounterparty when a row is clicked', async () => {
    const selectCounterparty = vi.fn()
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: [SAMPLE_EXPOSURE],
      selectCounterparty,
    })

    render(<CounterpartyRiskDashboard />)

    fireEvent.click(screen.getByTestId('counterparty-row-CP-GS'))

    await waitFor(() => {
      expect(selectCounterparty).toHaveBeenCalledWith('CP-GS')
    })
  })

  describe('cross-tab jump to filtered Trades', () => {
    it('renders a "View trades" link on each row when onJumpToTrades is provided', () => {
      mockUseCounterpartyRisk.mockReturnValue({
        ...defaultHook,
        exposures: [SAMPLE_EXPOSURE],
      })

      render(<CounterpartyRiskDashboard onJumpToTrades={() => {}} />)

      expect(screen.getByTestId('jump-to-trades-CP-GS')).toBeInTheDocument()
    })

    it('does not render the "View trades" link when onJumpToTrades is omitted', () => {
      mockUseCounterpartyRisk.mockReturnValue({
        ...defaultHook,
        exposures: [SAMPLE_EXPOSURE],
      })

      render(<CounterpartyRiskDashboard />)

      expect(screen.queryByTestId('jump-to-trades-CP-GS')).not.toBeInTheDocument()
    })

    it('clicking "View trades" calls onJumpToTrades with the counterparty id', () => {
      const onJumpToTrades = vi.fn()
      const selectCounterparty = vi.fn()
      mockUseCounterpartyRisk.mockReturnValue({
        ...defaultHook,
        exposures: [SAMPLE_EXPOSURE],
        selectCounterparty,
      })

      render(<CounterpartyRiskDashboard onJumpToTrades={onJumpToTrades} />)

      fireEvent.click(screen.getByTestId('jump-to-trades-CP-GS'))

      expect(onJumpToTrades).toHaveBeenCalledWith('CP-GS')
      // The row-click select should NOT fire — the jump button is a separate
      // action and must stop propagation.
      expect(selectCounterparty).not.toHaveBeenCalled()
    })
  })

  it('shows detail panel with metrics when counterparty is selected', () => {
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: [SAMPLE_EXPOSURE],
      selected: SAMPLE_EXPOSURE,
    })

    render(<CounterpartyRiskDashboard />)

    expect(screen.getByTestId('counterparty-detail-panel')).toBeInTheDocument()
    expect(screen.getByTestId('detail-net-exposure')).toBeInTheDocument()
    expect(screen.getByTestId('detail-peak-pfe')).toBeInTheDocument()
    expect(screen.getByTestId('detail-cva')).toBeInTheDocument()
  })

  it('shows PFE chart when profile is available', () => {
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: [SAMPLE_EXPOSURE],
      selected: SAMPLE_EXPOSURE,
    })

    render(<CounterpartyRiskDashboard />)

    expect(screen.getByTestId('pfe-chart')).toBeInTheDocument()
  })

  it('shows empty PFE chart message when no profile', () => {
    const noProfile = { ...SAMPLE_EXPOSURE, pfeProfile: [] }
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: [noProfile],
      selected: noProfile,
    })

    render(<CounterpartyRiskDashboard />)

    expect(screen.getByTestId('pfe-chart-empty')).toBeInTheDocument()
  })

  it('does not render NaN coordinates when a pfeProfile row is missing expectedExposure', () => {
    // Simulates a malformed API response where one tenor row is missing numeric
    // fields. Before the fix, the chart rendered polyline points containing "NaN"
    // (e.g. "64,NaN") which crashes real SVG renderers with:
    //   Cannot read properties of undefined (reading 'toFixed')
    // After the fix, bad rows are filtered out and no NaN appears in the SVG.
    const malformedProfile = {
      ...SAMPLE_EXPOSURE,
      pfeProfile: [
        { tenor: '1Y', tenorYears: 1, expectedExposure: 1_500_000, pfe95: 1_800_000, pfe99: 2_000_000 },
        // Missing expectedExposure — simulates what the API can return when a
        // tenor entry is partially populated.
        { tenor: '2Y', tenorYears: 2, expectedExposure: undefined as unknown as number, pfe95: 1_500_000, pfe99: 1_700_000 },
      ],
    }

    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: [malformedProfile],
      selected: malformedProfile,
    })

    const { container } = render(<CounterpartyRiskDashboard />)

    // The SVG must not contain any NaN coordinate — that indicates the bad row
    // was not filtered and would crash a real browser SVG renderer.
    const svg = container.querySelector('[data-testid="pfe-chart"]')
    const empty = container.querySelector('[data-testid="pfe-chart-empty"]')

    if (svg !== null) {
      // Chart is rendered — ensure no polyline has NaN in its points attribute
      const polylines = svg.querySelectorAll('polyline')
      polylines.forEach((polyline) => {
        const points = polyline.getAttribute('points') ?? ''
        expect(points).not.toContain('NaN')
      })
    } else {
      // Empty state is acceptable when all rows are bad
      expect(empty).not.toBeNull()
    }
  })

  it('calls computePFE when Compute PFE button is clicked', async () => {
    const computePFE = vi.fn().mockResolvedValue(undefined)
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: [SAMPLE_EXPOSURE],
      selected: SAMPLE_EXPOSURE,
      computePFE,
    })

    render(<CounterpartyRiskDashboard />)

    fireEvent.click(screen.getByTestId('compute-pfe-button'))

    await waitFor(() => {
      expect(computePFE).toHaveBeenCalledWith('CP-GS')
    })
  })

  it('calls computeCVA when Compute CVA button is clicked', async () => {
    const computeCVA = vi.fn().mockResolvedValue(undefined)
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: [SAMPLE_EXPOSURE],
      selected: SAMPLE_EXPOSURE,
      computeCVA,
    })

    render(<CounterpartyRiskDashboard />)

    fireEvent.click(screen.getByTestId('compute-cva-button'))

    await waitFor(() => {
      expect(computeCVA).toHaveBeenCalledWith('CP-GS')
    })
  })

  it('shows error message when error is set', () => {
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      error: 'Failed to load exposures',
    })

    render(<CounterpartyRiskDashboard />)

    expect(screen.getByTestId('counterparty-error')).toHaveTextContent('Failed to load exposures')
  })

  it('calls refresh when Refresh button is clicked', () => {
    const refresh = vi.fn()
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: [SAMPLE_EXPOSURE],
      refresh,
    })

    render(<CounterpartyRiskDashboard />)

    fireEvent.click(screen.getByTestId('refresh-exposures-button'))

    expect(refresh).toHaveBeenCalled()
  })

  it('shows placeholder when no counterparty is selected', () => {
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: [SAMPLE_EXPOSURE],
    })

    render(<CounterpartyRiskDashboard />)

    expect(screen.getByTestId('detail-panel-placeholder')).toBeInTheDocument()
  })

  it('disables CVA button when no PFE profile is available', () => {
    const noProfile = { ...SAMPLE_EXPOSURE, pfeProfile: [] }
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: [noProfile],
      selected: noProfile,
    })

    render(<CounterpartyRiskDashboard />)

    const cvaButton = screen.getByTestId('compute-cva-button')
    expect(cvaButton).toBeDisabled()
  })

  // Light-mode support: every bare slate utility class on a rendered element
  // must be paired with a `dark:` counterpart of the same family on the same
  // element. The reverse — `dark:bg-slate-800` must have a same-element
  // light-mode counterpart of the same family (any colour, e.g. `bg-white`)
  // so the element is not transparent in light mode. A bare `bg-slate-800` /
  // `text-slate-200` / `border-slate-700` with no `dark:` companion is the
  // regression we are guarding against.
  it('pairs every slate utility class with a light-mode and dark-mode counterpart', () => {
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: [SAMPLE_EXPOSURE, HIGH_EXPOSURE],
      selected: SAMPLE_EXPOSURE,
    })

    const { container } = render(<CounterpartyRiskDashboard />)

    type Offender = { reason: string; family: string; classAttr: string }
    const offenders: Offender[] = []

    container.querySelectorAll<HTMLElement>('[class]').forEach((node) => {
      const classAttr = node.getAttribute('class') ?? ''
      const tokens = classAttr.split(/\s+/).filter(Boolean)

      // For each utility "family" — keyed by the non-`dark:` modifier chain
      // plus the utility prefix (`bg` / `text` / `border`) — collect the
      // bare ("light") and `dark:`-prefixed tokens that target it. A family
      // is healthy only when both buckets are populated.
      //
      // Example: `dark:hover:bg` is a family. Tokens belonging to it are
      // `hover:bg-…` (light) and `dark:hover:bg-…` (dark).
      const families: Record<string, { light: string[]; dark: string[]; slateLight: boolean; slateDark: boolean }> = {}
      for (const token of tokens) {
        const match = token.match(/^((?:[\w-]+:)*)((?:bg|text|border|placeholder)-[\w/-]+)$/)
        if (!match) continue
        const variants = match[1] // e.g. "dark:hover:" or "hover:"
        const utility = match[2] // e.g. "bg-slate-800" / "bg-white"
        const utilityKind = utility.split('-')[0] // bg | text | border | placeholder
        const isDark = /\bdark:/.test(variants)
        const familyKey = `${variants.replace(/(^|:)dark:/g, '$1')}${utilityKind}`
        const bucket = (families[familyKey] ??= { light: [], dark: [], slateLight: false, slateDark: false })
        const isSlate = /^(?:bg|text|border|placeholder)-slate-/.test(utility)
        if (isDark) {
          bucket.dark.push(token)
          if (isSlate) bucket.slateDark = true
        } else {
          bucket.light.push(token)
          if (isSlate) bucket.slateLight = true
        }
      }

      for (const [family, { light, dark, slateLight, slateDark }] of Object.entries(families)) {
        // We only care about families that involve a slate utility — those
        // are the ones that risk hardcoding the dark palette.
        if (!slateLight && !slateDark) continue
        if (dark.length === 0) {
          offenders.push({
            reason: 'bare slate utility without a `dark:` counterpart',
            family,
            classAttr,
          })
        }
        if (light.length === 0) {
          offenders.push({
            reason: '`dark:` slate utility without a light-mode counterpart',
            family,
            classAttr,
          })
        }
      }
    })

    expect(offenders).toEqual([])
  })

  it('renders the "Counterparty Risk" heading via the canonical SectionHeading primitive', () => {
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: [SAMPLE_EXPOSURE],
    })

    render(<CounterpartyRiskDashboard />)

    const heading = screen.getByRole('heading', { name: /counterparty risk/i })
    expect(heading.className).toContain('text-base')
    expect(heading.className).toContain('font-semibold')
  })

  // Plan §5.4 — Reserve colour for semantic, not decorative.
  // The Peak PFE and CVA columns historically used amber / indigo tints as a
  // purely decorative "this is a different column" cue. Severity, sign and
  // status earn colour; mere column identity does not. Decorative grouping
  // should use weight / borders / spacing instead.
  describe('decorative column colour removal (plan §5.4)', () => {
    it('does not tint the Peak PFE cell amber', () => {
      mockUseCounterpartyRisk.mockReturnValue({
        ...defaultHook,
        exposures: [SAMPLE_EXPOSURE],
      })

      const { container } = render(<CounterpartyRiskDashboard />)

      const row = container.querySelector('[data-testid="counterparty-row-CP-GS"]')!
      const cells = row.querySelectorAll('td')
      // 5 columns: counterparty, net exposure, peak pfe, cva, wwr.
      const peakPfeCell = cells[2]
      expect(peakPfeCell.className).not.toMatch(/\btext-amber-\d{2,3}\b/)
    })

    it('does not tint the CVA cell indigo', () => {
      mockUseCounterpartyRisk.mockReturnValue({
        ...defaultHook,
        exposures: [SAMPLE_EXPOSURE],
      })

      const { container } = render(<CounterpartyRiskDashboard />)

      const row = container.querySelector('[data-testid="counterparty-row-CP-GS"]')!
      const cells = row.querySelectorAll('td')
      const cvaCell = cells[3]
      // The whole CVA cell — including any inner spans — must be free of
      // decorative indigo tinting. (The italic "estimated" slate variant is
      // semantic — it signals the value is an approximation — and stays.)
      expect(cvaCell.innerHTML).not.toMatch(/text-indigo-\d{2,3}/)
    })

    it('does not tint the detail Peak PFE metric amber', () => {
      mockUseCounterpartyRisk.mockReturnValue({
        ...defaultHook,
        exposures: [SAMPLE_EXPOSURE],
        selected: SAMPLE_EXPOSURE,
      })

      render(<CounterpartyRiskDashboard />)

      const peakPfe = screen.getByTestId('detail-peak-pfe')
      expect(peakPfe.className).not.toMatch(/\btext-amber-\d{2,3}\b/)
    })

    it('does not tint the detail CVA metric indigo', () => {
      mockUseCounterpartyRisk.mockReturnValue({
        ...defaultHook,
        exposures: [SAMPLE_EXPOSURE],
        selected: SAMPLE_EXPOSURE,
      })

      render(<CounterpartyRiskDashboard />)

      const cva = screen.getByTestId('detail-cva')
      expect(cva.className).not.toMatch(/\btext-indigo-\d{2,3}\b/)
    })

    it('separates the Peak PFE and CVA column groups with a left border (decorative grouping via border, not colour)', () => {
      mockUseCounterpartyRisk.mockReturnValue({
        ...defaultHook,
        exposures: [SAMPLE_EXPOSURE],
      })

      const { container } = render(<CounterpartyRiskDashboard />)

      // Both the header cell and the data cell of the Peak PFE column should
      // carry a left border to visually group the credit-risk columns
      // (Peak PFE + CVA) apart from the exposure column to its left.
      const peakPfeHeader = container.querySelector('[data-testid="sort-header-peakPfe"]')!
      expect(peakPfeHeader.className).toMatch(/\bborder-l\b/)

      const row = container.querySelector('[data-testid="counterparty-row-CP-GS"]')!
      const peakPfeCell = row.querySelectorAll('td')[2]
      expect(peakPfeCell.className).toMatch(/\bborder-l\b/)
    })

    it('keeps the WWR amber flag and badge (semantic — severity)', () => {
      mockUseCounterpartyRisk.mockReturnValue({
        ...defaultHook,
        exposures: buildFleet(),
      })

      render(<CounterpartyRiskDashboard />)

      // Top-decile counterparty — should still carry the amber severity flag
      // and badge. Amber here means "high exposure", not "this is the WWR
      // column", so it stays.
      const flag = screen.getByTestId('wwf-flag-CP-BIG-3')
      // The flag is a lucide-react SVG; className is an SVGAnimatedString, so
      // read the raw attribute instead.
      expect(flag.getAttribute('class') ?? '').toMatch(/\btext-amber-\d{2,3}\b/)
      const badge = screen.getByTestId('wwf-badge-CP-BIG-3')
      expect(badge.className).toMatch(/\btext-amber-\d{2,3}\b/)
    })

    it('keeps the error banner red (semantic — error)', () => {
      mockUseCounterpartyRisk.mockReturnValue({
        ...defaultHook,
        error: 'boom',
      })

      render(<CounterpartyRiskDashboard />)

      const errorBanner = screen.getByTestId('counterparty-error')
      expect(errorBanner.className).toMatch(/\bbg-red-\d{2,3}\b/)
      expect(errorBanner.className).toMatch(/\btext-red-\d{2,3}\b/)
    })

    it('keeps the agreement-expired pill red (semantic — status)', () => {
      mockUseCounterpartyRisk.mockReturnValue({
        ...defaultHook,
        exposures: [{ ...SAMPLE_EXPOSURE, agreementStatus: 'EXPIRED' }],
      })

      render(<CounterpartyRiskDashboard />)

      const pill = screen.getByTestId('agreement-expired-pill-CP-GS')
      expect(pill.className).toMatch(/\bbg-red-\d{2,3}/)
      expect(pill.className).toMatch(/\btext-red-\d{2,3}/)
    })
  })
})

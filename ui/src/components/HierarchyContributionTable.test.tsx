import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { HierarchyNodeRiskDto } from '../types'
import { HierarchyContributionTable } from './HierarchyContributionTable'

const firmNode: HierarchyNodeRiskDto = {
  level: 'FIRM',
  entityId: 'FIRM',
  entityName: 'FIRM',
  parentId: null,
  varValue: '2500000.00',
  expectedShortfall: '3125000.00',
  pnlToday: null,
  limitUtilisation: null,
  marginalVar: null,
  incrementalVar: null,
  topContributors: [
    { entityId: 'div-equities', entityName: 'Equities', varContribution: '1500000.00', pctOfTotal: '60.00' },
    { entityId: 'div-rates', entityName: 'Rates', varContribution: '1000000.00', pctOfTotal: '40.00' },
  ],
  childCount: 2,
  isPartial: false,
  missingBooks: [],
}

describe('HierarchyContributionTable', () => {
  it('renders contributor rows with entity name and VaR contribution', () => {
    render(<HierarchyContributionTable node={firmNode} />)

    expect(screen.getByTestId('hierarchy-contribution-table')).toBeInTheDocument()
    expect(screen.getByTestId('contributor-row-div-equities')).toBeInTheDocument()
    expect(screen.getByTestId('contributor-row-div-rates')).toBeInTheDocument()
    expect(screen.getByText('Equities')).toBeInTheDocument()
    expect(screen.getByText('Rates')).toBeInTheDocument()
  })

  it('shows percentage of total for each contributor', () => {
    render(<HierarchyContributionTable node={firmNode} />)

    expect(screen.getByText('60.0%')).toBeInTheDocument()
    expect(screen.getByText('40.0%')).toBeInTheDocument()
  })

  it('shows FIRM-level header with Division as child level', () => {
    render(<HierarchyContributionTable node={firmNode} />)

    expect(screen.getByText(/Top Division Contributors/)).toBeInTheDocument()
  })

  it('shows DESK-level header with Book as child level', () => {
    const deskNode: HierarchyNodeRiskDto = {
      ...firmNode,
      level: 'DESK',
      entityId: 'desk-rates',
      entityName: 'Rates Desk',
      parentId: 'div-rates',
    }
    render(<HierarchyContributionTable node={deskNode} />)

    expect(screen.getByText(/Top Book Contributors/)).toBeInTheDocument()
  })

  it('shows partial badge when isPartial is true', () => {
    const partialNode: HierarchyNodeRiskDto = {
      ...firmNode,
      isPartial: true,
      missingBooks: ['book-x', 'book-y'],
    }
    render(<HierarchyContributionTable node={partialNode} />)

    expect(screen.getByTestId('partial-badge')).toBeInTheDocument()
    expect(screen.getByTestId('missing-books-note')).toBeInTheDocument()
    expect(screen.getByText(/book-x/)).toBeInTheDocument()
  })

  it('does not show partial badge when isPartial is false', () => {
    render(<HierarchyContributionTable node={firmNode} />)

    expect(screen.queryByTestId('partial-badge')).not.toBeInTheDocument()
    expect(screen.queryByTestId('missing-books-note')).not.toBeInTheDocument()
  })

  it('calls onEntityClick with entity ID when row is clicked', async () => {
    const onEntityClick = vi.fn()
    render(<HierarchyContributionTable node={firmNode} onEntityClick={onEntityClick} />)

    await userEvent.click(screen.getByTestId('contributor-row-div-equities'))

    expect(onEntityClick).toHaveBeenCalledWith('div-equities')
  })

  it('returns null when no contributors and not partial', () => {
    const emptyNode: HierarchyNodeRiskDto = {
      ...firmNode,
      topContributors: [],
      isPartial: false,
    }
    const { container } = render(<HierarchyContributionTable node={emptyNode} />)

    expect(container.firstChild).toBeNull()
  })

  it('shows node-level marginalVar summary when populated', () => {
    const nodeWithMarginal: HierarchyNodeRiskDto = {
      ...firmNode,
      marginalVar: '12500.50',
    }
    render(<HierarchyContributionTable node={nodeWithMarginal} />)

    expect(screen.getByTestId('marginal-var-summary')).toBeInTheDocument()
    expect(screen.getByText(/Marginal VaR/)).toBeInTheDocument()
  })

  it('shows node-level incrementalVar summary when populated', () => {
    const nodeWithIncremental: HierarchyNodeRiskDto = {
      ...firmNode,
      incrementalVar: '8750.00',
    }
    render(<HierarchyContributionTable node={nodeWithIncremental} />)

    expect(screen.getByTestId('incremental-var-summary')).toBeInTheDocument()
    expect(screen.getByText(/Incremental VaR/)).toBeInTheDocument()
  })

  it('does not show marginalVar summary when marginalVar is null', () => {
    render(<HierarchyContributionTable node={firmNode} />)

    expect(screen.queryByTestId('marginal-var-summary')).not.toBeInTheDocument()
  })

  it('renders an em-dash for a zero marginal/incremental VaR alongside non-zero contributions', () => {
    // UX review: "Marginal VaR: $0.00 · Incremental VaR: $0.00" under a table
    // of nine-figure contributions reads as a contradiction — a hard zero next
    // to real contributions means "not computed", not "zero risk".
    const node: HierarchyNodeRiskDto = {
      ...firmNode,
      marginalVar: '0.00',
      incrementalVar: '0',
    }
    render(<HierarchyContributionTable node={node} />)

    expect(screen.getByTestId('marginal-var-summary')).toHaveTextContent('—')
    expect(screen.getByTestId('marginal-var-summary')).not.toHaveTextContent('$0.00')
    expect(screen.getByTestId('incremental-var-summary')).toHaveTextContent('—')
  })

  it('does not show incrementalVar summary when incrementalVar is null', () => {
    render(<HierarchyContributionTable node={firmNode} />)

    expect(screen.queryByTestId('incremental-var-summary')).not.toBeInTheDocument()
  })
})

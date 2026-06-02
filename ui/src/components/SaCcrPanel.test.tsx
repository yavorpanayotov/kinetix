import { describe, it, expect } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { SaCcrPanel } from './SaCcrPanel'
import type { SaCcrSummaryDto } from '../types'

const NETTING_SET_GS: SaCcrSummaryDto['nettingSets'][number] = {
  nettingSetId: 'ISDA-GS-2019',
  counterpartyId: 'CP-GS',
  replacementCost: 2100000,
  pfeAddon: 1013000,
  multiplier: 0.995,
  ead: 4218000,
  alpha: 1.4,
}

const NETTING_SET_UNASSIGNED: SaCcrSummaryDto['nettingSets'][number] = {
  nettingSetId: 'CP-GS-UNASSIGNED',
  counterpartyId: 'CP-GS',
  replacementCost: 500000,
  pfeAddon: 250000,
  multiplier: 1.0,
  ead: 1050000,
  alpha: 1.4,
}

const TEST_RESULT: SaCcrSummaryDto = {
  counterpartyId: 'CP-GS',
  totalEad: 5268000,
  nettingSets: [NETTING_SET_GS, NETTING_SET_UNASSIGNED],
}

describe('SaCcrPanel', () => {
  it('renders loading state', () => {
    render(<SaCcrPanel result={null} loading={true} error={null} />)
    expect(screen.getByTestId('sa-ccr-loading')).toBeVisible()
  })

  it('renders error state', () => {
    render(<SaCcrPanel result={null} loading={false} error="Counterparty not found" />)
    expect(screen.getByTestId('sa-ccr-error')).toHaveTextContent('Counterparty not found')
  })

  it('renders empty state when no result', () => {
    render(<SaCcrPanel result={null} loading={false} error={null} />)
    expect(screen.getByTestId('sa-ccr-empty')).toBeVisible()
  })

  it('renders empty state when the counterparty has no netting sets', () => {
    render(
      <SaCcrPanel
        result={{ counterpartyId: 'CP-GS', totalEad: 0, nettingSets: [] }}
        loading={false}
        error={null}
      />,
    )
    expect(screen.getByTestId('sa-ccr-empty')).toBeVisible()
  })

  it('renders the total EAD across netting sets', () => {
    render(<SaCcrPanel result={TEST_RESULT} loading={false} error={null} />)
    expect(screen.getByTestId('sa-ccr-panel')).toBeVisible()
    expect(screen.getByTestId('sa-ccr-total-ead')).toHaveTextContent('$5,268,000')
  })

  it('renders one row per netting set with RC, PFE add-on, multiplier and EAD', () => {
    render(<SaCcrPanel result={TEST_RESULT} loading={false} error={null} />)
    const gsRow = screen.getByTestId('sa-ccr-netting-set-ISDA-GS-2019')
    expect(gsRow).toBeVisible()
    expect(within(gsRow).getByText('ISDA-GS-2019')).toBeVisible()
    expect(within(gsRow).getByTestId('sa-ccr-multiplier-ISDA-GS-2019')).toHaveTextContent('0.9950')
    expect(screen.getByTestId('sa-ccr-netting-set-CP-GS-UNASSIGNED')).toBeVisible()
  })

  it('displays regulatory label clearly', () => {
    render(<SaCcrPanel result={TEST_RESULT} loading={false} error={null} />)
    expect(screen.getByText(/Regulatory Capital.*SA-CCR.*BCBS 279/)).toBeVisible()
    expect(screen.getByText(/Distinct from internal Monte Carlo PFE/)).toBeVisible()
  })

  it('labels PFE as SA-CCR PFE Add-on to avoid conflation', () => {
    render(<SaCcrPanel result={TEST_RESULT} loading={false} error={null} />)
    expect(screen.getAllByText('SA-CCR PFE Add-on').length).toBeGreaterThan(0)
  })

  it('does not crash when a netting set is missing numeric fields', () => {
    // Defensive: a partially-populated upstream response must not throw
    // "Cannot read properties of undefined (reading 'toFixed')".
    const partial = {
      counterpartyId: 'CP-GS',
      totalEad: 4218000,
      nettingSets: [
        {
          nettingSetId: 'ISDA-GS-2019',
          counterpartyId: 'CP-GS',
          ead: 4218000,
          alpha: 1.4,
        } as SaCcrSummaryDto['nettingSets'][number],
      ],
    }
    render(<SaCcrPanel result={partial} loading={false} error={null} />)
    expect(screen.getByTestId('sa-ccr-netting-set-ISDA-GS-2019')).toBeVisible()
    expect(within(screen.getByTestId('sa-ccr-multiplier-ISDA-GS-2019'))).toBeDefined()
    expect(screen.getByTestId('sa-ccr-multiplier-ISDA-GS-2019')).toHaveTextContent('—')
  })
})

import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ScenarioIndicator } from './ScenarioIndicator'

describe('ScenarioIndicator', () => {
  it('renders nothing when scenario is null and not loading', () => {
    render(<ScenarioIndicator scenario={null} loading={false} />)
    expect(screen.queryByTestId('scenario-indicator')).not.toBeInTheDocument()
    expect(screen.queryByTestId('scenario-indicator-loading')).not.toBeInTheDocument()
  })

  it('renders the loading placeholder while scenario is null and loading=true', () => {
    render(<ScenarioIndicator scenario={null} loading={true} />)
    expect(screen.getByTestId('scenario-indicator-loading')).toBeInTheDocument()
  })

  it('renders the active scenario label for known scenarios', () => {
    render(<ScenarioIndicator scenario="multi-asset" loading={false} />)
    const indicator = screen.getByTestId('scenario-indicator')
    expect(indicator).toHaveTextContent('Multi-Asset')
    expect(indicator).toHaveAttribute('data-scenario', 'multi-asset')
    expect(indicator).toHaveAttribute('aria-label', 'Active scenario: Multi-Asset')
  })

  it('renders mapped labels for each known scenario', () => {
    const cases: Array<[string, string]> = [
      ['equity-ls', 'Equity L/S'],
      ['options-book', 'Options Book'],
      ['stress', 'Stress'],
      ['regulatory', 'Regulatory'],
    ]
    for (const [scenario, label] of cases) {
      const { unmount } = render(<ScenarioIndicator scenario={scenario} loading={false} />)
      expect(screen.getByTestId('scenario-indicator')).toHaveTextContent(label)
      unmount()
    }
  })

  it('falls back to the raw key for unknown scenarios', () => {
    render(<ScenarioIndicator scenario="custom-future-flag" loading={false} />)
    expect(screen.getByTestId('scenario-indicator')).toHaveTextContent('custom-future-flag')
  })
})

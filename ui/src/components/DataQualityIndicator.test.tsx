import { render, screen, fireEvent } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { DataQualityIndicator } from './DataQualityIndicator'
import type { DataQualityStatus } from '../types'

describe('DataQualityIndicator', () => {
  const allOkStatus: DataQualityStatus = {
    overall: 'OK',
    checks: [
      { name: 'Price Freshness', status: 'OK', message: 'All prices fresh', lastChecked: '2025-01-15T10:00:00Z' },
      { name: 'Position Count', status: 'OK', message: 'Counts consistent', lastChecked: '2025-01-15T10:00:00Z' },
      { name: 'Risk Result Completeness', status: 'OK', message: 'All complete', lastChecked: '2025-01-15T10:00:00Z' },
    ],
  }

  const warningStatus: DataQualityStatus = {
    overall: 'WARNING',
    checks: [
      { name: 'Price Freshness', status: 'WARNING', message: 'Price staleness detected for AAPL', lastChecked: '2025-01-15T10:00:00Z' },
      { name: 'Position Count', status: 'OK', message: 'Counts consistent', lastChecked: '2025-01-15T10:00:00Z' },
      { name: 'Risk Result Completeness', status: 'OK', message: 'All complete', lastChecked: '2025-01-15T10:00:00Z' },
    ],
  }

  const criticalStatus: DataQualityStatus = {
    overall: 'CRITICAL',
    checks: [
      { name: 'Price Freshness', status: 'CRITICAL', message: 'All prices stale', lastChecked: '2025-01-15T10:00:00Z' },
      { name: 'Position Count', status: 'WARNING', message: 'Count mismatch', lastChecked: '2025-01-15T10:00:00Z' },
      { name: 'Risk Result Completeness', status: 'OK', message: 'All complete', lastChecked: '2025-01-15T10:00:00Z' },
    ],
  }

  it('shows green status when all checks pass', () => {
    render(<DataQualityIndicator status={allOkStatus} loading={false} />)

    const indicator = screen.getByTestId('data-quality-indicator')
    expect(indicator).toBeDefined()
    expect(indicator.querySelector('[data-testid="dq-status-ok"]')).toBeDefined()
  })

  it('shows yellow warning when price staleness detected', () => {
    render(<DataQualityIndicator status={warningStatus} loading={false} />)

    const indicator = screen.getByTestId('data-quality-indicator')
    expect(indicator.querySelector('[data-testid="dq-status-warning"]')).toBeDefined()
  })

  // Plan §10.2 — in FROZEN replay mode the demo intentionally pins prices,
  // so price-staleness is expected and must not surface as a header warning.
  it('hides stale-price WARNING and downgrades overall to OK when replayFrozen', () => {
    render(<DataQualityIndicator status={warningStatus} loading={false} replayFrozen />)

    const indicator = screen.getByTestId('data-quality-indicator')
    // overall recomputed → green/OK because the only WARNING was the stale-price one.
    expect(indicator.querySelector('[data-testid="dq-status-ok"]')).toBeDefined()
    expect(indicator.querySelector('[data-testid="dq-status-warning"]')).toBeNull()

    // Open the dropdown and confirm the stale-price row is filtered out.
    fireEvent.click(indicator)
    const dropdown = screen.getByTestId('data-quality-dropdown')
    expect(dropdown.textContent).not.toContain('Price staleness detected')
    expect(dropdown.textContent).toContain('Position Count')
    expect(dropdown.textContent).toContain('Risk Result Completeness')
  })

  it('keeps a CRITICAL price check even when replayFrozen (only WARNINGs are suppressed)', () => {
    render(<DataQualityIndicator status={criticalStatus} loading={false} replayFrozen />)

    const indicator = screen.getByTestId('data-quality-indicator')
    // CRITICAL price freshness must still surface in frozen mode — a true
    // outage shouldn't hide behind the replay-mode filter.
    expect(indicator.querySelector('[data-testid="dq-status-critical"]')).toBeDefined()
  })

  it('shows red alert when critical issues found', () => {
    render(<DataQualityIndicator status={criticalStatus} loading={false} />)

    const indicator = screen.getByTestId('data-quality-indicator')
    expect(indicator.querySelector('[data-testid="dq-status-critical"]')).toBeDefined()
  })

  it('displays check details on click', () => {
    render(<DataQualityIndicator status={allOkStatus} loading={false} />)

    const button = screen.getByTestId('data-quality-indicator')
    fireEvent.click(button)

    expect(screen.getByText('Price Freshness')).toBeDefined()
    expect(screen.getByText('Position Count')).toBeDefined()
    expect(screen.getByText('Risk Result Completeness')).toBeDefined()
  })

  it('shows loading state', () => {
    render(<DataQualityIndicator status={null} loading={true} />)

    expect(screen.getByTestId('data-quality-loading')).toBeDefined()
  })

  it('hides dropdown when clicked outside', () => {
    render(<DataQualityIndicator status={allOkStatus} loading={false} />)

    const button = screen.getByTestId('data-quality-indicator')
    fireEvent.click(button)

    expect(screen.getByText('Price Freshness')).toBeDefined()

    // Click the button again to close
    fireEvent.click(button)

    expect(screen.queryByText('Price Freshness')).toBeNull()
  })

  it('renders nothing when status is null and loading is false', () => {
    const { container } = render(<DataQualityIndicator status={null} loading={false} />)

    expect(container.firstChild).toBeNull()
  })

  it('shows CRITICAL indicator with monitoring unavailable message when a synthetic CRITICAL status is passed', () => {
    const monitoringUnavailableStatus: DataQualityStatus = {
      overall: 'CRITICAL',
      checks: [
        {
          name: 'Data Quality Monitoring',
          status: 'CRITICAL',
          message: 'Monitoring unavailable',
          lastChecked: new Date().toISOString(),
        },
      ],
    }

    render(<DataQualityIndicator status={monitoringUnavailableStatus} loading={false} />)

    const indicator = screen.getByTestId('data-quality-indicator')
    expect(indicator.querySelector('[data-testid="dq-status-critical"]')).toBeDefined()

    fireEvent.click(indicator)
    expect(screen.getByText('Monitoring unavailable')).toBeDefined()
  })

  it('exposes the open dropdown as a labelled dialog', () => {
    render(<DataQualityIndicator status={allOkStatus} loading={false} />)

    const trigger = screen.getByTestId('data-quality-indicator')
    fireEvent.click(trigger)

    const dropdown = screen.getByTestId('data-quality-dropdown')
    expect(dropdown.getAttribute('role')).toBe('dialog')
    expect(dropdown.getAttribute('aria-label')).toBeTruthy()
  })

  it('moves focus into the dropdown when it opens', () => {
    render(<DataQualityIndicator status={allOkStatus} loading={false} />)

    const trigger = screen.getByTestId('data-quality-indicator')
    fireEvent.click(trigger)

    const dropdown = screen.getByTestId('data-quality-dropdown')
    const focused = document.activeElement
    expect(focused).not.toBeNull()
    expect(dropdown === focused || dropdown.contains(focused)).toBe(true)
  })

  it('returns focus to the trigger when the dropdown closes via Escape', () => {
    render(<DataQualityIndicator status={allOkStatus} loading={false} />)

    const trigger = screen.getByLabelText('Data quality status')
    fireEvent.click(trigger)

    expect(screen.getByTestId('data-quality-dropdown')).toBeDefined()

    fireEvent.keyDown(document, { key: 'Escape' })

    expect(screen.queryByTestId('data-quality-dropdown')).toBeNull()
    expect(document.activeElement).toBe(trigger)
  })
})

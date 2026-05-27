import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { AuditLog, type AuditLogEntry } from './AuditLog'

const sampleEntries: AuditLogEntry[] = [
  { id: '1', timestamp: '2026-05-27T11:50:00Z', eventType: 'TRADE_BOOKED', subject: 'AAPL' },
  { id: '2', timestamp: '2026-05-27T11:55:00Z', eventType: 'LIMIT_BREACH', subject: 'MSFT' },
]

describe('AuditLog', () => {
  it('renders each entry when the range returns rows', () => {
    render(<AuditLog entries={sampleEntries} from="2026-05-27" to="2026-05-27" />)

    expect(screen.getByText('TRADE_BOOKED')).toBeInTheDocument()
    expect(screen.getByText('LIMIT_BREACH')).toBeInTheDocument()
    expect(screen.getByText('AAPL')).toBeInTheDocument()
  })

  it('does not render the empty-state hint when entries are present', () => {
    render(<AuditLog entries={sampleEntries} from="2026-05-27" to="2026-05-27" />)

    expect(screen.queryByText(/no events in selected range/i)).toBeNull()
    expect(screen.queryByText(/widen date/i)).toBeNull()
  })

  it('renders the widen-range hint when the filter returns no rows', () => {
    render(<AuditLog entries={[]} from="2026-05-27" to="2026-05-27" />)

    expect(
      screen.getByText(/No events in selected range; widen date/i),
    ).toBeInTheDocument()
  })

  it('exposes the empty state as a status region for screen readers', () => {
    render(<AuditLog entries={[]} from="2026-05-27" to="2026-05-27" />)

    const status = screen.getByRole('status')
    expect(status).toHaveTextContent('No events in selected range; widen date')
  })

  it('still renders the table headers when empty so the column shape is obvious', () => {
    render(<AuditLog entries={[]} from="2026-05-27" to="2026-05-27" />)

    expect(screen.getByRole('columnheader', { name: 'When' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Event' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Subject' })).toBeInTheDocument()
  })

  it('echoes the active from/to range inside the empty-state hint', () => {
    render(<AuditLog entries={[]} from="2026-05-20" to="2026-05-21" />)

    const status = screen.getByRole('status')
    expect(status).toHaveTextContent('2026-05-20')
    expect(status).toHaveTextContent('2026-05-21')
  })
})

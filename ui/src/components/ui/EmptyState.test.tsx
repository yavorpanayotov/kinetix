import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { EmptyState } from './EmptyState'

describe('EmptyState', () => {
  it('renders the title', () => {
    render(<EmptyState title="Nothing here" />)

    expect(screen.getByText('Nothing here')).toBeInTheDocument()
  })

  it('renders an optional description', () => {
    render(<EmptyState title="Nothing here" description="Try selecting a book." />)

    expect(screen.getByText('Try selecting a book.')).toBeInTheDocument()
  })

  it('does not render description container when omitted', () => {
    const { container } = render(<EmptyState title="Nothing here" />)

    expect(container.querySelectorAll('p').length).toBe(0)
  })

  it('renders an optional icon node', () => {
    render(
      <EmptyState
        title="Nothing here"
        icon={<svg data-testid="empty-icon" />}
      />,
    )

    expect(screen.getByTestId('empty-icon')).toBeInTheDocument()
  })
})

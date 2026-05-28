import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { fireEvent } from '@testing-library/react'
import { UnauthenticatedLanding } from './UnauthenticatedLanding'

describe('UnauthenticatedLanding', () => {
  it('renders the product name "Kinetix"', () => {
    render(<UnauthenticatedLanding onLogin={() => {}} />)
    // Use the accessible heading lookup so the test reflects what a sighted
    // *and* assistive-tech user perceives: a top-level "Kinetix" heading.
    expect(
      screen.getByRole('heading', { name: /kinetix/i, level: 1 }),
    ).toBeInTheDocument()
  })

  it('renders a one-sentence product description', () => {
    render(<UnauthenticatedLanding onLogin={() => {}} />)
    expect(screen.getByTestId('landing-description')).toBeInTheDocument()
    // The copy is deliberately minimal — one sentence describing what the
    // platform is — so the assertion is just that the element is present and
    // non-empty.
    expect(screen.getByTestId('landing-description').textContent?.trim()).not.toBe('')
  })

  it('renders a "Log in" button with accessible role', () => {
    render(<UnauthenticatedLanding onLogin={() => {}} />)
    const button = screen.getByRole('button', { name: /log in/i })
    expect(button).toBeInTheDocument()
    expect(button).toHaveAttribute('data-testid', 'landing-login-button')
  })

  it('invokes onLogin when the login button is clicked', () => {
    const onLogin = vi.fn()
    render(<UnauthenticatedLanding onLogin={onLogin} />)
    fireEvent.click(screen.getByRole('button', { name: /log in/i }))
    expect(onLogin).toHaveBeenCalledTimes(1)
  })

  it('renders a link to /status for unauthenticated visitors to check system health', () => {
    render(<UnauthenticatedLanding onLogin={() => {}} />)
    const link = screen.getByRole('link', { name: /status/i })
    expect(link).toBeInTheDocument()
    expect(link).toHaveAttribute('href', '/status')
  })
})

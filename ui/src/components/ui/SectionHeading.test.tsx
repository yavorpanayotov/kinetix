import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { SectionHeading } from './SectionHeading'

describe('SectionHeading', () => {
  it('renders children as text', () => {
    render(<SectionHeading>Market Risk</SectionHeading>)

    expect(screen.getByText('Market Risk')).toBeInTheDocument()
  })

  it('defaults to an <h3> element', () => {
    render(<SectionHeading>Default heading</SectionHeading>)

    const heading = screen.getByRole('heading', { name: /default heading/i })
    expect(heading.tagName).toBe('H3')
  })

  it('renders as <h2> when as="h2"', () => {
    render(<SectionHeading as="h2">Section title</SectionHeading>)

    const heading = screen.getByRole('heading', { name: /section title/i })
    expect(heading.tagName).toBe('H2')
  })

  it('renders as <h4> when as="h4"', () => {
    render(<SectionHeading as="h4">Sub-section</SectionHeading>)

    const heading = screen.getByRole('heading', { name: /sub-section/i })
    expect(heading.tagName).toBe('H4')
  })

  it('applies the canonical text-base font-semibold classes', () => {
    render(<SectionHeading>Canonical</SectionHeading>)

    const heading = screen.getByRole('heading', { name: /canonical/i })
    expect(heading.className).toContain('text-base')
    expect(heading.className).toContain('font-semibold')
  })

  it('renders the right slot when supplied', () => {
    render(
      <SectionHeading right={<button data-testid="heading-action">Action</button>}>
        With actions
      </SectionHeading>,
    )

    expect(screen.getByTestId('heading-action')).toBeInTheDocument()
    expect(screen.getByText('With actions')).toBeInTheDocument()
  })

  it('does not render a right-slot container when no right prop is supplied', () => {
    render(<SectionHeading data-testid="heading-no-right">Bare</SectionHeading>)

    // No actions container should render when there is nothing on the right.
    expect(screen.queryByTestId('heading-no-right-actions')).not.toBeInTheDocument()
  })

  it('extends rather than replaces base classes when className is supplied', () => {
    render(<SectionHeading className="mb-4">Extended</SectionHeading>)

    const heading = screen.getByRole('heading', { name: /extended/i })
    // Caller-supplied class is present
    expect(heading.className).toContain('mb-4')
    // Canonical classes are still present
    expect(heading.className).toContain('text-base')
    expect(heading.className).toContain('font-semibold')
  })

  it('exposes a data-testid hook', () => {
    render(<SectionHeading data-testid="my-heading">Tagged</SectionHeading>)

    expect(screen.getByTestId('my-heading')).toBeInTheDocument()
  })
})

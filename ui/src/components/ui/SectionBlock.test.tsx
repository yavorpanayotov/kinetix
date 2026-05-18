import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { SectionBlock } from './SectionBlock'

describe('SectionBlock', () => {
  it('renders title and children', () => {
    render(
      <SectionBlock title="Market Risk">
        <div data-testid="child-content">child</div>
      </SectionBlock>,
    )

    expect(screen.getByText('Market Risk')).toBeInTheDocument()
    expect(screen.getByTestId('child-content')).toBeInTheDocument()
  })

  it('is open by default', () => {
    render(
      <SectionBlock title="Section A">
        <div data-testid="child">child</div>
      </SectionBlock>,
    )

    expect(screen.getByTestId('child')).toBeVisible()
    expect(screen.getByRole('button', { name: /section a/i })).toHaveAttribute(
      'aria-expanded',
      'true',
    )
  })

  it('respects defaultOpen=false and hides content', () => {
    render(
      <SectionBlock title="Section B" defaultOpen={false}>
        <div data-testid="child">child</div>
      </SectionBlock>,
    )

    expect(screen.queryByTestId('child')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /section b/i })).toHaveAttribute(
      'aria-expanded',
      'false',
    )
  })

  it('toggles open state when the header button is clicked (uncontrolled)', async () => {
    const user = userEvent.setup()
    render(
      <SectionBlock title="Section C">
        <div data-testid="child">child</div>
      </SectionBlock>,
    )

    const toggle = screen.getByRole('button', { name: /section c/i })
    expect(toggle).toHaveAttribute('aria-expanded', 'true')

    await user.click(toggle)

    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByTestId('child')).not.toBeInTheDocument()

    await user.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByTestId('child')).toBeVisible()
  })

  it('honours controlled open prop and fires onToggle without mutating local state', async () => {
    const user = userEvent.setup()
    const onToggle = vi.fn()

    const { rerender } = render(
      <SectionBlock title="Controlled" open={true} onToggle={onToggle}>
        <div data-testid="child">child</div>
      </SectionBlock>,
    )

    const toggle = screen.getByRole('button', { name: /controlled/i })
    expect(toggle).toHaveAttribute('aria-expanded', 'true')

    await user.click(toggle)

    expect(onToggle).toHaveBeenCalledWith(false)
    // Open prop unchanged, so still expanded
    expect(toggle).toHaveAttribute('aria-expanded', 'true')

    rerender(
      <SectionBlock title="Controlled" open={false} onToggle={onToggle}>
        <div data-testid="child">child</div>
      </SectionBlock>,
    )

    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByTestId('child')).not.toBeInTheDocument()
  })

  it('renders the right slot when provided', () => {
    render(
      <SectionBlock title="With actions" right={<button data-testid="action-btn">Action</button>}>
        <div>child</div>
      </SectionBlock>,
    )

    expect(screen.getByTestId('action-btn')).toBeInTheDocument()
  })

  it('exposes a data-testid hook for the section', () => {
    render(
      <SectionBlock title="Tagged" data-testid="section-block-tagged">
        <div>child</div>
      </SectionBlock>,
    )

    expect(screen.getByTestId('section-block-tagged')).toBeInTheDocument()
  })
})

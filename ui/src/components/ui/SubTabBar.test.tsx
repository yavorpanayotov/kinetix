import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { SubTabBar } from './SubTabBar'

const TABS = [
  { id: 'blotter', label: 'Trade Blotter' },
  { id: 'place', label: 'Place Order' },
  { id: 'cost', label: 'Execution Cost' },
] as const

describe('SubTabBar', () => {
  it('renders one button per tab with the supplied label', () => {
    render(<SubTabBar tabs={[...TABS]} activeId="blotter" onSelect={() => {}} />)

    expect(screen.getByRole('tab', { name: 'Trade Blotter' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Place Order' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Execution Cost' })).toBeInTheDocument()
  })

  it('marks only the active tab as aria-selected="true"', () => {
    render(<SubTabBar tabs={[...TABS]} activeId="cost" onSelect={() => {}} />)

    expect(screen.getByRole('tab', { name: 'Trade Blotter' })).toHaveAttribute(
      'aria-selected',
      'false',
    )
    expect(screen.getByRole('tab', { name: 'Place Order' })).toHaveAttribute(
      'aria-selected',
      'false',
    )
    expect(screen.getByRole('tab', { name: 'Execution Cost' })).toHaveAttribute(
      'aria-selected',
      'true',
    )
  })

  it('applies the canonical active class (with dark-mode primary text) to the active tab', () => {
    render(<SubTabBar tabs={[...TABS]} activeId="place" onSelect={() => {}} />)

    const activeTab = screen.getByRole('tab', { name: 'Place Order' })
    expect(activeTab.className).toContain('border-primary-500')
    expect(activeTab.className).toContain('text-primary-600')
    expect(activeTab.className).toMatch(/dark:text-primary-/)

    const inactiveTab = screen.getByRole('tab', { name: 'Trade Blotter' })
    expect(inactiveTab.className).toContain('border-transparent')
    expect(inactiveTab.className).not.toContain('border-primary-500')
  })

  it('calls onSelect with the tab id when a tab is clicked', async () => {
    const user = userEvent.setup()
    const onSelect = vi.fn()
    render(<SubTabBar tabs={[...TABS]} activeId="blotter" onSelect={onSelect} />)

    await user.click(screen.getByRole('tab', { name: 'Execution Cost' }))

    expect(onSelect).toHaveBeenCalledTimes(1)
    expect(onSelect).toHaveBeenCalledWith('cost')
  })

  it('exposes data-testid on each tab when supplied', () => {
    render(
      <SubTabBar
        tabs={[
          { id: 'a', label: 'Alpha', testId: 'sub-a' },
          { id: 'b', label: 'Beta', testId: 'sub-b' },
        ]}
        activeId="a"
        onSelect={() => {}}
      />,
    )

    expect(screen.getByTestId('sub-a')).toBeInTheDocument()
    expect(screen.getByTestId('sub-b')).toBeInTheDocument()
  })

  it('renders an optional count badge for tabs that supply one', () => {
    render(
      <SubTabBar
        tabs={[
          { id: 'a', label: 'Alpha', count: 3 },
          { id: 'b', label: 'Beta' },
        ]}
        activeId="a"
        onSelect={() => {}}
      />,
    )

    const alpha = screen.getByRole('tab', { name: /Alpha/ })
    expect(alpha.textContent).toContain('3')

    const beta = screen.getByRole('tab', { name: /Beta/ })
    // No count means no numeric suffix.
    expect(beta.textContent).not.toMatch(/\d/)
  })

  it('does not render a count badge when count is zero or undefined', () => {
    render(
      <SubTabBar
        tabs={[{ id: 'a', label: 'Alpha', count: 0 }]}
        activeId="a"
        onSelect={() => {}}
      />,
    )

    const alpha = screen.getByRole('tab', { name: /Alpha/ })
    expect(alpha.textContent).not.toMatch(/\d/)
  })

  it('renders a tablist with the supplied aria-label', () => {
    render(
      <SubTabBar
        tabs={[...TABS]}
        activeId="blotter"
        onSelect={() => {}}
        aria-label="Trades sections"
      />,
    )

    const tablist = screen.getByRole('tablist', { name: 'Trades sections' })
    expect(tablist).toBeInTheDocument()
  })

  it('appends caller-supplied className to the tablist container', () => {
    render(
      <SubTabBar
        tabs={[...TABS]}
        activeId="blotter"
        onSelect={() => {}}
        className="mt-6"
      />,
    )

    const tablist = screen.getByRole('tablist')
    expect(tablist.className).toContain('mt-6')
    // Canonical layout classes are still present.
    expect(tablist.className).toContain('border-b')
  })
})

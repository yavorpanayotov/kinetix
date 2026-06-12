import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { PersonaSwitcher } from './PersonaSwitcher'
import { DemoAuthProvider } from '../auth/DemoAuthProvider'
import { DEMO_PERSONAS } from '../auth/demoPersonas'

function renderSwitcher() {
  return render(
    <DemoAuthProvider>
      <PersonaSwitcher />
    </DemoAuthProvider>,
  )
}

describe('PersonaSwitcher', () => {
  it('renders toggle with default RISK_MANAGER persona', () => {
    renderSwitcher()
    expect(screen.getByTestId('header-role-badge')).toHaveTextContent('RISK MANAGER')
    expect(screen.getByTestId('header-username')).toHaveTextContent('risk_manager1')
  })

  it('toggle has visible button shape with border styling', () => {
    renderSwitcher()
    const toggle = screen.getByTestId('persona-switcher-toggle')
    expect(toggle.className).toContain('border')
    expect(toggle.className).toContain('rounded-md')
  })

  it('opens dropdown on click showing all 5 personas', () => {
    renderSwitcher()
    expect(screen.queryByTestId('persona-switcher-panel')).not.toBeInTheDocument()

    fireEvent.click(screen.getByTestId('persona-switcher-toggle'))

    expect(screen.getByTestId('persona-switcher-panel')).toBeInTheDocument()
    for (const p of DEMO_PERSONAS) {
      expect(screen.getByTestId(`persona-option-${p.key}`)).toBeInTheDocument()
    }
  })

  it('asks for confirmation before switching, then applies and announces the switch', () => {
    // UX review: a single stray click silently switched role (and persisted
    // across sessions). Selection stages a confirm dialog.
    renderSwitcher()
    fireEvent.click(screen.getByTestId('persona-switcher-toggle'))
    fireEvent.click(screen.getByTestId('persona-option-trader'))

    // Not switched yet — the dialog is asking.
    expect(screen.getByTestId('header-role-badge')).toHaveTextContent('RISK MANAGER')
    const dialog = screen.getByTestId('confirm-dialog')
    expect(dialog).toHaveTextContent(/trader1/)

    fireEvent.click(screen.getByTestId('confirm-dialog-confirm'))

    expect(screen.getByTestId('header-role-badge')).toHaveTextContent('TRADER')
    expect(screen.getByTestId('header-username')).toHaveTextContent('trader1')
    expect(screen.getByTestId('persona-switch-toast')).toHaveTextContent(/now viewing as/i)
  })

  it('keeps the current persona when the confirm dialog is cancelled', () => {
    renderSwitcher()
    fireEvent.click(screen.getByTestId('persona-switcher-toggle'))
    fireEvent.click(screen.getByTestId('persona-option-trader'))

    fireEvent.click(screen.getByTestId('confirm-dialog-cancel'))

    expect(screen.getByTestId('header-role-badge')).toHaveTextContent('RISK MANAGER')
    expect(screen.queryByTestId('persona-switch-toast')).not.toBeInTheDocument()
  })

  it('does not raise the dialog when re-selecting the active persona', () => {
    renderSwitcher()
    fireEvent.click(screen.getByTestId('persona-switcher-toggle'))
    fireEvent.click(screen.getByTestId('persona-option-risk_manager'))

    expect(screen.queryByTestId('confirm-dialog')).not.toBeInTheDocument()
  })

  it('closes dropdown after selection', () => {
    renderSwitcher()
    fireEvent.click(screen.getByTestId('persona-switcher-toggle'))
    expect(screen.getByTestId('persona-switcher-panel')).toBeInTheDocument()

    fireEvent.click(screen.getByTestId('persona-option-admin'))
    expect(screen.queryByTestId('persona-switcher-panel')).not.toBeInTheDocument()
  })

  it('closes dropdown on click outside', () => {
    renderSwitcher()
    fireEvent.click(screen.getByTestId('persona-switcher-toggle'))
    expect(screen.getByTestId('persona-switcher-panel')).toBeInTheDocument()

    fireEvent.mouseDown(document.body)
    expect(screen.queryByTestId('persona-switcher-panel')).not.toBeInTheDocument()
  })

  it('highlights active persona with check mark and others without', () => {
    renderSwitcher()
    fireEvent.click(screen.getByTestId('persona-switcher-toggle'))

    const activeOption = screen.getByTestId('persona-option-risk_manager')
    expect(activeOption.getAttribute('aria-selected')).toBe('true')

    const inactiveOption = screen.getByTestId('persona-option-trader')
    expect(inactiveOption.getAttribute('aria-selected')).toBe('false')
  })

  it('keyboard: ArrowDown/ArrowUp moves focus, Enter selects, Escape closes', () => {
    renderSwitcher()
    const toggle = screen.getByTestId('persona-switcher-toggle')

    // ArrowDown opens dropdown and focuses first option
    fireEvent.keyDown(toggle, { key: 'ArrowDown' })
    expect(screen.getByTestId('persona-switcher-panel')).toBeInTheDocument()

    const firstOption = screen.getByTestId('persona-option-risk_manager')
    // ArrowDown moves to next option
    fireEvent.keyDown(firstOption, { key: 'ArrowDown' })
    const secondOption = screen.getByTestId('persona-option-trader')
    expect(document.activeElement).toBe(secondOption)

    // Enter stages the focused option for confirmation
    fireEvent.keyDown(secondOption, { key: 'Enter' })
    expect(screen.queryByTestId('persona-switcher-panel')).not.toBeInTheDocument()
    fireEvent.click(screen.getByTestId('confirm-dialog-confirm'))
    expect(screen.getByTestId('header-role-badge')).toHaveTextContent('TRADER')
  })

  it('focus returns to toggle button after the switch is confirmed', () => {
    renderSwitcher()
    const toggle = screen.getByTestId('persona-switcher-toggle')
    fireEvent.click(toggle)
    fireEvent.click(screen.getByTestId('persona-option-compliance'))
    fireEvent.click(screen.getByTestId('confirm-dialog-confirm'))

    expect(document.activeElement).toBe(toggle)
  })

  it('focus returns to toggle button when the switch is cancelled', () => {
    renderSwitcher()
    const toggle = screen.getByTestId('persona-switcher-toggle')
    fireEvent.click(toggle)
    fireEvent.click(screen.getByTestId('persona-option-compliance'))
    fireEvent.click(screen.getByTestId('confirm-dialog-cancel'))

    expect(document.activeElement).toBe(toggle)
  })

  it('has correct ARIA attributes', () => {
    renderSwitcher()
    const toggle = screen.getByTestId('persona-switcher-toggle')
    expect(toggle.getAttribute('aria-haspopup')).toBe('listbox')
    expect(toggle.getAttribute('aria-expanded')).toBe('false')

    fireEvent.click(toggle)
    expect(toggle.getAttribute('aria-expanded')).toBe('true')

    const panel = screen.getByTestId('persona-switcher-panel')
    expect(panel.getAttribute('role')).toBe('listbox')

    const option = screen.getByTestId('persona-option-risk_manager')
    expect(option.getAttribute('role')).toBe('option')
  })
})

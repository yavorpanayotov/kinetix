import { render, screen, fireEvent } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ExplainButton } from './ExplainButton'

describe('ExplainButton', () => {
  it('renders with the default label "Explain"', () => {
    render(<ExplainButton onClick={() => {}} data-testid="explain-foo" />)
    expect(screen.getByTestId('explain-foo')).toHaveTextContent('Explain')
  })

  it('renders the Sparkles icon', () => {
    const { container } = render(
      <ExplainButton onClick={() => {}} data-testid="explain-foo" />,
    )
    // lucide-react renders the icon as an <svg> child inside the button.
    const svg = container.querySelector('button svg')
    expect(svg).not.toBeNull()
    // lucide-react attaches a stable `lucide-sparkles` class to the SVG.
    // SVGAnimatedString — use baseVal or className.toString() pattern.
    const classAttr = svg?.getAttribute('class') ?? ''
    expect(classAttr).toContain('lucide-sparkles')
  })

  it('label can be overridden', () => {
    render(
      <ExplainButton onClick={() => {}} label="Why?" data-testid="explain-foo" />,
    )
    expect(screen.getByTestId('explain-foo')).toHaveTextContent('Why?')
  })

  it('calls onClick when clicked', () => {
    const onClick = vi.fn()
    render(<ExplainButton onClick={onClick} data-testid="explain-foo" />)
    fireEvent.click(screen.getByTestId('explain-foo'))
    expect(onClick).toHaveBeenCalledTimes(1)
  })

  it('does not call onClick when disabled', () => {
    const onClick = vi.fn()
    render(
      <ExplainButton onClick={onClick} disabled data-testid="explain-foo" />,
    )
    fireEvent.click(screen.getByTestId('explain-foo'))
    expect(onClick).not.toHaveBeenCalled()
  })

  it('applies aria-label when supplied', () => {
    render(
      <ExplainButton
        onClick={() => {}}
        ariaLabel="Explain VaR"
        data-testid="explain-foo"
      />,
    )
    expect(screen.getByLabelText('Explain VaR')).toBeInTheDocument()
  })

  it('uses label as default aria-label', () => {
    render(<ExplainButton onClick={() => {}} data-testid="explain-foo" />)
    expect(screen.getByLabelText('Explain')).toBeInTheDocument()
  })

  it('forwards data-testid to the button element', () => {
    render(<ExplainButton onClick={() => {}} data-testid="explain-foo" />)
    expect(screen.getByTestId('explain-foo')).toBeInTheDocument()
  })

  it('forwards className alongside default classes', () => {
    render(
      <ExplainButton
        onClick={() => {}}
        className="extra-class"
        data-testid="explain-foo"
      />,
    )
    const button = screen.getByTestId('explain-foo')
    // Spot-check a couple of the default tailwind classes alongside the override.
    expect(button.className).toContain('extra-class')
    expect(button.className).toContain('inline-flex')
    expect(button.className).toContain('text-indigo-600')
  })

  it('button has type="button" to prevent form submission', () => {
    render(<ExplainButton onClick={() => {}} data-testid="explain-foo" />)
    const button = screen.getByTestId('explain-foo') as HTMLButtonElement
    expect(button.type).toBe('button')
  })

  it('renders a spinner and disables clicks while busy', () => {
    const onClick = vi.fn()
    render(
      <ExplainButton
        onClick={onClick}
        isBusy
        data-testid="explain-foo"
      />,
    )
    const button = screen.getByTestId('explain-foo') as HTMLButtonElement
    // Spinner replaces the Sparkles icon while busy.
    expect(screen.getByTestId('explain-spinner')).toBeInTheDocument()
    // aria-busy is set so assistive tech announces the pending work.
    expect(button.getAttribute('aria-busy')).toBe('true')
    // Disabled while busy — absorbs double-clicks during the 10+s wait.
    expect(button.disabled).toBe(true)
    fireEvent.click(button)
    expect(onClick).not.toHaveBeenCalled()
  })

  it('swaps label copy to "Explaining…" when busy and label is non-empty', () => {
    render(
      <ExplainButton
        onClick={() => {}}
        isBusy
        label="Explain"
        data-testid="explain-foo"
      />,
    )
    expect(screen.getByTestId('explain-foo')).toHaveTextContent('Explaining…')
  })

  it('keeps icon-only call sites icon-only while busy', () => {
    // Per-row Explain buttons in PositionRiskTable use ``label=""`` so the
    // 32px action column stays compact. The spinner alone signals the
    // pending state — "Explaining…" would overflow the column.
    render(
      <ExplainButton
        onClick={() => {}}
        isBusy
        label=""
        ariaLabel="Explain row"
        data-testid="explain-foo"
      />,
    )
    const button = screen.getByTestId('explain-foo')
    expect(screen.getByTestId('explain-spinner')).toBeInTheDocument()
    expect(button.textContent?.trim()).toBe('')
  })
})

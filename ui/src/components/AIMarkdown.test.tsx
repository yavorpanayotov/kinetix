import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { AIMarkdown } from './AIMarkdown'

describe('AIMarkdown', () => {
  it('renders plain text', () => {
    render(<AIMarkdown source="VaR is stable today." />)

    expect(screen.getByTestId('ai-markdown')).toHaveTextContent(
      'VaR is stable today.',
    )
  })

  it('renders **bold** as <strong>', () => {
    render(<AIMarkdown source="Driver: **tech beta** rose." />)

    const root = screen.getByTestId('ai-markdown')
    const strong = within(root).getByText('tech beta')
    expect(strong.tagName).toBe('STRONG')
  })

  it('renders *italic* as <em>', () => {
    render(<AIMarkdown source="The model is *experimental*." />)

    const root = screen.getByTestId('ai-markdown')
    const em = within(root).getByText('experimental')
    expect(em.tagName).toBe('EM')
  })

  it('renders unordered lists with disc markers', () => {
    render(
      <AIMarkdown
        source={'Drivers:\n\n- AAPL\n- MSFT\n- NVDA'}
      />,
    )

    const root = screen.getByTestId('ai-markdown')
    const list = within(root).getByRole('list')
    expect(list.tagName).toBe('UL')
    const items = within(list).getAllByRole('listitem')
    expect(items.map((i) => i.textContent)).toEqual(['AAPL', 'MSFT', 'NVDA'])
  })

  it('renders ordered lists', () => {
    render(<AIMarkdown source={'1. First\n2. Second\n3. Third'} />)

    const list = within(screen.getByTestId('ai-markdown')).getByRole('list')
    expect(list.tagName).toBe('OL')
  })

  it('renders inline `code` with a monospace span', () => {
    render(<AIMarkdown source="Field `netDelta` is positive." />)

    const code = within(screen.getByTestId('ai-markdown')).getByText('netDelta')
    expect(code.tagName).toBe('CODE')
  })

  it('renders fenced code blocks inside an <pre>', () => {
    render(<AIMarkdown source={'```\nlimit: 1.5\n```'} />)

    const root = screen.getByTestId('ai-markdown')
    expect(root.querySelector('pre')).not.toBeNull()
    expect(root.querySelector('pre code')).not.toBeNull()
    expect(root.querySelector('pre code')?.textContent).toContain('limit: 1.5')
  })

  it('downcasts # heading to a small bold element (no h1 in panel)', () => {
    render(<AIMarkdown source="# Summary" />)

    const root = screen.getByTestId('ai-markdown')
    // No actual <h1> — the renderer downcasts to a paragraph-weight
    // element so the heading does not compete with the panel's own
    // section title.
    expect(root.querySelector('h1')).toBeNull()
    expect(root).toHaveTextContent('Summary')
  })

  it('renders links with target=_blank and rel=noopener noreferrer', () => {
    render(<AIMarkdown source="[docs](https://example.com/risk)" />)

    const link = within(screen.getByTestId('ai-markdown')).getByRole('link', {
      name: 'docs',
    })
    expect(link).toHaveAttribute('href', 'https://example.com/risk')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', expect.stringContaining('noopener'))
    expect(link).toHaveAttribute('rel', expect.stringContaining('noreferrer'))
  })

  it('renders GFM tables', () => {
    render(
      <AIMarkdown
        source={
          '| Ticker | VaR |\n| --- | --- |\n| AAPL | 1.2% |\n| MSFT | 0.8% |'
        }
      />,
    )

    const root = screen.getByTestId('ai-markdown')
    const table = root.querySelector('table')
    expect(table).not.toBeNull()
    expect(within(table as HTMLElement).getByText('AAPL')).toBeInTheDocument()
    expect(within(table as HTMLElement).getByText('1.2%')).toBeInTheDocument()
  })

  it('does NOT render images — strips ![]() output', () => {
    render(
      <AIMarkdown
        source={'Some text ![pwn](https://evil.example.com/x.png) here.'}
      />,
    )

    const root = screen.getByTestId('ai-markdown')
    expect(root.querySelector('img')).toBeNull()
  })

  it('does NOT render raw HTML — treats it as text', () => {
    render(
      <AIMarkdown source={'Hello <script>alert(1)</script> world'} />,
    )

    const root = screen.getByTestId('ai-markdown')
    expect(root.querySelector('script')).toBeNull()
  })

  it('handles an empty string without crashing', () => {
    render(<AIMarkdown source="" />)
    expect(screen.getByTestId('ai-markdown')).toBeInTheDocument()
  })

  it('handles trailing unfinished markdown gracefully (streaming mid-token)', () => {
    // While the stream is mid-flight a closing ``**`` may not have
    // arrived yet. The renderer should not crash and should at minimum
    // expose the in-flight characters as text.
    render(<AIMarkdown source="Driver: **tech bet" />)

    expect(screen.getByTestId('ai-markdown')).toHaveTextContent(/tech bet/)
  })
})

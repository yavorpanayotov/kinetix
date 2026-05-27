import ReactMarkdown from 'react-markdown'
import type { Components } from 'react-markdown'
import remarkGfm from 'remark-gfm'

export interface AIMarkdownProps {
  /**
   * The markdown source emitted by the AI. May contain partial /
   * unfinished syntax during streaming — the renderer falls back to
   * literal text for any incomplete tokens, so passing a half-written
   * `**bold` is safe.
   */
  source: string

  /**
   * Optional class name applied to the wrapper. The component owns
   * typography (size, line-height) so callers usually leave this
   * alone — used by consumers that need a margin or overflow hint
   * (e.g. ``max-h-[300px] overflow-y-auto``) outside the renderer.
   */
  className?: string

  /**
   * Typographic density.
   *
   * - ``normal`` (default) — ``text-sm`` body. Right for explainer
   *   panels and the streaming narrative, where the AI output is the
   *   primary content of the surface.
   * - ``compact`` — ``text-xs`` body with tighter margins. Right for
   *   the morning brief and other side-panel surfaces where AI text
   *   sits alongside dense chrome and the surrounding section uses
   *   ``text-xs`` already.
   */
  density?: 'normal' | 'compact'
}

interface DensityClasses {
  body: string
  inlineCode: string
  block: string
}

const DENSITY: Record<'normal' | 'compact', DensityClasses> = {
  normal: {
    body: 'text-sm leading-relaxed',
    inlineCode: 'text-xs',
    block: 'mb-2 last:mb-0',
  },
  compact: {
    body: 'text-xs leading-relaxed',
    inlineCode: 'text-[11px]',
    block: 'mb-1 last:mb-0',
  },
}

/**
 * Risk-UI–constrained markdown renderer for AI-generated output.
 *
 * One renderer, used by every surface that shows model text
 * (``AIInsightPanel`` buffered branch, ``StreamingNarrative`` token
 * branch). The component map is deliberately narrow:
 *
 * - Headings are downcast to small bold text so a model-emitted
 *   ``# Summary`` does not compete with the panel's own section title.
 * - Inline ``code`` and fenced blocks use mono with a subtle tint,
 *   plus ``overflow-x: auto`` on the block so long lines never blow
 *   out the panel.
 * - Links always open in a new tab with ``rel="noopener noreferrer"``.
 * - GFM tables get an ``overflow-x: auto`` wrapper so wide tables don't
 *   stretch the panel.
 * - Images and raw HTML are not rendered (no ``rehype-raw``); ``img``
 *   is mapped to a fragment that drops the node.
 *
 * Numbers in tables render with ``font-variant-numeric: tabular-nums``
 * so digits align on width.
 */
function buildComponents(density: 'normal' | 'compact'): Components {
  const d = DENSITY[density]
  return {
    // Downcast headings so they sit inside the panel hierarchy rather
    // than overpowering its title.
    h1: ({ children }) => (
      <p className={`mt-2 mb-1 ${d.body} font-semibold text-slate-800 dark:text-slate-100`}>
        {children}
      </p>
    ),
    h2: ({ children }) => (
      <p className={`mt-2 mb-1 ${d.body} font-semibold text-slate-800 dark:text-slate-100`}>
        {children}
      </p>
    ),
    h3: ({ children }) => (
      <p className={`mt-2 mb-1 ${d.body} font-semibold text-slate-700 dark:text-slate-200`}>
        {children}
      </p>
    ),
    h4: ({ children }) => (
      <p className={`mt-2 mb-1 ${d.body} font-semibold text-slate-700 dark:text-slate-200`}>
        {children}
      </p>
    ),
    h5: ({ children }) => (
      <p className={`mt-2 mb-1 ${d.body} font-semibold text-slate-700 dark:text-slate-200`}>
        {children}
      </p>
    ),
    h6: ({ children }) => (
      <p className={`mt-2 mb-1 ${d.body} font-semibold text-slate-700 dark:text-slate-200`}>
        {children}
      </p>
    ),
    p: ({ children }) => (
      <p className={`${d.block} ${d.body} text-slate-700 dark:text-slate-200`}>
        {children}
      </p>
    ),
    ul: ({ children }) => (
      <ul className={`${d.block} list-disc pl-5 space-y-1 marker:text-slate-400 dark:marker:text-slate-500 ${d.body} text-slate-700 dark:text-slate-200`}>
        {children}
      </ul>
    ),
    ol: ({ children }) => (
      <ol className={`${d.block} list-decimal pl-5 space-y-1 marker:text-slate-400 dark:marker:text-slate-500 ${d.body} text-slate-700 dark:text-slate-200`}>
        {children}
      </ol>
    ),
    li: ({ children }) => <li className="leading-relaxed">{children}</li>,
    strong: ({ children }) => (
      <strong className="font-semibold text-slate-800 dark:text-slate-100">
        {children}
      </strong>
    ),
    em: ({ children }) => <em className="italic">{children}</em>,
    a: ({ children, href }) => (
      <a
        href={href}
        target="_blank"
        rel="noopener noreferrer"
        className="text-primary-600 dark:text-primary-400 underline underline-offset-2 hover:text-primary-700 dark:hover:text-primary-300"
      >
        {children}
      </a>
    ),
    blockquote: ({ children }) => (
      <blockquote className={`${d.block} border-l-2 border-slate-300 dark:border-surface-600 pl-3 italic text-slate-600 dark:text-slate-300`}>
        {children}
      </blockquote>
    ),
    code: ({ className, children, ...rest }) => {
      // `react-markdown` v10 routes both inline and fenced through the
      // same ``code`` slot. The fenced variant always carries a
      // ``language-*`` class (or is wrapped in ``<pre>``); the inline
      // variant carries neither. We distinguish by the className: the
      // ``pre`` slot below renders the block frame, this slot styles
      // the text.
      const isFenced = typeof className === 'string' && className.startsWith('language-')
      if (isFenced) {
        return (
          <code className={`${className ?? ''} font-mono ${d.inlineCode}`} {...rest}>
            {children}
          </code>
        )
      }
      return (
        <code
          className={`rounded bg-slate-100 dark:bg-surface-700 px-1 py-0.5 font-mono ${d.inlineCode} text-slate-800 dark:text-slate-100`}
          {...rest}
        >
          {children}
        </code>
      )
    },
    pre: ({ children }) => (
      <pre className={`${d.block} overflow-x-auto rounded bg-slate-100 dark:bg-surface-900 p-2 ${d.inlineCode} text-slate-800 dark:text-slate-100`}>
        {children}
      </pre>
    ),
    table: ({ children }) => (
      <div className={`${d.block} overflow-x-auto`}>
        <table className={`min-w-full ${d.inlineCode} text-slate-700 dark:text-slate-200 [font-variant-numeric:tabular-nums]`}>
          {children}
        </table>
      </div>
    ),
    thead: ({ children }) => (
      <thead className="border-b border-slate-200 dark:border-surface-700">
        {children}
      </thead>
    ),
    tbody: ({ children }) => (
      <tbody className="divide-y divide-slate-100 dark:divide-surface-800">
        {children}
      </tbody>
    ),
    tr: ({ children }) => (
      <tr className="even:bg-slate-50 dark:even:bg-surface-800/40">{children}</tr>
    ),
    th: ({ children }) => (
      <th className="px-2 py-1 text-left font-semibold text-slate-600 dark:text-slate-300">
        {children}
      </th>
    ),
    td: ({ children }) => <td className="px-2 py-1 align-top">{children}</td>,
    hr: () => (
      <hr className="my-2 border-slate-200 dark:border-surface-700" />
    ),
    // Drop images entirely — the model has no legitimate reason to
    // embed an external image in a risk explainer, and admitting them
    // is an injection / data-exfil surface (remote URLs ping a logger).
    img: () => null,
  }
}

const NORMAL_COMPONENTS = buildComponents('normal')
const COMPACT_COMPONENTS = buildComponents('compact')

export function AIMarkdown({ source, className, density = 'normal' }: AIMarkdownProps) {
  const components =
    density === 'compact' ? COMPACT_COMPONENTS : NORMAL_COMPONENTS
  return (
    <div data-testid="ai-markdown" className={className}>
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>
        {source}
      </ReactMarkdown>
    </div>
  )
}

export default AIMarkdown

import { Loader2, Sparkles } from 'lucide-react'

/**
 * Reusable "Explain" call-to-action button. Wraps the visual treatment that
 * was originally inlined in {@link VaRDashboard} so other surfaces can drop
 * the same affordance next to any panel that has an AI explanation pipeline.
 *
 * The visible label, ARIA label, and `data-testid` are all caller-controlled
 * so each site can wire its own E2E hook and copy.
 */
export interface ExplainButtonProps {
  /** Click handler — typically wires to a chat/insight pipeline. */
  onClick: () => void

  /** Disable the button (e.g. while a prior request is in flight). */
  disabled?: boolean

  /**
   * Visual + ARIA busy state. The model can sit silent for >10 s between
   * the click and the first token; without explicit feedback the user
   * thinks nothing happened and re-clicks. While `isBusy` is `true` the
   * Sparkles icon is replaced with a spinning loader, the button is
   * disabled to absorb double-clicks, and ``aria-busy="true"`` is set so
   * assistive tech announces the pending work.
   */
  isBusy?: boolean

  /** Stable testid for E2E hooks. Required so call sites can scope it. */
  'data-testid': string

  /** Optional label override; defaults to "Explain". */
  label?: string

  /** Optional ARIA label override (defaults to the visible label). */
  ariaLabel?: string

  /** Optional className override for one-off positioning tweaks. */
  className?: string
}

const DEFAULT_CLASSES =
  'inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium text-indigo-600 border border-indigo-300 rounded-md hover:bg-indigo-50 transition-colors disabled:opacity-50 disabled:cursor-not-allowed'

export function ExplainButton({
  onClick,
  disabled = false,
  isBusy = false,
  'data-testid': dataTestId,
  label = 'Explain',
  ariaLabel,
  className,
}: ExplainButtonProps) {
  const combinedClassName = className
    ? `${DEFAULT_CLASSES} ${className}`
    : DEFAULT_CLASSES

  // Keep icon-only call sites (label="") icon-only even while busy —
  // they live in a 32px action column that cannot accommodate the
  // "Explaining…" copy. The spinner alone communicates the pending state.
  const effectiveLabel = isBusy && label ? 'Explaining…' : label

  return (
    <button
      type="button"
      data-testid={dataTestId}
      onClick={onClick}
      disabled={disabled || isBusy}
      aria-busy={isBusy || undefined}
      aria-label={ariaLabel ?? label}
      className={combinedClassName}
    >
      {isBusy ? (
        <Loader2 className="h-4 w-4 animate-spin" data-testid="explain-spinner" />
      ) : (
        <Sparkles className="h-4 w-4" />
      )}
      {effectiveLabel}
    </button>
  )
}

import { useEffect, useRef, type ReactNode } from 'react'

// Focus trap for explainer modal panels (kx-yrqx).
//
// When an explainer modal opens — Greeks explanation, scenario walkthrough,
// risk-attribution rationale — keyboard focus must stay inside the modal
// while it is visible and return to the element that opened it on close.
// WAI-ARIA APG dialog guidance: a Tab from the last focusable element wraps
// to the first, Shift+Tab from the first wraps to the last, and a focus
// landing outside the modal is corrected back in. Without this, sighted
// keyboard users and screen-reader users can lose the modal entirely.
//
// The companion Playwright spec exercises a self-contained HTML fixture
// against the same focus rules so the contract is enforced at the browser
// level too.

interface ExplainerModalFocusTrapProps {
  open: boolean
  onClose: () => void
  /** Accessible label announced when the dialog gains focus. */
  label: string
  children: ReactNode
}

const FOCUSABLE_SELECTOR =
  'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), [tabindex]:not([tabindex="-1"])'

function getFocusable(root: HTMLElement): HTMLElement[] {
  return Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR)).filter(
    el => !el.hasAttribute('disabled') && el.offsetParent !== null,
  )
}

export function ExplainerModalFocusTrap({
  open,
  onClose,
  label,
  children,
}: ExplainerModalFocusTrapProps) {
  const dialogRef = useRef<HTMLDivElement | null>(null)
  const previouslyFocused = useRef<HTMLElement | null>(null)

  useEffect(() => {
    if (!open) return
    previouslyFocused.current = (document.activeElement as HTMLElement | null) ?? null

    const dialog = dialogRef.current
    if (dialog) {
      const focusables = getFocusable(dialog)
      const first = focusables[0] ?? dialog
      first.focus()
    }

    function onKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        e.preventDefault()
        onClose()
        return
      }
      if (e.key !== 'Tab') return
      const dialog2 = dialogRef.current
      if (!dialog2) return
      const focusables = getFocusable(dialog2)
      if (focusables.length === 0) {
        e.preventDefault()
        dialog2.focus()
        return
      }
      const first = focusables[0]
      const last = focusables[focusables.length - 1]
      const active = document.activeElement as HTMLElement | null
      if (e.shiftKey) {
        if (active === first || !dialog2.contains(active)) {
          e.preventDefault()
          last.focus()
        }
      } else if (active === last || !dialog2.contains(active)) {
        e.preventDefault()
        first.focus()
      }
    }

    function onFocusIn(e: FocusEvent) {
      const dialog2 = dialogRef.current
      if (!dialog2) return
      const target = e.target as Node | null
      if (target && !dialog2.contains(target)) {
        const focusables = getFocusable(dialog2)
        ;(focusables[0] ?? dialog2).focus()
      }
    }

    document.addEventListener('keydown', onKeyDown)
    document.addEventListener('focusin', onFocusIn)

    return () => {
      document.removeEventListener('keydown', onKeyDown)
      document.removeEventListener('focusin', onFocusIn)
      previouslyFocused.current?.focus()
    }
  }, [open, onClose])

  if (!open) return null

  return (
    <div
      ref={dialogRef}
      role="dialog"
      aria-modal="true"
      aria-label={label}
      tabIndex={-1}
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/50"
    >
      <div className="bg-white dark:bg-slate-900 rounded shadow-lg p-4 max-w-md w-full">
        {children}
      </div>
    </div>
  )
}

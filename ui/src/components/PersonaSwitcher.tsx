import { useEffect, useRef, useState, useCallback } from 'react'
import { ChevronDown, Check } from 'lucide-react'
import { useClickOutside } from '../hooks/useClickOutside'
import { useDemoPersona } from '../auth/useDemoPersona'
import { DEMO_PERSONAS, type DemoPersona } from '../auth/demoPersonas'
import { ConfirmDialog } from './ui/ConfirmDialog'

/** How long the "Now viewing as…" confirmation toast stays on screen. */
const SWITCH_TOAST_MS = 4000

const ROLE_BADGE_CLASSES: Record<string, string> = {
  ADMIN: 'bg-purple-100 text-purple-800 dark:bg-purple-900/40 dark:text-purple-300',
  RISK_MANAGER: 'bg-blue-100 text-blue-800 dark:bg-blue-900/40 dark:text-blue-300',
  TRADER: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-300',
  COMPLIANCE: 'bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300',
  VIEWER: 'bg-slate-100 text-slate-800 dark:bg-slate-700 dark:text-slate-300',
}

function roleBadgeClass(role: string): string {
  return ROLE_BADGE_CLASSES[role] ?? ROLE_BADGE_CLASSES.VIEWER
}

export function PersonaSwitcher() {
  const [open, setOpen] = useState(false)
  const [focusedIndex, setFocusedIndex] = useState(-1)
  const containerRef = useRef<HTMLDivElement>(null)
  const toggleRef = useRef<HTMLButtonElement>(null)
  const optionRefs = useRef<(HTMLDivElement | null)[]>([])

  const { persona, setPersona } = useDemoPersona()
  // Switching role is consequential (entitlements change and the choice
  // persists across sessions), so a single stray click must not do it
  // silently (UX review): selection stages a confirm dialog, and the applied
  // switch announces itself with a transient status toast.
  const [pendingPersona, setPendingPersona] = useState<DemoPersona | null>(null)
  const [switchedTo, setSwitchedTo] = useState<DemoPersona | null>(null)

  useClickOutside(containerRef, () => {
    setOpen(false)
    setFocusedIndex(-1)
  })

  useEffect(() => {
    if (!switchedTo) return
    const timer = setTimeout(() => setSwitchedTo(null), SWITCH_TOAST_MS)
    return () => clearTimeout(timer)
  }, [switchedTo])

  const selectPersona = useCallback(
    (p: DemoPersona) => {
      setOpen(false)
      setFocusedIndex(-1)
      toggleRef.current?.focus()
      if (p.key === persona.key) return
      setPendingPersona(p)
    },
    [persona.key],
  )

  const confirmSwitch = useCallback(() => {
    if (!pendingPersona) return
    setPersona(pendingPersona)
    setSwitchedTo(pendingPersona)
    setPendingPersona(null)
    toggleRef.current?.focus()
  }, [pendingPersona, setPersona])

  const cancelSwitch = useCallback(() => {
    setPendingPersona(null)
    toggleRef.current?.focus()
  }, [])

  const handleToggleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault()
      if (!open) {
        setOpen(true)
        setFocusedIndex(0)
      } else {
        const next = e.key === 'ArrowDown' ? 0 : DEMO_PERSONAS.length - 1
        setFocusedIndex(next)
        optionRefs.current[next]?.focus()
      }
    }
    if (e.key === 'Escape' && open) {
      e.preventDefault()
      setOpen(false)
      setFocusedIndex(-1)
    }
  }

  const handleOptionKeyDown = (e: React.KeyboardEvent, index: number) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      const next = (index + 1) % DEMO_PERSONAS.length
      setFocusedIndex(next)
      optionRefs.current[next]?.focus()
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      const prev = (index - 1 + DEMO_PERSONAS.length) % DEMO_PERSONAS.length
      setFocusedIndex(prev)
      optionRefs.current[prev]?.focus()
    } else if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault()
      selectPersona(DEMO_PERSONAS[index])
    } else if (e.key === 'Escape') {
      e.preventDefault()
      setOpen(false)
      setFocusedIndex(-1)
      toggleRef.current?.focus()
    }
  }

  return (
    <div ref={containerRef} className="relative" data-testid="persona-switcher">
      <button
        ref={toggleRef}
        data-testid="persona-switcher-toggle"
        onClick={() => setOpen((v) => !v)}
        onKeyDown={handleToggleKeyDown}
        className="flex items-center gap-2 bg-surface-800 border border-surface-700 text-white rounded-md px-3 py-1.5 text-sm hover:bg-surface-700 focus:ring-2 focus:ring-primary-500 transition-colors"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={`Persona: ${persona.label}`}
      >
        <span
          data-testid="header-role-badge"
          aria-label={`Role: ${persona.role}`}
          className={`px-2 py-0.5 text-xs font-medium rounded ${roleBadgeClass(persona.role)}`}
        >
          {persona.role.replace('_', ' ')}
        </span>
        <span data-testid="header-username" className="text-sm text-slate-300">
          {persona.username}
        </span>
        <ChevronDown className={`h-4 w-4 text-slate-400 transition-transform ${open ? 'rotate-180' : ''}`} />
      </button>

      {open && (
        <div
          data-testid="persona-switcher-panel"
          role="listbox"
          aria-label="Select persona"
          className="absolute right-0 top-full mt-1 w-72 bg-surface-800 border border-surface-700 rounded-lg shadow-xl z-50 py-1"
        >
          {DEMO_PERSONAS.map((p, index) => {
            const isActive = p.key === persona.key
            return (
              <div
                key={p.key}
                ref={(el) => { optionRefs.current[index] = el }}
                data-testid={`persona-option-${p.key}`}
                role="option"
                aria-selected={isActive}
                tabIndex={focusedIndex === index ? 0 : -1}
                onClick={() => selectPersona(p)}
                onKeyDown={(e) => handleOptionKeyDown(e, index)}
                className={`flex items-center gap-3 px-3 py-2.5 cursor-pointer transition-colors ${
                  isActive ? 'bg-surface-700' : 'hover:bg-surface-700/50'
                }`}
              >
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <span className={`px-2 py-0.5 text-xs font-medium rounded ${roleBadgeClass(p.role)}`}>
                      {p.label}
                    </span>
                    <span className="text-sm text-slate-400">{p.username}</span>
                  </div>
                  <p className="text-xs text-slate-500 mt-0.5">{p.description}</p>
                </div>
                {isActive && <Check className="h-4 w-4 text-primary-400 flex-shrink-0" />}
              </div>
            )
          })}
        </div>
      )}

      <ConfirmDialog
        open={pendingPersona !== null}
        title="Switch persona"
        message={
          pendingPersona
            ? `View the platform as ${pendingPersona.label} (${pendingPersona.username})? Data and entitlements reload for that role, and the choice persists for future sessions.`
            : ''
        }
        confirmLabel="Switch"
        cancelLabel="Cancel"
        variant="primary"
        onConfirm={confirmSwitch}
        onCancel={cancelSwitch}
      />

      {switchedTo && (
        <div
          data-testid="persona-switch-toast"
          role="status"
          aria-live="polite"
          className="absolute right-0 top-full mt-1 z-50 whitespace-nowrap rounded-md bg-surface-800 border border-surface-600 px-3 py-1.5 text-xs text-slate-200 shadow-lg"
        >
          Now viewing as {switchedTo.label} ({switchedTo.username})
        </div>
      )}
    </div>
  )
}

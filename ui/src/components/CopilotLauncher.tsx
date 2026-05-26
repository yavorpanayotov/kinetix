import { Sparkles } from 'lucide-react'

interface CopilotLauncherProps {
  onOpen: () => void
}

/**
 * Plan §1.1 — Visible discovery affordance.
 *
 * A header button that surfaces the AI copilot to users who wouldn't discover
 * the ⌘K shortcut on their own. Renders with the Sparkles icon, "Ask Kinetix"
 * label, and a dimmed keyboard chip (⌘K on macOS, Ctrl K elsewhere).
 *
 * Clicking it invokes `onOpen`, which in App.tsx opens the CommandPalette in
 * copilotMode. This component has no state — it is a pure presentation element.
 */
export function CopilotLauncher({ onOpen }: CopilotLauncherProps) {
  const isMac = navigator.platform.startsWith('Mac')
  const chip = isMac ? '⌘K' : 'Ctrl K'

  return (
    <button
      data-testid="copilot-launcher"
      onClick={onOpen}
      className="flex items-center gap-1.5 px-2 py-1 rounded-md hover:bg-surface-800 transition-colors text-slate-300 hover:text-white text-sm"
      aria-label="Open AI copilot"
    >
      <Sparkles className="h-4 w-4 shrink-0" />
      <span>Ask Kinetix</span>
      <span
        data-testid="copilot-launcher-chip"
        className="text-[10px] font-mono px-1 py-0.5 rounded bg-surface-700 text-slate-500 select-none"
      >
        {chip}
      </span>
    </button>
  )
}

import { useState } from 'react'
import { X } from 'lucide-react'
import { DEMO_MODE } from '../auth/demoPersonas'

const STORAGE_KEY = 'kinetix_demo_strip_dismissed'

export function DemoWelcomeStrip() {
  // Session-scoped dismissal: the flag lives in sessionStorage so the strip
  // stays dismissed across reloads within the same browser session, but
  // reappears the next time the demo is opened in a fresh session.
  const [dismissed, setDismissed] = useState(
    () => !DEMO_MODE || sessionStorage.getItem(STORAGE_KEY) === 'true',
  )

  if (dismissed) return null

  const handleDismiss = () => {
    sessionStorage.setItem(STORAGE_KEY, 'true')
    setDismissed(true)
  }

  return (
    <div
      data-testid="demo-welcome-strip"
      role="status"
      aria-live="polite"
      aria-label="Demo mode notice"
      className="bg-primary-500/10 border-b border-primary-500/25 text-primary-200 dark:text-primary-300 px-6 py-2 text-sm flex items-center justify-between"
    >
      <span>
        Demo mode — explore freely. All data is synthetic.{' '}
        <span className="opacity-75">
          Switch personas above to see the platform from different roles.
        </span>
      </span>
      <button
        data-testid="demo-strip-dismiss"
        onClick={handleDismiss}
        className="ml-4 flex-shrink-0 p-1 rounded text-primary-300 hover:text-white hover:bg-primary-500/20 transition-colors focus:outline-none focus:ring-2 focus:ring-primary-500"
        aria-label="Dismiss demo mode notice"
      >
        <X className="h-3.5 w-3.5" />
      </button>
    </div>
  )
}

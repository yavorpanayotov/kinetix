import { useState } from 'react'
import { Activity, Shield, TrendingUp, Bell, BarChart3, Sun, Moon } from 'lucide-react'
import { useTheme } from '../../hooks/useTheme'
import { useBookSelector } from '../../hooks/useBookSelector'
import { DEFAULT_MOBILE_VIEW, type MobileView } from './MobileView'

// Plan §mobile — below the 1280px desktop floor App.tsx renders <MobileApp>
// instead of the small-viewport warning. This is the phone-first surface: a
// compact header (logo, book selector, theme toggle), a single-column body,
// and a thumb-reach bottom tab bar switching between four curated views.
//
// This file is the SCAFFOLD: each view is an empty placeholder. The real view
// components are wired in a later checkbox. The layout is `max-width` capped so
// it reads on a tablet too, and honours dark mode via the existing `.dark` /
// Tailwind `dark:` mechanism.

// The bottom tab bar entries, in display order. Each maps to a MobileView and
// carries its lucide icon, mirroring the desktop tab metadata in App.tsx.
const MOBILE_NAV: { view: MobileView; label: string; icon: typeof Activity }[] = [
  { view: 'risk', label: 'Risk', icon: Shield },
  { view: 'pnl', label: 'P&L', icon: TrendingUp },
  { view: 'alerts', label: 'Alerts', icon: Bell },
  { view: 'positions', label: 'Positions', icon: BarChart3 },
]

export function MobileApp() {
  const { isDark, toggle: toggleTheme } = useTheme()
  const bookSelector = useBookSelector()
  const [activeMobileView, setActiveMobileView] = useState<MobileView>(
    DEFAULT_MOBILE_VIEW,
  )

  return (
    <div
      data-testid="mobile-app"
      className="min-h-screen flex flex-col bg-surface-50 dark:bg-surface-900 dark:text-slate-100"
    >
      {/* max-width keeps the single column comfortable on a tablet while the
          flex centring lets it sit phone-edge-to-edge below ~640px. */}
      <div className="flex flex-col flex-1 w-full max-w-2xl mx-auto">
        <header
          data-testid="mobile-header"
          className="bg-surface-900 text-white px-4 py-3 flex items-center justify-between gap-3"
        >
          <div className="flex items-center gap-2">
            <Activity className="h-5 w-5 text-primary-500" />
            <h1 className="text-base font-bold tracking-tight">Kinetix</h1>
          </div>
          <div className="flex items-center gap-2">
            <label className="sr-only" htmlFor="mobile-book-selector">
              Book
            </label>
            <select
              id="mobile-book-selector"
              data-testid="mobile-book-selector"
              value={bookSelector.selectedBookId}
              onChange={(e) => bookSelector.selectBook(e.target.value)}
              className="bg-surface-800 text-slate-100 text-sm rounded-md px-2 py-1 border border-surface-700 max-w-[10rem] truncate"
              aria-label="Select book"
            >
              {bookSelector.bookOptions.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label}
                </option>
              ))}
            </select>
            <button
              data-testid="mobile-dark-mode-toggle"
              onClick={toggleTheme}
              className="p-1.5 rounded-md hover:bg-surface-800 transition-colors text-slate-300 hover:text-white"
              aria-label={isDark ? 'Switch to light mode' : 'Switch to dark mode'}
            >
              {isDark ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
            </button>
          </div>
        </header>

        <main
          data-testid="mobile-main"
          className="flex-1 p-4 overflow-y-auto"
          role="tabpanel"
          aria-labelledby={`mobile-tab-${activeMobileView}`}
        >
          {/* Scaffold placeholders — the real views land in a later checkbox. */}
          {activeMobileView === 'risk' && (
            <div data-testid="mobile-view-risk">Risk</div>
          )}
          {activeMobileView === 'pnl' && (
            <div data-testid="mobile-view-pnl">P&amp;L</div>
          )}
          {activeMobileView === 'alerts' && (
            <div data-testid="mobile-view-alerts">Alerts</div>
          )}
          {activeMobileView === 'positions' && (
            <div data-testid="mobile-view-positions">Positions</div>
          )}
        </main>

        <nav
          data-testid="mobile-tab-bar"
          role="tablist"
          aria-label="Mobile views"
          className="bg-surface-800 border-t border-surface-700 flex"
        >
          {MOBILE_NAV.map(({ view, label, icon: Icon }) => {
            const active = activeMobileView === view
            return (
              <button
                key={view}
                id={`mobile-tab-${view}`}
                data-testid={`mobile-tab-${view}`}
                role="tab"
                aria-selected={active}
                onClick={() => setActiveMobileView(view)}
                className={`flex-1 flex flex-col items-center gap-1 py-2 text-xs font-medium transition-colors ${
                  active
                    ? 'text-primary-400'
                    : 'text-slate-400 hover:text-white'
                }`}
              >
                <Icon className="h-5 w-5" />
                <span>{label}</span>
              </button>
            )
          })}
        </nav>
      </div>
    </div>
  )
}

import { useState } from 'react'
import { Activity, Shield, TrendingUp, Bell, BarChart3, Sun, Moon } from 'lucide-react'
import { useTheme } from '../../hooks/useTheme'
import { useBookSelector } from '../../hooks/useBookSelector'
import { useAuth } from '../../auth/useAuth'
import { DEFAULT_MOBILE_VIEW, type MobileView } from './MobileView'
import { MobileRiskView } from './MobileRiskView'
import { MobilePnlView } from './MobilePnlView'
import { MobileAlertsView } from './MobileAlertsView'
import { MobilePositionsView } from './MobilePositionsView'

// Plan §mobile — below the 1280px desktop floor App.tsx renders <MobileApp>
// instead of the small-viewport warning. This is the phone-first surface: a
// compact header (logo, book selector, theme toggle), a single-column body,
// and a thumb-reach bottom tab bar switching between four curated views.
//
// The body renders the ONE view matching the active tab so only that view's
// data hooks run — Risk/P&L/Positions are fed the selected book id, Alerts the
// authenticated username (threaded to useNotifications exactly as App.tsx does).
// The layout is `max-width` capped so it reads on a tablet too, and honours
// dark mode via the existing `.dark` / Tailwind `dark:` mechanism.

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
  const auth = useAuth()
  const [activeMobileView, setActiveMobileView] = useState<MobileView>(
    DEFAULT_MOBILE_VIEW,
  )

  // The single book the per-book views fetch for. When "All Books" is selected
  // the aggregate sentinel is not a real book id, so pass null — the views show
  // their no-data state, mirroring App.tsx's effectiveBookId semantics.
  const effectiveBookId = bookSelector.isAllSelected
    ? null
    : bookSelector.selectedBookId

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
              value={bookSelector.selectedBookId ?? ''}
              onChange={(e) => bookSelector.selectBook(e.target.value)}
              className="bg-surface-800 text-slate-100 text-sm rounded-md px-2 py-2.5 border border-surface-700 min-w-[7rem] max-w-[10rem] truncate"
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
              className="p-2.5 rounded-md hover:bg-surface-800 transition-colors text-slate-300 hover:text-white"
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
          {/* Only the active view is rendered, so only its hooks run. */}
          {activeMobileView === 'risk' && (
            <MobileRiskView bookId={effectiveBookId} />
          )}
          {activeMobileView === 'pnl' && (
            <MobilePnlView bookId={effectiveBookId} />
          )}
          {activeMobileView === 'alerts' && (
            <MobileAlertsView username={auth.username} />
          )}
          {activeMobileView === 'positions' && (
            <MobilePositionsView bookId={effectiveBookId} />
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
                className={`flex-1 flex flex-col items-center justify-center gap-1 py-3 min-h-[48px] text-xs font-medium transition-colors border-t-2 ${
                  active
                    ? 'border-primary-500 text-white'
                    : 'border-transparent text-slate-400 hover:text-white'
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

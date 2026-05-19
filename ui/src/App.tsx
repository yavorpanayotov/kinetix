import { Fragment, useEffect, useRef, useState } from 'react'
import { Activity, BarChart3, ScrollText, TrendingUp, Shield, FlaskConical, Scale, Bell, Server, FlaskRound, Sun, Moon, CalendarDays, Users, FileText, LogOut } from 'lucide-react'
import { ErrorBoundary, SectionErrorCard } from './components/ErrorBoundary'
import { SubTabBar } from './components/ui/SubTabBar'
import { PositionGrid } from './components/PositionGrid'
import { TradeBlotter } from './components/TradeBlotter'
import { PlaceOrderPanel } from './components/PlaceOrderPanel'
import { ExecutionCostPanel } from './components/ExecutionCostPanel'
import { ReconciliationPanel } from './components/ReconciliationPanel'
import { NotificationCenter } from './components/NotificationCenter'
import { SystemDashboard } from './components/SystemDashboard'
import { RiskTab } from './components/RiskTab'
import { ScenariosTab } from './components/ScenariosTab'
import { RegulatoryTab } from './components/RegulatoryTab'
import { PnlTab } from './components/PnlTab'
import { WhatIfPanel } from './components/WhatIfPanel'
import { HedgeRecommendationPanel } from './components/HedgeRecommendationPanel'
import { useHedgeRecommendation } from './hooks/useHedgeRecommendation'
import { CounterpartyRiskDashboard } from './components/CounterpartyRiskDashboard'
import { ReportsTab } from './components/ReportsTab'
import { EodTimelineTab } from './components/EodTimelineTab'
import { BookSummaryCard } from './components/BookSummaryCard'
import { RiskTickerStrip } from './components/RiskTickerStrip'
import { BreachBanner } from './components/BreachBanner'
import { SystemStatusBanner } from './components/SystemStatusBanner'
import { usePositions } from './hooks/usePositions'
import { usePositionNotes } from './hooks/usePositionNotes'
import { useAlerts } from './hooks/useAlerts'
import { useVaR } from './hooks/useVaR'
import { useVarLimit } from './hooks/useVarLimit'
import { useIntradayPnlStream } from './hooks/useIntradayPnlStream'
import { useBookSelector, ALL_BOOKS } from './hooks/useBookSelector'
import { useHierarchySelector } from './hooks/useHierarchySelector'
import { HierarchySelector } from './components/HierarchySelector'
import { usePriceStream } from './hooks/usePriceStream'
import { useNotifications } from './hooks/useNotifications'
import { usePositionRisk } from './hooks/usePositionRisk'
import { useSystemHealth } from './hooks/useSystemHealth'
import { useWhatIf } from './hooks/useWhatIf'
import { useRebalancing } from './hooks/useRebalancing'
import { useStressTest } from './hooks/useStressTest'
import { useRunAllScenarios } from './hooks/useRunAllScenarios'
import { useHierarchySummary } from './hooks/useHierarchySummary'
import { useTheme } from './hooks/useTheme'
import { useDataQuality } from './hooks/useDataQuality'
import { DataQualityIndicator } from './components/DataQualityIndicator'
import { useMarketRegime } from './hooks/useMarketRegime'
import { RegimeIndicator } from './components/RegimeIndicator'
import { useActiveScenario } from './hooks/useActiveScenario'
import { ScenarioIndicator } from './components/ScenarioIndicator'
import { useTapeReplayStatus } from './hooks/useTapeReplayStatus'
import { TapeReplayIndicator } from './components/TapeReplayIndicator'
import { useTraders } from './hooks/useTraders'
import { TraderSelector } from './components/TraderSelector'
import { useWorkspace } from './hooks/useWorkspace'
import { WorkspaceViewPicker } from './components/WorkspaceViewPicker'
import { useAuth } from './auth/useAuth'
import { DEMO_MODE } from './auth/demoPersonas'
import { PersonaSwitcher } from './components/PersonaSwitcher'
import { DemoWelcomeStrip } from './components/DemoWelcomeStrip'
import { KeyboardShortcutsOverlay } from './components/KeyboardShortcutsOverlay'
import { CommandPalette, type CommandItem } from './components/CommandPalette'
import { SmallViewportWarning, MIN_VIEWPORT_WIDTH_PX } from './components/SmallViewportWarning'

type Tab = 'positions' | 'trades' | 'pnl' | 'risk' | 'eod' | 'scenarios' | 'regulatory' | 'counterparty-risk' | 'reports' | 'alerts' | 'system'

// Plan §2.1: the 11 top-level tabs are grouped into three visual clusters
// — Trading, Risk, Ops. The clusters are communicated by thin presentational
// dividers between cluster boundaries; all tabs remain inside a single
// `role="tablist"` so keyboard navigation continues to work uniformly.
//
// The first tab in each cluster carries `clusterStart: true` so the render
// loop can inject a divider element immediately before it (skipping the
// very first tab to avoid a leading divider).
type TabCluster = 'trading' | 'risk' | 'ops'

const TABS: { key: Tab; label: string; icon: typeof Activity; cluster: TabCluster; clusterStart?: boolean }[] = [
  // Trading cluster
  { key: 'positions', label: 'Positions', icon: BarChart3, cluster: 'trading', clusterStart: true },
  { key: 'trades', label: 'Trades', icon: ScrollText, cluster: 'trading' },
  { key: 'pnl', label: 'P&L', icon: TrendingUp, cluster: 'trading' },
  // Risk cluster
  { key: 'risk', label: 'Risk', icon: Shield, cluster: 'risk', clusterStart: true },
  { key: 'eod', label: 'EOD History', icon: CalendarDays, cluster: 'risk' },
  { key: 'scenarios', label: 'Scenarios', icon: FlaskConical, cluster: 'risk' },
  { key: 'counterparty-risk', label: 'Counterparty Risk', icon: Users, cluster: 'risk' },
  // Ops cluster
  { key: 'regulatory', label: 'Regulatory', icon: Scale, cluster: 'ops', clusterStart: true },
  { key: 'reports', label: 'Reports', icon: FileText, cluster: 'ops' },
  { key: 'alerts', label: 'Alerts', icon: Bell, cluster: 'ops' },
  { key: 'system', label: 'System', icon: Server, cluster: 'ops' },
]

// Plan §9 — Desktop-only floor. The viewport check lives in a wrapper so that
// when the window is too narrow we render *only* the warning, without paying
// the cost of mounting the rest of the app tree (data fetches, websockets,
// risk hooks, etc.). When the viewport grows back above the floor we mount
// the full app cleanly.
function App() {
  const [tooSmall, setTooSmall] = useState<boolean>(
    () => typeof window !== 'undefined' && window.innerWidth < MIN_VIEWPORT_WIDTH_PX,
  )

  useEffect(() => {
    function handleResize() {
      setTooSmall(window.innerWidth < MIN_VIEWPORT_WIDTH_PX)
    }
    window.addEventListener('resize', handleResize)
    return () => window.removeEventListener('resize', handleResize)
  }, [])

  if (tooSmall) {
    return <SmallViewportWarning />
  }

  return <AppContent />
}

function AppContent() {
  const workspace = useWorkspace()
  const auth = useAuth()
  const [activeTab, setActiveTab] = useState<Tab>(
    (workspace.preferences.defaultTab as Tab) || 'positions',
  )

  // View-switch state is pushed into the downstream hooks below the hook
  // declarations — see the FU2 block right after `hierarchy` / `bookSelector`.
  const [whatIfOpen, setWhatIfOpen] = useState(false)
  // Plan §8.2 — Hedge Recommendation panel is owned at the App level so the
  // breach surfacing (ticker-strip CTA, breach-banner CTA, RiskTab's own
  // "Suggest Hedge" button, and the Shift+H shortcut) can all converge on a
  // single open/close state and a single panel render.
  const [hedgePanelOpen, setHedgePanelOpen] = useState(false)
  const [tradesSubTab, setTradesSubTab] = useState<'blotter' | 'place' | 'cost' | 'reconciliation'>('blotter')
  // Cross-tab link (plan §2.4): when the user jumps from a counterparty row
  // to the Trades blotter, the chosen counterparty id flows in here so the
  // TradeBlotter opens already filtered to that counterparty.
  const [tradesCounterpartyFilter, setTradesCounterpartyFilter] = useState<string>('')
  // Cross-tab link (plan §2.4): when the user jumps from a report row to
  // Risk, this seeds RiskTab's valuation date so the dashboard re-renders
  // as-of the reported date.
  const [riskInitialValuationDate, setRiskInitialValuationDate] = useState<string | null>(null)
  const [shortcutsOverlayOpen, setShortcutsOverlayOpen] = useState(false)
  // Plan §7.1 — Cmd+K (or Ctrl+K) opens the command palette. State lives at
  // App.tsx because the palette needs setters across the entire app surface.
  const [commandPaletteOpen, setCommandPaletteOpen] = useState(false)
  const focusBeforeOverlayRef = useRef<HTMLElement | null>(null)
  const tabRefs = useRef<Map<Tab, HTMLButtonElement>>(new Map())

  // Global '?' (Shift+/) opens the keyboard shortcuts overlay.
  // Mirrors the Shift+H pattern used in RiskTab.tsx: skip when an input/textarea/select is focused.
  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key !== '?') return
      const target = e.target
      if (
        target instanceof HTMLInputElement ||
        target instanceof HTMLTextAreaElement ||
        target instanceof HTMLSelectElement ||
        (target instanceof HTMLElement && target.isContentEditable)
      ) {
        return
      }
      e.preventDefault()
      if (document.activeElement instanceof HTMLElement) {
        focusBeforeOverlayRef.current = document.activeElement
      }
      setShortcutsOverlayOpen(true)
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [])

  // Plan §8.2 — Shift+H opens/closes the Hedge Recommendation panel from
  // anywhere in the app. Same input-focus guard as the other power-user
  // shortcuts so we don't hijack native text editing.
  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if (!e.shiftKey || (e.key !== 'H' && e.key !== 'h')) return
      const target = e.target
      if (
        target instanceof HTMLInputElement ||
        target instanceof HTMLTextAreaElement ||
        target instanceof HTMLSelectElement ||
        (target instanceof HTMLElement && target.isContentEditable)
      ) {
        return
      }
      setHedgePanelOpen((prev) => !prev)
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [])

  // Plan §7.1 — Cmd+K (Mac) / Ctrl+K (Linux/Windows) opens the command
  // palette. Same input-focus guard as '?': power-user shortcut, but no
  // hijacking native text-editing.
  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key !== 'k' && e.key !== 'K') return
      if (!(e.metaKey || e.ctrlKey)) return
      const target = e.target
      if (
        target instanceof HTMLInputElement ||
        target instanceof HTMLTextAreaElement ||
        target instanceof HTMLSelectElement ||
        (target instanceof HTMLElement && target.isContentEditable)
      ) {
        return
      }
      e.preventDefault()
      setCommandPaletteOpen(true)
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [])

  const closeShortcutsOverlay = () => {
    setShortcutsOverlayOpen(false)
    // Return focus to wherever it was before the overlay opened.
    const previous = focusBeforeOverlayRef.current
    if (previous && document.body.contains(previous)) {
      previous.focus()
    }
    focusBeforeOverlayRef.current = null
  }

  const handleTabKeyDown = (e: React.KeyboardEvent) => {
    const tabKeys = TABS.map(t => t.key)
    const focusedElement = document.activeElement
    const currentIndex = tabKeys.findIndex(
      key => tabRefs.current.get(key) === focusedElement,
    )
    if (currentIndex === -1) return

    let nextIndex: number | null = null
    switch (e.key) {
      case 'ArrowRight':
        nextIndex = (currentIndex + 1) % tabKeys.length
        break
      case 'ArrowLeft':
        nextIndex = (currentIndex - 1 + tabKeys.length) % tabKeys.length
        break
      case 'Home':
        nextIndex = 0
        break
      case 'End':
        nextIndex = tabKeys.length - 1
        break
      default:
        return
    }
    e.preventDefault()
    tabRefs.current.get(tabKeys[nextIndex])?.focus()
  }

  const { positions: initialPositions, bookId: rawBookId, selectBook: rawSelectBook, refreshPositions, retryInitialLoad, loading: rawLoading, error: rawError } = usePositions()
  const bookSelector = useBookSelector()
  const hierarchy = useHierarchySelector()
  const isAllSelected = bookSelector.isAllSelected
  const effectiveBookId = hierarchy.effectiveBookId ?? (isAllSelected ? null : rawBookId)
  const { positions, connected, reconnecting, exhausted, lastConnectedAt, disconnectedSince, manualReconnect } = usePriceStream(
    isAllSelected ? bookSelector.aggregatedPositions : initialPositions,
    undefined,
    refreshPositions,
  )
  const { positionRisk } = usePositionRisk(effectiveBookId)

  const loading = rawLoading || bookSelector.loading
  const error = rawError || bookSelector.error

  // Keep selectBook wired for when hierarchy navigates to a specific book
  const handleBookChange = (id: string) => {
    if (id === ALL_BOOKS) {
      bookSelector.selectBook(ALL_BOOKS)
    } else {
      rawSelectBook(id)
      bookSelector.selectBook(id)
    }
  }
  void handleBookChange // used indirectly via hierarchy selection changes

  // Plan §2.3 FU2 — When the active saved view changes at runtime, push the
  // view's captured prefs into the hooks that own that state. This is a
  // one-shot push on `activeViewId` transition: once switched, the user can
  // freely re-configure, and saving the view will update the prefs.
  //
  // Uses the React "adjust state during render" pattern (see
  // https://react.dev/learn/you-might-not-need-an-effect#adjusting-some-state-when-a-prop-changes)
  // rather than an effect, to avoid an extra commit. The pattern is to
  // compare the latest `activeViewId` against a `useState`-held "last seen"
  // value, and dispatch the push inside the comparison branch. The branch
  // runs at most once per view transition (React bails out when state is
  // unchanged), so it's safe to invoke setters on other hooks.
  //
  // Prefs that already auto-apply because their owners read straight from
  // `workspace.preferences` (so they re-render when the active view changes):
  //   - riskDashboardSections (RiskTab)
  //   - showPositionDetails (PositionGrid)
  //
  // Prefs we explicitly push here because no consumer reads them otherwise:
  //   - defaultTab (→ local `activeTab`)
  //   - defaultBook (→ hierarchy.setSelection + bookSelector.selectBook)
  //
  // `timeRange` is stored as a vestigial string code (`'1d'`) with no
  // runtime consumer; `useVaR`'s timeRange is a `{from,to,label}` object
  // owned by that hook. There's nowhere to push the string code without
  // adding pref fields, which the plan explicitly disallows in this step.
  const [lastViewId, setLastViewId] = useState<string | null>(workspace.activeViewId)
  if (workspace.activeViewId !== lastViewId) {
    setLastViewId(workspace.activeViewId)
    const nextTab = workspace.preferences.defaultTab as Tab
    if (nextTab) setActiveTab(nextTab)
    const nextBook = workspace.preferences.defaultBook
    if (nextBook) {
      hierarchy.setSelection({
        level: 'book',
        divisionId: hierarchy.selection.divisionId,
        deskId: hierarchy.selection.deskId,
        bookId: nextBook,
      })
      if (nextBook !== ALL_BOOKS) {
        rawSelectBook(nextBook)
      }
      bookSelector.selectBook(nextBook)
    }
  }

  const bookId = hierarchy.effectiveBookId ?? (isAllSelected ? ALL_BOOKS : rawBookId)
  // Plan §7.3.3 — per-instrument notes only meaningful when a concrete book is
  // selected (not the firm-wide "All" aggregate).
  const positionNotes = usePositionNotes(effectiveBookId)
  const notifications = useNotifications(auth.username)
  const systemHealth = useSystemHealth()
  const whatIf = useWhatIf(effectiveBookId)
  const rebalancing = useRebalancing()
  const stress = useStressTest(bookId)
  const scenariosAll = useRunAllScenarios(bookId)
  const hierarchySummary = useHierarchySummary(hierarchy.selection)
  const { varResult, greeksResult } = useVaR(effectiveBookId)
  const { varLimit } = useVarLimit()
  const { alerts: breachAlerts, dismissAlert: dismissBreachAlert } = useAlerts()
  const { latest: intradayLatest, connected: intradayConnected } = useIntradayPnlStream(effectiveBookId)
  // Plan §8.2 — Hedge recommendation hook lives at the App level so the
  // panel can be opened from the global ticker strip, the breach banner, or
  // RiskTab's own button without each surface owning duplicate state.
  const hedge = useHedgeRecommendation(effectiveBookId)
  // Plan §8.2 — A single derived predicate for whether the user is in a
  // breach state, mirroring BreachBanner's logic (VaR utilisation > 80% OR
  // any active CRITICAL alert). The ticker strip and breach banner only
  // surface the "Need a hedge?" CTA when this is true.
  const varValueNumber = varResult ? Number(varResult.varValue) : null
  const varUtilisation =
    varValueNumber !== null && varLimit !== null && varLimit > 0
      ? varValueNumber / varLimit
      : null
  const varBreachActive = varUtilisation !== null && varUtilisation > 0.8
  const criticalAlertActive = breachAlerts.some((a) => a.severity === 'CRITICAL')
  const hedgeCtaActive = varBreachActive || criticalAlertActive
  const openHedgePanel = () => setHedgePanelOpen(true)
  const { isDark, toggle: toggleTheme } = useTheme()
  const dataQuality = useDataQuality()
  const marketRegime = useMarketRegime()
  const activeScenario = useActiveScenario()
  const tapeReplay = useTapeReplayStatus()
  const tradersState = useTraders()
  const [selectedTraderId, setSelectedTraderId] = useState<string | null>(null)

  // Plan §7.1 — Compose command palette items from data reachable at this
  // level. Sub-tabs are hardcoded per scope ("the sub-tab structure is
  // static and small"). Counterparties are intentionally not threaded —
  // useCounterpartyRisk lives inside CounterpartyRiskDashboard and pulling
  // it up to App.tsx would trigger an unconditional fetch on every page
  // load; that's flagged as a follow-up.
  const commandPaletteItems: CommandItem[] = [
    // Tabs
    ...TABS.map((tab) => ({
      id: `tab:${tab.key}`,
      group: 'Tabs',
      label: tab.label,
      onActivate: () => setActiveTab(tab.key),
    })),
    // Trades sub-tabs — App.tsx already owns tradesSubTab state.
    {
      id: 'trades-subtab:blotter',
      group: 'Sub-tabs',
      label: 'Trades · Trade Blotter',
      onActivate: () => {
        setTradesSubTab('blotter')
        setActiveTab('trades')
      },
    },
    {
      id: 'trades-subtab:place',
      group: 'Sub-tabs',
      label: 'Trades · Place Order',
      onActivate: () => {
        setTradesSubTab('place')
        setActiveTab('trades')
      },
    },
    {
      id: 'trades-subtab:cost',
      group: 'Sub-tabs',
      label: 'Trades · Execution Cost',
      onActivate: () => {
        setTradesSubTab('cost')
        setActiveTab('trades')
      },
    },
    {
      id: 'trades-subtab:reconciliation',
      group: 'Sub-tabs',
      label: 'Trades · Reconciliation',
      onActivate: () => {
        setTradesSubTab('reconciliation')
        setActiveTab('trades')
      },
    },
    // Risk sub-tabs (palette navigates to parent tab; deep sub-tab routing is
    // a follow-up — RiskTab owns the subTab state internally).
    {
      id: 'risk-subtab:dashboard',
      group: 'Sub-tabs',
      label: 'Risk · Dashboard',
      onActivate: () => setActiveTab('risk'),
    },
    {
      id: 'risk-subtab:intraday',
      group: 'Sub-tabs',
      label: 'Risk · Intraday',
      onActivate: () => setActiveTab('risk'),
    },
    {
      id: 'risk-subtab:run-compare',
      group: 'Sub-tabs',
      label: 'Risk · Run Compare',
      onActivate: () => setActiveTab('risk'),
    },
    {
      id: 'risk-subtab:market-data',
      group: 'Sub-tabs',
      label: 'Risk · Market Data',
      onActivate: () => setActiveTab('risk'),
    },
    // Books — from useBookSelector.allBookIds
    ...bookSelector.allBookIds.map((id) => ({
      id: `book:${id}`,
      group: 'Books',
      label: id,
      onActivate: () => {
        hierarchy.setSelection({
          level: 'book',
          divisionId: hierarchy.selection.divisionId,
          deskId: hierarchy.selection.deskId,
          bookId: id,
        })
        rawSelectBook(id)
        bookSelector.selectBook(id)
      },
    })),
    // Instruments — from currently loaded positions. Capped at 50 so the
    // palette stays responsive on large books; the fuzzy filter will surface
    // any of the loaded set.
    ...Array.from(new Set(positions.map((p) => p.instrumentId)))
      .slice(0, 50)
      .map((instrumentId) => ({
        id: `instrument:${instrumentId}`,
        group: 'Instruments',
        label: instrumentId,
        onActivate: () => setActiveTab('positions'),
      })),
    // Scenarios — useRunAllScenarios already lives in App.tsx
    ...scenariosAll.scenarios.map((name) => ({
      id: `scenario:${name}`,
      group: 'Scenarios',
      label: name,
      onActivate: () => {
        scenariosAll.setSelectedScenario(name)
        setActiveTab('scenarios')
      },
    })),
  ]

  const [disconnectElapsed, setDisconnectElapsed] = useState(0)
  useEffect(() => {
    if (!disconnectedSince) return
    // Use a chained setTimeout rather than setInterval so the browser cannot
    // batch / clamp two ticks together under load. Poll every 250ms (cheap;
    // the only work is an integer floor + state set) so even when Chrome
    // clamps background timers to 1s we still observe a state update inside
    // each Playwright polling window.
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | null = null
    const update = () => {
      if (cancelled) return
      setDisconnectElapsed(
        Math.floor((Date.now() - disconnectedSince.getTime()) / 1000),
      )
      timer = setTimeout(update, 250)
    }
    update()
    return () => {
      cancelled = true
      if (timer !== null) clearTimeout(timer)
      setDisconnectElapsed(0)
    }
  }, [disconnectedSince])

  return (
    <div className="min-h-screen bg-surface-50 dark:bg-surface-900 dark:text-slate-100 flex flex-col">
      <header className="bg-surface-900 text-white px-6 py-3 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Activity className="h-5 w-5 text-primary-500" />
          <h1 className="text-lg font-bold tracking-tight">Kinetix</h1>
          {DEMO_MODE && (
            <span
              data-testid="demo-mode-badge"
              className="px-1.5 py-0.5 text-[10px] font-semibold tracking-wider leading-none rounded bg-primary-500/20 text-primary-300 border border-primary-500/30 select-none self-center"
              aria-label="Demo mode"
            >
              DEMO
            </span>
          )}
        </div>
        <div className="flex items-center gap-3" data-testid="header-right-cluster">
          <HierarchySelector hierarchy={hierarchy} />
          <ScenarioIndicator scenario={activeScenario.scenario} loading={activeScenario.loading} />
          <TapeReplayIndicator status={tapeReplay.status} loading={tapeReplay.loading} />
          <RegimeIndicator regime={marketRegime.regime} loading={marketRegime.loading} />
          <DataQualityIndicator
            status={(() => {
              const baseStatus = dataQuality.status ?? dataQuality.syntheticStatus
              if (reconnecting && baseStatus) {
                return {
                  ...baseStatus,
                  overall: 'WARNING' as const,
                  checks: [
                    { name: 'Price Feed', status: 'WARNING' as const, message: 'WebSocket reconnecting', lastChecked: new Date().toISOString() },
                    ...baseStatus.checks,
                  ],
                }
              }
              return baseStatus
            })()}
            loading={dataQuality.loading}
            replayFrozen={tapeReplay.status === 'FROZEN'}
          />
          {!DEMO_MODE && (
            <WorkspaceViewPicker
              views={workspace.views}
              activeViewId={workspace.activeViewId}
              onSwitchView={workspace.switchView}
              onSaveAsNewView={(name) => {
                // Plan §2.3: snapshot the *current* ephemeral UI state into
                // the brand-new view, without disturbing the existing
                // active view's prefs.
                workspace.saveAsNewView(name, {
                  ...workspace.preferences,
                  defaultTab: activeTab,
                  defaultBook: bookId,
                })
              }}
              onUpdateActiveView={() => {
                // "Update current view" — by contrast — explicitly overwrites
                // the active view with the current ephemeral state.
                workspace.updatePreference('defaultTab', activeTab)
                workspace.updatePreference('defaultBook', bookId)
                workspace.updateActiveView()
              }}
              onDeleteView={workspace.deleteView}
              onRenameView={workspace.renameView}
            />
          )}
          <button
            data-testid="dark-mode-toggle"
            onClick={toggleTheme}
            className="p-1.5 rounded-md hover:bg-surface-800 transition-colors text-slate-300 hover:text-white"
            aria-label={isDark ? 'Switch to light mode' : 'Switch to dark mode'}
          >
            {isDark ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
          </button>
          {auth.authenticated && (
            <div className="border-l border-surface-700 ml-1 pl-3 flex items-center gap-2">
              {DEMO_MODE ? (
                <PersonaSwitcher />
              ) : (
                <>
                  <span
                    data-testid="header-role-badge"
                    aria-label={`Role: ${auth.roles[0] ?? 'UNKNOWN'}`}
                    className={`px-2 py-0.5 text-xs font-medium rounded ${
                      auth.roles.includes('ADMIN')
                        ? 'bg-purple-100 text-purple-800 dark:bg-purple-900/40 dark:text-purple-300'
                        : auth.roles.includes('RISK_MANAGER')
                          ? 'bg-blue-100 text-blue-800 dark:bg-blue-900/40 dark:text-blue-300'
                          : auth.roles.includes('TRADER')
                            ? 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-300'
                            : auth.roles.includes('COMPLIANCE')
                              ? 'bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300'
                              : 'bg-slate-100 text-slate-800 dark:bg-slate-700 dark:text-slate-300'
                    }`}
                  >
                    {auth.roles[0]?.replace('_', ' ') ?? 'VIEWER'}
                  </span>
                  <span data-testid="header-username" className="text-sm text-slate-300">
                    {auth.username}
                  </span>
                  <button
                    data-testid="logout-button"
                    onClick={auth.logout}
                    className="p-1.5 rounded-md hover:bg-surface-800 transition-colors text-slate-300 hover:text-white"
                    aria-label="Log out"
                    title={`Log out ${auth.username}`}
                  >
                    <LogOut className="h-4 w-4" />
                  </button>
                </>
              )}
            </div>
          )}
        </div>
      </header>

      <nav className="bg-surface-800 px-6 flex gap-1 border-b border-surface-700 overflow-x-auto" data-testid="tab-bar" role="tablist" onKeyDown={handleTabKeyDown}>
        {TABS.map(({ key, label, icon: Icon, clusterStart }, index) => (
          <Fragment key={key}>
            {/* Plan §2.1: cluster divider sits between cluster boundaries.
                Presentational only — aria-hidden so screen readers ignore it
                and it does not appear in the tablist's tab order. */}
            {clusterStart && index > 0 && (
              <span
                aria-hidden="true"
                data-testid="tab-cluster-divider"
                className="self-stretch w-px my-2 ml-2 mr-2 bg-surface-700"
              />
            )}
            <button
              id={`tab-${key}`}
              data-testid={`tab-${key}`}
              ref={(el) => { if (el) tabRefs.current.set(key, el) }}
              role="tab"
              aria-selected={activeTab === key}
              tabIndex={activeTab === key ? 0 : -1}
              onClick={() => setActiveTab(key)}
              className={`flex items-center gap-1.5 px-4 py-2.5 text-sm font-medium border-b-2 -mb-px transition-colors ${
                activeTab === key
                  ? 'border-primary-500 text-white'
                  : 'border-transparent text-slate-400 hover:text-white'
              }`}
            >
              <Icon className="h-4 w-4" />
              <span>{label}</span>
              {key === 'alerts' && notifications.error && (
                <span
                  data-testid="alerts-error-dot"
                  className="ml-1 inline-block h-2 w-2 rounded-full bg-amber-400"
                  title="Alert monitoring unavailable"
                />
              )}
              {key === 'alerts' && !notifications.error && notifications.alerts.length > 0 && (
                <span
                  data-testid="alert-count-badge"
                  className="ml-1 px-1.5 py-0.5 bg-primary-500 text-white text-xs rounded-full"
                >
                  {notifications.alerts.length}
                </span>
              )}
              {key === 'system' && systemHealth.health?.status === 'DEGRADED' && (
                <span
                  data-testid="system-degraded-dot"
                  className="ml-1 inline-block h-2 w-2 rounded-full bg-red-500"
                />
              )}
            </button>
          </Fragment>
        ))}
      </nav>

      <DemoWelcomeStrip />

      <SystemStatusBanner
        exhausted={exhausted}
        reconnecting={reconnecting}
        maintenance={systemHealth.health?.status === 'DEGRADED'}
        systemHealthStatus={systemHealth.health?.status ?? null}
        disconnectElapsed={disconnectElapsed}
        onReconnect={manualReconnect}
      />

      <RiskTickerStrip
        bookId={effectiveBookId ?? bookId}
        bookSummary={hierarchySummary.summary}
        intradaySnapshot={intradayLatest}
        varResult={varResult}
        greeksResult={greeksResult}
        varLimit={varLimit}
        streamConnected={intradayConnected}
        onOpenHedgePanel={hedgeCtaActive ? openHedgePanel : undefined}
      />

      <BreachBanner
        activeTab={activeTab}
        varValue={varResult ? Number(varResult.varValue) : null}
        varLimit={varLimit}
        alerts={breachAlerts}
        onDismiss={dismissBreachAlert}
        onOpenHedgePanel={openHedgePanel}
        onViewAllAlerts={() => setActiveTab('alerts')}
      />

      <main className="flex-1 p-6 dark:bg-surface-900" role="tabpanel" aria-labelledby={`tab-${activeTab}`}>
        {(activeTab === 'positions' || activeTab === 'pnl' || activeTab === 'risk') && (
          <div
            data-testid="trader-filter-row"
            className="mb-3 flex items-center justify-end gap-3"
          >
            <TraderSelector
              traders={tradersState.traders}
              selectedTraderId={selectedTraderId}
              onChange={setSelectedTraderId}
              loading={tradersState.loading}
            />
          </div>
        )}
        {activeTab === 'system' ? (
          <SystemDashboard
            health={systemHealth.health}
            loading={systemHealth.loading}
            error={systemHealth.error}
            onRefresh={systemHealth.refresh}
          />
        ) : (
          <>
            {activeTab === 'positions' && (
              <>
                {loading && <p className="text-gray-500">Loading positions...</p>}
                {error && (
                  <div
                    data-testid="load-error-card"
                    className="bg-red-50 border border-red-200 rounded-lg p-4 flex items-start justify-between gap-4"
                    role="alert"
                  >
                    <div>
                      <p className="text-red-700 font-medium text-sm">
                        {error.includes('403') || error.includes('Forbidden') || error.includes('not permitted')
                          ? 'Access denied'
                          : 'Failed to load positions'}
                      </p>
                      <p className="text-red-600 text-sm mt-1">
                        {error.includes('403') || error.includes('Forbidden') || error.includes('not permitted')
                          ? 'You do not have access to this book. Contact your administrator.'
                          : error}
                      </p>
                    </div>
                    {!(error.includes('403') || error.includes('Forbidden') || error.includes('not permitted')) && (
                      <button
                        data-testid="retry-load-button"
                        onClick={retryInitialLoad}
                        className="flex-shrink-0 px-3 py-1.5 text-sm font-medium bg-red-100 hover:bg-red-200 text-red-800 rounded-md transition-colors"
                      >
                        Retry
                      </button>
                    )}
                  </div>
                )}
                {!loading && !error && (
                  <div>
                    <div className="flex items-center justify-between mb-4">
                      <div />
                      <button
                        data-testid="whatif-open-button"
                        onClick={() => setWhatIfOpen(true)}
                        className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium text-indigo-600 border border-indigo-300 rounded-md hover:bg-indigo-50 transition-colors"
                      >
                        <FlaskRound className="h-4 w-4" />
                        What-If
                      </button>
                    </div>
                    <div className="mb-4">
                      <BookSummaryCard
                        summary={hierarchySummary.summary}
                        baseCurrency={hierarchySummary.baseCurrency}
                        onBaseCurrencyChange={hierarchySummary.setBaseCurrency}
                        loading={hierarchySummary.loading}
                        title={hierarchySummary.summaryLabel}
                      />
                    </div>
                    <PositionGrid
                      positions={positions}
                      connected={connected}
                      reconnecting={reconnecting}
                      lastConnectedAt={lastConnectedAt}
                      positionRisk={positionRisk}
                      showBookColumn={hierarchy.selection.level !== 'book'}
                      bookId={effectiveBookId}
                      notesByInstrument={positionNotes.notesByInstrument}
                      onAddNote={(instrumentId, text) => positionNotes.createNote(instrumentId, text)}
                      onDeleteNote={(id) => positionNotes.deleteNote(id)}
                    />
                  </div>
                )}
              </>
            )}

            {activeTab === 'trades' && (
                  <div>
                    <SubTabBar
                      aria-label="Trades sections"
                      activeId={tradesSubTab}
                      onSelect={(id) =>
                        setTradesSubTab(id as 'blotter' | 'place' | 'cost' | 'reconciliation')
                      }
                      tabs={[
                        { id: 'blotter', label: 'Trade Blotter', testId: 'trades-subtab-blotter' },
                        { id: 'place', label: 'Place Order', testId: 'trades-subtab-place' },
                        { id: 'cost', label: 'Execution Cost', testId: 'trades-subtab-cost' },
                        {
                          id: 'reconciliation',
                          label: 'Reconciliation',
                          testId: 'trades-subtab-reconciliation',
                        },
                      ]}
                    />
                    {tradesSubTab === 'blotter' && (
                      <TradeBlotter
                        bookId={bookId}
                        initialCounterpartyFilter={tradesCounterpartyFilter}
                      />
                    )}
                    {tradesSubTab === 'place' && <PlaceOrderPanel bookId={bookId ?? ''} />}
                    {tradesSubTab === 'cost' && <ExecutionCostPanel bookId={bookId} />}
                    {tradesSubTab === 'reconciliation' && <ReconciliationPanel bookId={bookId} />}
                  </div>
                )}

                {activeTab === 'pnl' && (
                  <PnlTab bookId={bookId} />
                )}

                {activeTab === 'risk' && (
                  <RiskTab
                    bookId={bookId}
                    stressResults={stress.results}
                    stressLoading={stress.loading}
                    onRunStress={stress.run}
                    onViewStressDetails={() => setActiveTab('scenarios')}
                    onWhatIf={() => setWhatIfOpen(true)}
                    onOpenHedgePanel={openHedgePanel}
                    onViewPnlTab={() => setActiveTab('pnl')}
                    aggregatedView={hierarchy.selection.level !== 'book'}
                    effectiveBookIds={hierarchy.effectiveBookIds}
                    portfolioGroupId={hierarchy.selection.deskId ?? hierarchy.selection.divisionId ?? (hierarchy.selection.level === 'firm' ? 'firm' : null)}
                    hierarchyLevel={hierarchy.selection.level === 'firm' ? 'FIRM' : hierarchy.selection.level === 'division' ? 'DIVISION' : hierarchy.selection.level === 'desk' ? 'DESK' : null}
                    onNavigateToBook={(bid) => hierarchy.setSelection({ level: 'book', divisionId: hierarchy.selection.divisionId, deskId: hierarchy.selection.deskId, bookId: bid })}
                    activeScenario={activeScenario.scenario}
                    marketRegime={marketRegime.regime?.regime ?? null}
                    onShowAlerts={() => setActiveTab('alerts')}
                    initialValuationDate={riskInitialValuationDate}
                  />
                )}

                {activeTab === 'eod' && (
                  <EodTimelineTab bookId={effectiveBookId} />
                )}

                {activeTab === 'scenarios' && (
                  <ErrorBoundary fallback={<SectionErrorCard name="Scenarios" />}>
                    <ScenariosTab
                      bookId={bookId}
                      results={scenariosAll.results}
                      loading={scenariosAll.loading}
                      error={scenariosAll.error}
                      selectedScenario={scenariosAll.selectedScenario}
                      onSelectScenario={scenariosAll.setSelectedScenario}
                      confidenceLevel={scenariosAll.confidenceLevel}
                      onConfidenceLevelChange={scenariosAll.setConfidenceLevel}
                      timeHorizonDays={scenariosAll.timeHorizonDays}
                      onTimeHorizonDaysChange={scenariosAll.setTimeHorizonDays}
                      onRunAll={scenariosAll.runAll}
                      onAppendResult={scenariosAll.appendResult}
                    />
                  </ErrorBoundary>
                )}

                {activeTab === 'regulatory' && (
                  <ErrorBoundary fallback={<SectionErrorCard name="Regulatory" />}>
                    <RegulatoryTab bookId={bookId} />
                  </ErrorBoundary>
                )}

                {activeTab === 'counterparty-risk' && (
                  <CounterpartyRiskDashboard
                    onJumpToTrades={(counterpartyId) => {
                      // Cross-tab link (plan §2.4): focus the Trades blotter
                      // on the chosen counterparty, then switch tabs. The
                      // blotter sub-tab is reset to 'blotter' so the filter
                      // is actually visible.
                      setTradesCounterpartyFilter(counterpartyId)
                      setTradesSubTab('blotter')
                      setActiveTab('trades')
                    }}
                  />
                )}

                {activeTab === 'reports' && (
                  <ReportsTab
                    bookId={effectiveBookId}
                    onJumpToRiskAtDate={(reportBookId, valuationDate) => {
                      // Cross-tab link (plan §2.4): focus the hierarchy on
                      // the reported book, seed RiskTab's valuation date,
                      // then switch to the Risk tab. Empty valuationDate
                      // means "as of today", which maps to null in
                      // ValuationDatePicker.
                      hierarchy.setSelection({
                        level: 'book',
                        divisionId: hierarchy.selection.divisionId,
                        deskId: hierarchy.selection.deskId,
                        bookId: reportBookId,
                      })
                      setRiskInitialValuationDate(valuationDate || null)
                      setActiveTab('risk')
                    }}
                  />
                )}

                {activeTab === 'alerts' && (
                  <NotificationCenter
                    rules={notifications.rules}
                    alerts={notifications.alerts}
                    loading={notifications.loading}
                    error={notifications.error}
                    onCreateRule={notifications.createRule}
                    onDeleteRule={notifications.deleteRule}
                    onAcknowledge={notifications.acknowledgeAlert}
                    onEscalate={notifications.escalateAlert}
                    onResolve={notifications.resolveAlert}
                    onSnooze={notifications.snoozeAlert}
                    onJumpToRisk={(targetBookId) => {
                      // Cross-tab link (plan §2.4): focus the hierarchy on the
                      // alert's book so RiskTab opens scoped to it, then
                      // switch to the Risk tab.
                      if (targetBookId) {
                        hierarchy.setSelection({
                          level: 'book',
                          divisionId: hierarchy.selection.divisionId,
                          deskId: hierarchy.selection.deskId,
                          bookId: targetBookId,
                        })
                      }
                      setActiveTab('risk')
                    }}
                  />
                )}
          </>
        )}
      </main>

      <KeyboardShortcutsOverlay
        open={shortcutsOverlayOpen}
        onClose={closeShortcutsOverlay}
      />

      <CommandPalette
        open={commandPaletteOpen}
        onClose={() => setCommandPaletteOpen(false)}
        items={commandPaletteItems}
      />

      <HedgeRecommendationPanel
        open={hedgePanelOpen}
        onClose={() => setHedgePanelOpen(false)}
        bookId={effectiveBookId ?? bookId}
        recommendation={hedge.recommendation}
        loading={hedge.loading}
        error={hedge.error}
        onSuggest={hedge.suggest}
        onSendToWhatIf={() => setWhatIfOpen(true)}
      />

      <WhatIfPanel
        open={whatIfOpen}
        onClose={() => setWhatIfOpen(false)}
        trades={whatIf.trades}
        onAddTrade={whatIf.addTrade}
        onRemoveTrade={whatIf.removeTrade}
        onUpdateTrade={whatIf.updateTrade}
        onSubmit={whatIf.submit}
        onReset={() => { whatIf.reset(); rebalancing.resetRebalancing() }}
        result={whatIf.result}
        impact={whatIf.impact}
        loading={whatIf.loading}
        error={whatIf.error}
        errorTransient={whatIf.errorTransient}
        validationErrors={whatIf.validationErrors}
        onRetry={whatIf.retry}
        onCompareInDetail={() => {
          setWhatIfOpen(false)
          setActiveTab('risk')
        }}
        rebalancingResult={rebalancing.rebalancingResult}
        onRebalancingSubmit={() => {
          if (effectiveBookId) {
            rebalancing.submitRebalancing(effectiveBookId, whatIf.trades)
          }
        }}
        onApplyPreset={(preset) => {
          if (!positions || positions.length === 0) return
          if (preset === 'REDUCE_LARGEST') {
            const sorted = [...positions].sort((a, b) =>
              Math.abs(Number(b.marketValue.amount)) - Math.abs(Number(a.marketValue.amount)),
            )
            const largest = sorted[0]
            if (!largest) return
            const reduceQty = (Number(largest.quantity) * 0.25).toFixed(0)
            whatIf.reset()
            whatIf.addTrade()
            whatIf.updateTrade(0, 'instrumentId', largest.instrumentId)
            whatIf.updateTrade(0, 'assetClass', largest.assetClass)
            whatIf.updateTrade(0, 'side', Number(largest.quantity) > 0 ? 'SELL' : 'BUY')
            whatIf.updateTrade(0, 'quantity', reduceQty)
            whatIf.updateTrade(0, 'priceAmount', largest.marketPrice.amount)
          } else if (preset === 'FLATTEN_DELTA') {
            // Use the first position as a proxy — in practice this needs the net delta
            const firstEquity = positions.find((p) => p.assetClass === 'EQUITY')
            if (!firstEquity) return
            whatIf.reset()
            whatIf.addTrade()
            whatIf.updateTrade(0, 'instrumentId', firstEquity.instrumentId)
            whatIf.updateTrade(0, 'assetClass', 'EQUITY')
            whatIf.updateTrade(0, 'side', Number(firstEquity.quantity) > 0 ? 'SELL' : 'BUY')
            whatIf.updateTrade(0, 'quantity', Math.abs(Number(firstEquity.quantity)).toFixed(0))
            whatIf.updateTrade(0, 'priceAmount', firstEquity.marketPrice.amount)
          }
        }}
      />
    </div>
  )
}

export default App

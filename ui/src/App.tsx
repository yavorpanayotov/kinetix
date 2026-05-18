import { useEffect, useRef, useState } from 'react'
import { Activity, BarChart3, ScrollText, TrendingUp, Shield, FlaskConical, Scale, Bell, Server, FlaskRound, Sun, Moon, Save, CalendarDays, Users, FileText, LogOut } from 'lucide-react'
import { ErrorBoundary, SectionErrorCard } from './components/ErrorBoundary'
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
import { CounterpartyRiskDashboard } from './components/CounterpartyRiskDashboard'
import { ReportsTab } from './components/ReportsTab'
import { EodTimelineTab } from './components/EodTimelineTab'
import { BookSummaryCard } from './components/BookSummaryCard'
import { RiskTickerStrip } from './components/RiskTickerStrip'
import { BreachBanner } from './components/BreachBanner'
import { SystemStatusBanner } from './components/SystemStatusBanner'
import { usePositions } from './hooks/usePositions'
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
import { useAuth } from './auth/useAuth'
import { DEMO_MODE } from './auth/demoPersonas'
import { PersonaSwitcher } from './components/PersonaSwitcher'
import { DemoWelcomeStrip } from './components/DemoWelcomeStrip'
import { KeyboardShortcutsOverlay } from './components/KeyboardShortcutsOverlay'

type Tab = 'positions' | 'trades' | 'pnl' | 'risk' | 'eod' | 'scenarios' | 'regulatory' | 'counterparty-risk' | 'reports' | 'alerts' | 'system'

const TABS: { key: Tab; label: string; icon: typeof Activity }[] = [
  { key: 'positions', label: 'Positions', icon: BarChart3 },
  { key: 'trades', label: 'Trades', icon: ScrollText },
  { key: 'pnl', label: 'P&L', icon: TrendingUp },
  { key: 'risk', label: 'Risk', icon: Shield },
  { key: 'eod', label: 'EOD History', icon: CalendarDays },
  { key: 'scenarios', label: 'Scenarios', icon: FlaskConical },
  { key: 'regulatory', label: 'Regulatory', icon: Scale },
  { key: 'counterparty-risk', label: 'Counterparty Risk', icon: Users },
  { key: 'reports', label: 'Reports', icon: FileText },
  { key: 'alerts', label: 'Alerts', icon: Bell },
  { key: 'system', label: 'System', icon: Server },
]

function App() {
  const workspace = useWorkspace()
  const auth = useAuth()
  const [activeTab, setActiveTab] = useState<Tab>(
    (workspace.preferences.defaultTab as Tab) || 'positions',
  )
  const [whatIfOpen, setWhatIfOpen] = useState(false)
  const [tradesSubTab, setTradesSubTab] = useState<'blotter' | 'place' | 'cost' | 'reconciliation'>('blotter')
  const [shortcutsOverlayOpen, setShortcutsOverlayOpen] = useState(false)
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

  const bookId = hierarchy.effectiveBookId ?? (isAllSelected ? ALL_BOOKS : rawBookId)
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
  const { isDark, toggle: toggleTheme } = useTheme()
  const dataQuality = useDataQuality()
  const marketRegime = useMarketRegime()
  const activeScenario = useActiveScenario()
  const tapeReplay = useTapeReplayStatus()
  const tradersState = useTraders()
  const [selectedTraderId, setSelectedTraderId] = useState<string | null>(null)

  const [disconnectElapsed, setDisconnectElapsed] = useState(0)
  useEffect(() => {
    if (!disconnectedSince) return
    const update = () => setDisconnectElapsed(Math.floor((Date.now() - disconnectedSince.getTime()) / 1000))
    update()
    const timer = setInterval(update, 1000)
    return () => {
      clearInterval(timer)
      setDisconnectElapsed(0)
    }
  }, [disconnectedSince])

  return (
    <div className="min-h-screen bg-surface-50 dark:bg-surface-900 dark:text-slate-100 flex flex-col">
      <header className="bg-surface-900 text-white px-4 md:px-6 py-3 flex items-center justify-between">
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
        <div className="flex items-center gap-2 lg:gap-3" data-testid="header-right-cluster">
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
          />
          {!DEMO_MODE && (
            <button
              data-testid="save-workspace-button"
              onClick={() => {
                workspace.updatePreference('defaultTab', activeTab)
                workspace.updatePreference('defaultBook', bookId)
              }}
              className="hidden sm:block p-1.5 rounded-md hover:bg-surface-800 transition-colors text-slate-300 hover:text-white"
              aria-label="Save workspace"
              title="Save current tab and book as defaults"
            >
              <Save className="h-4 w-4" />
            </button>
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

      <nav className="bg-surface-800 px-4 md:px-6 flex gap-1 border-b border-surface-700 overflow-x-auto" data-testid="tab-bar" role="tablist" onKeyDown={handleTabKeyDown}>
        {TABS.map(({ key, label, icon: Icon }) => (
          <button
            key={key}
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
            <span className="hidden md:inline">{label}</span>
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
      />

      <BreachBanner
        activeTab={activeTab}
        varValue={varResult ? Number(varResult.varValue) : null}
        varLimit={varLimit}
        alerts={breachAlerts}
        onDismiss={dismissBreachAlert}
      />

      <main className="flex-1 p-4 md:p-6 dark:bg-surface-900" role="tabpanel" aria-labelledby={`tab-${activeTab}`}>
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
                    />
                  </div>
                )}
              </>
            )}

            {activeTab === 'trades' && (
                  <div>
                    <div className="flex gap-1 mb-4 border-b border-slate-200 dark:border-surface-700" role="tablist" aria-label="Trades sections">
                      {(['blotter', 'place', 'cost', 'reconciliation'] as const).map((subTab) => (
                        <button
                          key={subTab}
                          role="tab"
                          aria-selected={tradesSubTab === subTab}
                          data-testid={`trades-subtab-${subTab}`}
                          onClick={() => setTradesSubTab(subTab)}
                          className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
                            tradesSubTab === subTab
                              ? 'border-primary-500 text-primary-600 dark:text-primary-400'
                              : 'border-transparent text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300'
                          }`}
                        >
                          {subTab === 'blotter' && 'Trade Blotter'}
                          {subTab === 'place' && 'Place Order'}
                          {subTab === 'cost' && 'Execution Cost'}
                          {subTab === 'reconciliation' && 'Reconciliation'}
                        </button>
                      ))}
                    </div>
                    {tradesSubTab === 'blotter' && <TradeBlotter bookId={bookId} />}
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
                    onViewPnlTab={() => setActiveTab('pnl')}
                    aggregatedView={hierarchy.selection.level !== 'book'}
                    effectiveBookIds={hierarchy.effectiveBookIds}
                    bookGroupId={hierarchy.selection.deskId ?? hierarchy.selection.divisionId ?? (hierarchy.selection.level === 'firm' ? 'firm' : null)}
                    hierarchyLevel={hierarchy.selection.level === 'firm' ? 'FIRM' : hierarchy.selection.level === 'division' ? 'DIVISION' : hierarchy.selection.level === 'desk' ? 'DESK' : null}
                    onNavigateToBook={(bid) => hierarchy.setSelection({ level: 'book', divisionId: hierarchy.selection.divisionId, deskId: hierarchy.selection.deskId, bookId: bid })}
                    activeScenario={activeScenario.scenario}
                    marketRegime={marketRegime.regime?.regime ?? null}
                    onShowAlerts={() => setActiveTab('alerts')}
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
                  <CounterpartyRiskDashboard />
                )}

                {activeTab === 'reports' && (
                  <ReportsTab bookId={effectiveBookId} />
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

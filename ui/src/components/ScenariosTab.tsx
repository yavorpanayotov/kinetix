import { useState, useCallback, useEffect, useMemo } from 'react'
import { Zap } from 'lucide-react'
import type { StressTestResultDto, HistoricalReplayResultDto, ReverseStressResultDto, ReverseStressRequestDto } from '../types'
import { runStressTest } from '../api/stress'
import { createScenario, submitScenario } from '../api/scenarios'
import { runHistoricalReplay, runReverseStress } from '../api/historicalReplay'
import { exportStressResultsToCsv } from '../utils/exportStressResults'
import type { ScenarioSavePayload, ScenarioRunPayload } from '../hooks/useCustomScenario'
import { useScenarioGovernance } from '../hooks/useScenarioGovernance'
import { Card, Spinner, ErrorCard } from './ui'
import { ScenarioControlBar } from './ScenarioControlBar'
import { ScenarioComparisonTable } from './ScenarioComparisonTable'
import { ScenarioDetailPanel } from './ScenarioDetailPanel'
import { ScenarioComparisonView } from './ScenarioComparisonView'
import { ScenarioGovernancePanel } from './ScenarioGovernancePanel'
import { CustomScenarioBuilder } from './CustomScenarioBuilder'
import { ScenarioLibraryGrid } from './ScenarioLibraryGrid'
import { HistoricalReplayPanel } from './HistoricalReplayPanel'
import { ReverseStressDialog } from './ReverseStressDialog'

export interface ScenariosTabProps {
  bookId: string | null
  results: StressTestResultDto[]
  loading: boolean
  error: string | null
  selectedScenario: string | null
  onSelectScenario: (scenario: string | null) => void
  confidenceLevel: string
  onConfidenceLevelChange: (cl: string) => void
  timeHorizonDays: string
  onTimeHorizonDaysChange: (days: string) => void
  onRunAll: () => void
  onAppendResult: (result: StressTestResultDto) => void
}

export function ScenariosTab({
  bookId,
  results,
  loading,
  error,
  selectedScenario,
  onSelectScenario,
  confidenceLevel,
  onConfidenceLevelChange,
  timeHorizonDays,
  onTimeHorizonDaysChange,
  onRunAll,
  onAppendResult,
}: ScenariosTabProps) {
  const [builderOpen, setBuilderOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [running, setRunning] = useState(false)
  const [checkedScenarios, setCheckedScenarios] = useState<Set<string>>(new Set())
  const [showComparison, setShowComparison] = useState(false)
  const [showGovernance, setShowGovernance] = useState(false)
  const governance = useScenarioGovernance()

  // Historical Replay state
  const [replayScenario, setReplayScenario] = useState<string>('')
  const [replayResult, setReplayResult] = useState<HistoricalReplayResultDto | null>(null)
  const [replayLoading, setReplayLoading] = useState(false)
  const [replayError, setReplayError] = useState<string | null>(null)

  // Reverse Stress state
  const [reverseStressOpen, setReverseStressOpen] = useState(false)
  const [reverseStressResult, setReverseStressResult] = useState<ReverseStressResultDto | null>(null)
  const [reverseStressLoading, setReverseStressLoading] = useState(false)
  const [reverseStressError, setReverseStressError] = useState<string | null>(null)

  // Lazy-loaded full detail for the expanded scenario row.
  //
  // The batch (Run All) endpoint returns slim summaries — only
  // ``scenarioName``, ``baseVar``, ``stressedVar``, ``pnlImpact``. Drill-down
  // needs the full ``StressTestResultDto`` (asset-class impacts, position
  // impacts, Greeks, limit breaches), which the single-scenario route
  // provides. We fetch on expand rather than fattening the batch payload so
  // the comparison view stays cheap even with 20+ scenarios.
  const [detailResult, setDetailResult] = useState<StressTestResultDto | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [detailError, setDetailError] = useState<string | null>(null)

  const historicalScenarioNames = useMemo(
    () =>
      governance.scenarios
        .filter((s) => s.scenarioType === 'HISTORICAL_REPLAY' && s.status === 'APPROVED')
        .map((s) => s.name),
    [governance.scenarios],
  )

  const handleToggleCheck = useCallback((scenario: string) => {
    setCheckedScenarios((prev) => {
      const next = new Set(prev)
      if (next.has(scenario)) {
        next.delete(scenario)
      } else if (next.size < 3) {
        next.add(scenario)
      }
      return next
    })
  }, [])

  const handleCompare = useCallback(() => {
    setShowComparison(true)
  }, [])

  const handleSave = useCallback(
    async (payload: ScenarioSavePayload) => {
      setSaving(true)
      try {
        const shocks = JSON.stringify({
          volShocks: payload.volShocks,
          priceShocks: payload.priceShocks,
        })
        const scenario = await createScenario({
          name: payload.name,
          description: payload.description,
          shocks,
          createdBy: 'user',
        })
        await submitScenario(scenario.id)
        setBuilderOpen(false)
      } finally {
        setSaving(false)
      }
    },
    [],
  )

  const handleRunAdHoc = useCallback(
    async (payload: ScenarioRunPayload) => {
      if (!bookId) return
      setRunning(true)
      try {
        const result = await runStressTest(bookId, 'AD_HOC', {
          volShocks: payload.volShocks,
          priceShocks: payload.priceShocks,
          confidenceLevel,
          timeHorizonDays,
        })
        if (result) {
          onAppendResult(result)
        }
      } finally {
        setRunning(false)
      }
    },
    [bookId, confidenceLevel, timeHorizonDays, onAppendResult],
  )

  const handleRunReplay = useCallback(async () => {
    const effectiveScenario = replayScenario || historicalScenarioNames[0]
    if (!bookId || !effectiveScenario) return
    setReplayLoading(true)
    setReplayError(null)
    try {
      const result = await runHistoricalReplay(bookId, { instrumentReturns: [], scenarioName: effectiveScenario })
      setReplayResult(result)
    } catch (err) {
      setReplayError(err instanceof Error ? err.message : 'Historical replay failed')
    } finally {
      setReplayLoading(false)
    }
  }, [bookId, replayScenario, historicalScenarioNames])

  // Fire the single-scenario fetch whenever the user opens a row. The
  // ``confidenceLevel`` / ``timeHorizonDays`` controls are part of the
  // dependency list so re-running with different parameters refreshes the
  // detail panel for the open row.
  useEffect(() => {
    if (!bookId || !selectedScenario) {
      setDetailResult(null)
      setDetailError(null)
      setDetailLoading(false)
      return
    }
    let cancelled = false
    setDetailLoading(true)
    setDetailError(null)
    runStressTest(bookId, selectedScenario, {
      confidenceLevel,
      timeHorizonDays,
    })
      .then((data) => {
        if (cancelled) return
        setDetailResult(data)
      })
      .catch((err) => {
        if (cancelled) return
        setDetailError(err instanceof Error ? err.message : String(err))
      })
      .finally(() => {
        if (cancelled) return
        setDetailLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [bookId, selectedScenario, confidenceLevel, timeHorizonDays])

  const handleRunReverseStress = useCallback(async (request: ReverseStressRequestDto) => {
    if (!bookId) return
    setReverseStressLoading(true)
    setReverseStressError(null)
    try {
      const result = await runReverseStress(bookId, request)
      setReverseStressResult(result)
    } catch (err) {
      setReverseStressError(err instanceof Error ? err.message : 'Reverse stress failed')
    } finally {
      setReverseStressLoading(false)
    }
  }, [bookId])

  const comparedScenarios = results.filter((r) => checkedScenarios.has(r.scenarioName))

  return (
    <>
      <Card
        data-testid="scenarios-tab"
        header={
          <span className="flex items-center gap-1.5">
            <Zap className="h-4 w-4" />
            Stress Testing
          </span>
        }
      >
        <ScenarioControlBar
          onRunAll={onRunAll}
          loading={loading}
          confidenceLevel={confidenceLevel}
          onConfidenceLevelChange={onConfidenceLevelChange}
          timeHorizonDays={timeHorizonDays}
          onTimeHorizonDaysChange={onTimeHorizonDaysChange}
          onCustomScenario={() => setBuilderOpen(true)}
          compareCount={checkedScenarios.size}
          onCompare={handleCompare}
          onExportCsv={results.length > 0 ? () => exportStressResultsToCsv(results) : undefined}
          onManageScenarios={() => setShowGovernance((v) => !v)}
          onReverseStress={() => setReverseStressOpen(true)}
        />

        {loading && (
          <div data-testid="stress-loading" className="flex items-center gap-2 text-slate-500 text-sm mb-4">
            <Spinner size="sm" />
            Running all stress scenarios...
          </div>
        )}

        {error && (
          <div className="mb-4">
            <ErrorCard message={error} data-testid="stress-error" />
          </div>
        )}

        <ScenarioComparisonTable
          results={results}
          selectedScenario={selectedScenario}
          onSelectScenario={onSelectScenario}
          checkedScenarios={checkedScenarios}
          onToggleCheck={handleToggleCheck}
          scenarioMetadata={governance.scenarios}
        />

        {showComparison && comparedScenarios.length >= 2 && (
          <ScenarioComparisonView scenarios={comparedScenarios} />
        )}

        {showGovernance && (
          <>
            <ScenarioLibraryGrid
              scenarios={governance.scenarios}
              loading={governance.loading}
              error={governance.error}
            />
            <ScenarioGovernancePanel
              scenarios={governance.scenarios}
              onSubmit={governance.submit}
              onApprove={governance.approve}
              onRetire={governance.retire}
              loading={governance.loading}
              error={governance.error}
            />
          </>
        )}

        {selectedScenario && detailLoading && (
          <div
            data-testid="scenario-detail-loading"
            className="mt-4 border-t pt-4 flex items-center gap-2 text-slate-500 text-sm"
          >
            <Spinner size="sm" />
            Loading scenario details...
          </div>
        )}
        {selectedScenario && !detailLoading && detailError && (
          <div className="mt-4 border-t pt-4">
            <ErrorCard message={detailError} data-testid="scenario-detail-error" />
          </div>
        )}
        {selectedScenario && !detailLoading && !detailError && (
          <ScenarioDetailPanel result={detailResult} />
        )}
      </Card>

      <CustomScenarioBuilder
        open={builderOpen}
        onClose={() => setBuilderOpen(false)}
        onSave={handleSave}
        onRunAdHoc={handleRunAdHoc}
        saving={saving}
        running={running}
      />

      {historicalScenarioNames.length > 0 && (
        <HistoricalReplayPanel
          scenarios={historicalScenarioNames}
          result={replayResult}
          loading={replayLoading}
          error={replayError}
          selectedScenario={replayScenario || historicalScenarioNames[0]}
          onScenarioChange={setReplayScenario}
          onRun={handleRunReplay}
          bookId={bookId}
        />
      )}

      <ReverseStressDialog
        open={reverseStressOpen}
        onClose={() => setReverseStressOpen(false)}
        onRun={handleRunReverseStress}
        result={reverseStressResult}
        loading={reverseStressLoading}
        error={reverseStressError}
      />
    </>
  )
}

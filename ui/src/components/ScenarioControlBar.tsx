import { Zap, BarChart3, Settings, Download, RotateCcw } from 'lucide-react'
import { Button, Select } from './ui'

interface ScenarioControlBarProps {
  onRunAll: () => void
  loading: boolean
  confidenceLevel: string
  onConfidenceLevelChange: (cl: string) => void
  timeHorizonDays: string
  onTimeHorizonDaysChange: (days: string) => void
  onCustomScenario?: () => void
  compareCount?: number
  onCompare?: () => void
  onManageScenarios?: () => void
  onExportCsv?: () => void
  onReverseStress?: () => void
  hasResults?: boolean
}

export function ScenarioControlBar({
  onRunAll,
  loading,
  confidenceLevel,
  onConfidenceLevelChange,
  timeHorizonDays,
  onTimeHorizonDaysChange,
  onCustomScenario,
  compareCount = 0,
  onCompare,
  onManageScenarios,
  onExportCsv,
  onReverseStress,
  hasResults = false,
}: ScenarioControlBarProps) {
  const canCompare = compareCount >= 2 && compareCount <= 3

  return (
    <div data-testid="scenario-control-bar" className="flex items-center flex-wrap gap-3 mb-4">
      <Button
        data-testid="run-all-btn"
        variant="danger"
        size="md"
        icon={<Zap className="h-3.5 w-3.5" />}
        onClick={onRunAll}
        loading={loading}
      >
        {loading ? 'Running...' : 'Run All Scenarios'}
      </Button>

      <Select
        data-testid="confidence-level-select"
        value={confidenceLevel}
        onChange={(e) => onConfidenceLevelChange(e.target.value)}
        aria-label="Confidence level"
      >
        <option value="CL_95">95% CL</option>
        <option value="CL_975">97.5% CL</option>
        <option value="CL_99">99% CL</option>
      </Select>

      <Select
        data-testid="time-horizon-select"
        value={timeHorizonDays}
        onChange={(e) => onTimeHorizonDaysChange(e.target.value)}
        aria-label="Time horizon"
      >
        <option value="1">1 Day</option>
        <option value="5">5 Days</option>
        <option value="10">10 Days</option>
      </Select>

      {onCustomScenario && (
        <Button
          data-testid="custom-scenario-btn"
          variant="primary"
          size="md"
          onClick={onCustomScenario}
        >
          + Custom Scenario
        </Button>
      )}

      {onReverseStress && (
        <Button
          data-testid="reverse-stress-btn"
          variant="secondary"
          size="md"
          icon={<RotateCcw className="h-3.5 w-3.5" />}
          onClick={onReverseStress}
          disabled={!hasResults}
          title={
            hasResults
              ? 'Find market scenarios that breach your loss threshold'
              : 'Run all scenarios first to enable reverse stress'
          }
        >
          Reverse Stress
        </Button>
      )}

      {onCompare && canCompare && (
        <Button
          data-testid="compare-btn"
          variant="secondary"
          size="md"
          icon={<BarChart3 className="h-3.5 w-3.5" />}
          onClick={onCompare}
        >
          Compare ({compareCount})
        </Button>
      )}

      {onExportCsv && (
        <Button
          data-testid="export-csv-btn"
          variant="secondary"
          size="md"
          icon={<Download className="h-3.5 w-3.5" />}
          onClick={onExportCsv}
        >
          Export CSV
        </Button>
      )}

      {onManageScenarios && (
        <Button
          data-testid="manage-scenarios-btn"
          variant="secondary"
          size="md"
          icon={<Settings className="h-3.5 w-3.5" />}
          onClick={onManageScenarios}
          title="View and manage scenario governance — submit, approve, and retire scenarios"
        >
          Manage Scenarios
        </Button>
      )}
    </div>
  )
}

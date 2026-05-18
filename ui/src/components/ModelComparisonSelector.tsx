import { useState } from 'react'
import { Button } from './ui'
import type { ModelComparisonRequestDto } from '../types'

interface ModelComparisonSelectorProps {
  loading: boolean
  onCompare: (request: ModelComparisonRequestDto) => void
}

const CALC_TYPE_OPTIONS = [
  { value: 'PARAMETRIC', label: 'Parametric' },
  { value: 'HISTORICAL', label: 'Historical' },
  { value: 'MONTE_CARLO', label: 'Monte Carlo' },
]

const CONF_LEVEL_OPTIONS = [
  { value: 'CL_95', label: '95%' },
  { value: 'CL_99', label: '99%' },
]

const selectClass =
  'w-full border border-slate-300 dark:border-surface-600 rounded-md px-3 py-1.5 text-sm bg-white dark:bg-surface-700 dark:text-slate-200 focus:ring-2 focus:ring-primary-500 focus:border-primary-500'

export function ModelComparisonSelector({ loading, onCompare }: ModelComparisonSelectorProps) {
  const [baseCalcType, setBaseCalcType] = useState('PARAMETRIC')
  const [baseConfLevel, setBaseConfLevel] = useState('CL_95')
  const [targetCalcType, setTargetCalcType] = useState('MONTE_CARLO')
  const [targetConfLevel, setTargetConfLevel] = useState('CL_99')
  const [targetNumSims, setTargetNumSims] = useState('10000')

  const handleSubmit = () => {
    onCompare({
      calculationType: baseCalcType,
      confidenceLevel: baseConfLevel,
      targetCalculationType: targetCalcType,
      targetConfidenceLevel: targetConfLevel,
      targetNumSimulations: Number(targetNumSims) || undefined,
    })
  }

  return (
    <div data-testid="model-comparison-selector" className="space-y-4">
      <div className="grid grid-cols-2 gap-4">
        {/* Base model */}
        <fieldset className="space-y-2">
          <legend className="text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider">
            Base Model
          </legend>
          <div>
            <label
              htmlFor="base-calc-type"
              className="block text-xs text-slate-500 dark:text-slate-400 mb-1"
            >
              Calculation Type
            </label>
            <select
              id="base-calc-type"
              data-testid="base-calc-type"
              value={baseCalcType}
              onChange={(e) => setBaseCalcType(e.target.value)}
              className={selectClass}
              aria-label="Base calculation type"
            >
              {CALC_TYPE_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label
              htmlFor="base-conf-level"
              className="block text-xs text-slate-500 dark:text-slate-400 mb-1"
            >
              Confidence Level
            </label>
            <select
              id="base-conf-level"
              data-testid="base-conf-level"
              value={baseConfLevel}
              onChange={(e) => setBaseConfLevel(e.target.value)}
              className={selectClass}
              aria-label="Base confidence level"
            >
              {CONF_LEVEL_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </div>
        </fieldset>

        {/* Target model */}
        <fieldset className="space-y-2">
          <legend className="text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider">
            Target Model
          </legend>
          <div>
            <label
              htmlFor="target-calc-type"
              className="block text-xs text-slate-500 dark:text-slate-400 mb-1"
            >
              Calculation Type
            </label>
            <select
              id="target-calc-type"
              data-testid="target-calc-type"
              value={targetCalcType}
              onChange={(e) => setTargetCalcType(e.target.value)}
              className={selectClass}
              aria-label="Target calculation type"
            >
              {CALC_TYPE_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label
              htmlFor="target-conf-level"
              className="block text-xs text-slate-500 dark:text-slate-400 mb-1"
            >
              Confidence Level
            </label>
            <select
              id="target-conf-level"
              data-testid="target-conf-level"
              value={targetConfLevel}
              onChange={(e) => setTargetConfLevel(e.target.value)}
              className={selectClass}
              aria-label="Target confidence level"
            >
              {CONF_LEVEL_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label
              htmlFor="target-num-sims"
              className="block text-xs text-slate-500 dark:text-slate-400 mb-1"
            >
              Simulations
            </label>
            <input
              id="target-num-sims"
              data-testid="target-num-sims"
              type="number"
              value={targetNumSims}
              onChange={(e) => setTargetNumSims(e.target.value)}
              className={selectClass}
              placeholder="10000"
              min="1000"
              step="1000"
              aria-label="Number of Monte Carlo simulations for target"
            />
          </div>
        </fieldset>
      </div>

      <Button
        data-testid="run-model-comparison"
        variant="primary"
        onClick={handleSubmit}
        loading={loading}
        disabled={loading}
        aria-label="Run both models and compare results"
      >
        Run Both &amp; Compare
      </Button>
    </div>
  )
}

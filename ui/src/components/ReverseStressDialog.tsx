import { useState, useEffect } from 'react'
import { X, Target } from 'lucide-react'
import type { ReverseStressRequestDto, ReverseStressResultDto } from '../types'
import { formatCurrency } from '../utils/format'
import { Button, ErrorCard, Input, Spinner } from './ui'

interface ReverseStressDialogProps {
  open: boolean
  onClose: () => void
  onRun: (request: ReverseStressRequestDto) => void
  result: ReverseStressResultDto | null
  loading: boolean
  error: string | null
}

export function ReverseStressDialog({
  open,
  onClose,
  onRun,
  result,
  loading,
  error,
}: ReverseStressDialogProps) {
  const [targetLoss, setTargetLoss] = useState('100000')
  const [maxShock, setMaxShock] = useState('-1.0')

  useEffect(() => {
    if (!open) return

    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        onClose()
      }
    }

    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [open, onClose])

  if (!open) return null

  function handleRun() {
    const parsedTargetLoss = parseFloat(targetLoss) || 0
    const parsedMaxShock = parseFloat(maxShock) || -1.0
    onRun({ targetLoss: parsedTargetLoss, maxShock: parsedMaxShock })
  }

  return (
    <>
      <div
        data-testid="reverse-stress-backdrop"
        className="fixed inset-0 z-40 bg-black/30"
        onClick={onClose}
      />
      <div
        data-testid="reverse-stress-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="reverse-stress-title"
        className="fixed top-0 right-0 h-full w-[400px] bg-white dark:bg-surface-800 border-l border-slate-200 dark:border-surface-700 shadow-xl z-50 flex flex-col"
      >
        {/* Header */}
        <div className="flex items-center justify-between px-4 py-3 border-b border-slate-200 dark:border-surface-700 bg-slate-50 dark:bg-surface-900">
          <h2
            id="reverse-stress-title"
            className="text-sm font-bold text-slate-800 dark:text-slate-200 flex items-center gap-1.5"
          >
            <Target className="h-4 w-4" />
            Reverse Stress Test
          </h2>
          <button
            data-testid="reverse-stress-close"
            aria-label="Close reverse stress dialog"
            onClick={onClose}
            className="p-1 rounded hover:bg-slate-200 dark:hover:bg-surface-700 transition-colors"
          >
            <X className="h-4 w-4 text-slate-500 dark:text-slate-400" />
          </button>
        </div>

        {/* Content */}
        <div className="flex-1 overflow-y-auto px-4 py-4 space-y-4">
          <p className="text-sm text-slate-500 dark:text-slate-400">
            Find the minimum shocks required to reach a target loss. The engine solves for the
            smallest uniform shock vector that achieves the specified P&L impact.
          </p>

          <div className="space-y-3">
            <div>
              <label
                htmlFor="reverse-stress-target-loss-input"
                className="block text-xs text-slate-500 dark:text-slate-400 mb-1"
              >
                Target loss (absolute value, USD)
              </label>
              <Input
                id="reverse-stress-target-loss-input"
                data-testid="reverse-stress-target-loss"
                type="number"
                min="1"
                step="1000"
                value={targetLoss}
                onChange={(e) => setTargetLoss(e.target.value)}
                placeholder="e.g. 100000"
                className="w-full"
              />
            </div>

            <div>
              <label
                htmlFor="reverse-stress-max-shock-input"
                className="block text-xs text-slate-500 dark:text-slate-400 mb-1"
              >
                Maximum shock (negative fraction, default -1.0 = -100%)
              </label>
              <Input
                id="reverse-stress-max-shock-input"
                data-testid="reverse-stress-max-shock"
                type="number"
                min="-1.0"
                max="-0.01"
                step="0.01"
                value={maxShock}
                onChange={(e) => setMaxShock(e.target.value)}
                placeholder="e.g. -1.0"
                className="w-full"
              />
            </div>
          </div>

          <Button
            data-testid="reverse-stress-run-btn"
            variant="primary"
            onClick={handleRun}
            loading={loading}
            disabled={loading || !targetLoss}
            icon={<Target className="h-3.5 w-3.5" />}
            className="w-full"
          >
            {loading ? 'Solving...' : 'Run Reverse Stress'}
          </Button>

          {loading && (
            <div
              data-testid="reverse-stress-loading"
              className="flex items-center gap-2 text-slate-500 text-sm"
            >
              <Spinner size="sm" />
              Solving for minimum shock vector...
            </div>
          )}

          {error && <ErrorCard message={error} data-testid="reverse-stress-error" />}

          {result && !loading && (
            <div data-testid="reverse-stress-results" className="space-y-3">
              {result.converged ? (
                <div
                  data-testid="reverse-stress-converged"
                  className="bg-emerald-50 dark:bg-emerald-950 border border-emerald-200 dark:border-emerald-800 rounded p-3 text-sm text-emerald-800 dark:text-emerald-300"
                >
                  Solution converged
                </div>
              ) : (
                <div
                  data-testid="reverse-stress-not-converged"
                  className="bg-amber-50 dark:bg-amber-950 border border-amber-200 dark:border-amber-800 rounded p-3 text-sm text-amber-800 dark:text-amber-300"
                >
                  Did not converge — maximum shock constraint reached. Results below show the best
                  achieved loss.
                </div>
              )}

              <div className="grid grid-cols-2 gap-3">
                <div className="bg-slate-50 dark:bg-slate-800 rounded p-3">
                  <p className="text-xs text-slate-500 mb-0.5">Target Loss</p>
                  <p className="font-semibold text-slate-900 dark:text-slate-100 text-sm">
                    {formatCurrency(Number(result.targetLoss))}
                  </p>
                </div>
                <div className="bg-red-50 dark:bg-red-950 rounded p-3">
                  <p className="text-xs text-slate-500 mb-0.5">Achieved Loss</p>
                  <p
                    data-testid="reverse-stress-achieved-loss"
                    className="font-semibold text-red-600 dark:text-red-400 text-sm"
                  >
                    {formatCurrency(Number(result.achievedLoss))}
                  </p>
                </div>
              </div>

              <div>
                <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-300 mb-2">
                  Required Shocks
                </h3>
                <div data-testid="reverse-stress-shock-table" className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="border-b text-left text-slate-600 dark:text-slate-400">
                        <th className="py-2 pr-2">Instrument</th>
                        <th className="py-2 text-right">Shock</th>
                      </tr>
                    </thead>
                    <tbody>
                      {result.shocks.map((shock) => (
                        <tr
                          key={shock.instrumentId}
                          className="border-b hover:bg-slate-50 dark:hover:bg-slate-800 transition-colors"
                        >
                          <td className="py-1.5 pr-2 font-medium">{shock.instrumentId}</td>
                          <td className="py-1.5 text-right text-red-600 font-medium">
                            {(Number(shock.shock) * 100).toFixed(1)}%
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          )}
        </div>
      </div>
    </>
  )
}

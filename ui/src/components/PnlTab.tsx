import { useState, useCallback, useMemo } from 'react'
import { TrendingUp, Download } from 'lucide-react'
import { usePnlAttribution } from '../hooks/usePnlAttribution'
import { useSodBaseline } from '../hooks/useSodBaseline'
import { useIntradayPnlStream } from '../hooks/useIntradayPnlStream'
import { useIntradayPnlSeries } from '../hooks/useIntradayPnlSeries'
import { IntradayPnlChart } from './IntradayPnlChart'
import { PnlWaterfallChart } from './PnlWaterfallChart'
import { PnlAttributionTable } from './PnlAttributionTable'
import { BenchmarkAttributionSection } from './BenchmarkAttributionSection'
import { SodBaselineIndicator } from './SodBaselineIndicator'
import { JobPickerDialog } from './JobPickerDialog'
import { ConfirmDialog } from './ui/ConfirmDialog'
import { Button } from './ui/Button'
import { Card } from './ui/Card'
import { EmptyState } from './ui/EmptyState'
import { ErrorCard } from './ui/ErrorCard'
import { Spinner } from './ui/Spinner'
import { formatTimestamp } from '../utils/format'
import { exportToCsv } from '../utils/exportCsv'
import type { PnlAttributionDto } from '../types'

interface PnlTabProps {
  bookId: string | null
}

export function PnlTab({ bookId }: PnlTabProps) {
  const { data: pnlData, loading: pnlLoading } = usePnlAttribution(bookId)
  const sod = useSodBaseline(bookId)
  const [showResetDialog, setShowResetDialog] = useState(false)
  const [showJobPicker, setShowJobPicker] = useState(false)
  const [computedData, setComputedData] = useState<PnlAttributionDto | null>(null)

  const { from: intradayFrom, to: intradayTo } = useMemo(() => {
    const today = new Date()
    const pad = (n: number) => String(n).padStart(2, '0')
    const dateStr = `${today.getFullYear()}-${pad(today.getMonth() + 1)}-${pad(today.getDate())}`
    return {
      from: `${dateStr}T00:00:00Z`,
      to: `${dateStr}T23:59:59Z`,
    }
  }, [])

  const { snapshots: historicalSnapshots } = useIntradayPnlSeries(bookId, intradayFrom, intradayTo)
  const { snapshots: liveSnapshots } = useIntradayPnlStream(bookId)

  const intradaySnapshots = liveSnapshots.length > 0 ? liveSnapshots : historicalSnapshots

  const data = computedData ?? pnlData

  const handleComputePnl = useCallback(async () => {
    const result = await sod.computeAttribution()
    if (result) {
      setComputedData(result)
    }
  }, [sod])

  const handleJobSelect = useCallback(async (jobId: string) => {
    setShowJobPicker(false)
    await sod.createSnapshot(jobId)
  }, [sod])

  const handleResetConfirm = useCallback(async () => {
    await sod.resetBaseline()
    setShowResetDialog(false)
    setComputedData(null)
  }, [sod])

  if (pnlLoading || sod.loading) {
    return (
      <div data-testid="pnl-loading" className="flex items-center justify-center py-12">
        <Spinner />
      </div>
    )
  }

  return (
    <div className="space-y-4">
      <Card header="Intraday P&L" data-testid="intraday-pnl-card">
        <IntradayPnlChart snapshots={intradaySnapshots} />
      </Card>

      <SodBaselineIndicator
        status={sod.status}
        loading={sod.loading}
        creating={sod.creating}
        resetting={sod.resetting}
        onCreateSnapshot={() => sod.createSnapshot()}
        onResetBaseline={() => setShowResetDialog(true)}
        onPickFromHistory={() => setShowJobPicker(true)}
      />

      {sod.error && (
        <div data-testid="sod-error" className="py-2">
          <ErrorCard message={sod.error} />
        </div>
      )}

      {sod.status?.exists && !data && (
        <div data-testid="pnl-compute-prompt" className="text-center py-8">
          <EmptyState
            icon={<TrendingUp className="h-10 w-10" />}
            title="SOD baseline set"
            description="Click below to compute P&L attribution using the current baseline."
          />
          <Button
            variant="primary"
            onClick={handleComputePnl}
            loading={sod.computing}
            data-testid="pnl-compute-button"
            className="mt-4"
          >
            Compute P&L Attribution
          </Button>
        </div>
      )}

      {!sod.status?.exists && !data && (
        <div data-testid="pnl-empty">
          <EmptyState
            icon={<TrendingUp className="h-10 w-10" />}
            title="No P&L attribution data"
            description="Set an SOD baseline first, then compute P&L attribution."
          />
        </div>
      )}

      {data && (
        <>
          {sod.status?.exists && (
            <p
              data-testid="pnl-baseline-provenance"
              className="text-sm text-slate-600 dark:text-slate-400 bg-slate-50 dark:bg-slate-800/50 rounded px-3 py-1.5"
            >
              <span className="font-medium">Baseline:</span>{' '}
              Attribution baseline: {sod.status.snapshotType === 'AUTO' ? 'Auto' : 'Manual'}
              {sod.status.calculationType && ` · ${sod.status.calculationType}`}
              {sod.status.createdAt && ` · ${formatTimestamp(sod.status.createdAt)}`}
            </p>
          )}
          <div className="flex justify-end gap-2">
            <button
              data-testid="pnl-csv-export"
              onClick={() => {
                const headers = ['Instrument', 'Asset Class', 'Total P&L', 'Delta', 'Gamma', 'Vega', 'Theta', 'Rho', 'Unexplained']
                const rows = data.positionAttributions.map((p) => [
                  p.instrumentId,
                  p.assetClass,
                  p.totalPnl,
                  p.deltaPnl,
                  p.gammaPnl,
                  p.vegaPnl,
                  p.thetaPnl,
                  p.rhoPnl,
                  p.unexplainedPnl,
                ])
                exportToCsv('pnl-attribution.csv', headers, rows)
              }}
              className="inline-flex items-center gap-1 px-2 py-1 text-xs font-medium text-slate-500 border border-slate-300 rounded hover:bg-slate-50 transition-colors"
            >
              <Download className="h-3.5 w-3.5" />
              Export CSV
            </button>
            {sod.status?.exists && (
              <Button
                variant="secondary"
                size="sm"
                onClick={handleComputePnl}
                loading={sod.computing}
                data-testid="pnl-recompute-button"
              >
                Recompute P&L
              </Button>
            )}
          </div>

          <Card header="P&L Waterfall">
            <PnlWaterfallChart data={data} />
          </Card>

          <Card header="Factor Attribution">
            <PnlAttributionTable data={data} />
          </Card>
        </>
      )}

      {bookId && (
        <BenchmarkAttributionSection bookId={bookId} />
      )}

      {bookId && (
        <JobPickerDialog
          open={showJobPicker}
          bookId={bookId}
          onSelect={handleJobSelect}
          onCancel={() => setShowJobPicker(false)}
        />
      )}

      <ConfirmDialog
        open={showResetDialog}
        title="Reset SOD Baseline"
        message={
          <div>
            <p>This will remove the current SOD baseline and its snapshot data. You can immediately set a new baseline using the current market state.</p>
            {sod.status && (sod.status.sourceJobId || sod.status.calculationType || sod.status.createdAt) && (
              <div data-testid="reset-dialog-metadata" className="mt-3 rounded border border-slate-200 bg-slate-50 p-3 text-xs">
                <p className="font-medium text-slate-700 mb-1">Current baseline</p>
                {sod.status.sourceJobId && (
                  <p className="text-slate-600">
                    Job ID: <span className="font-mono">{sod.status.sourceJobId.slice(0, 8)}</span>
                  </p>
                )}
                {sod.status.calculationType && (
                  <p className="text-slate-600">
                    Type: {sod.status.calculationType}
                  </p>
                )}
                {sod.status.createdAt && (
                  <p className="text-slate-600">
                    Created: {formatTimestamp(sod.status.createdAt)}
                  </p>
                )}
              </div>
            )}
          </div>
        }
        confirmLabel="Reset Baseline"
        cancelLabel="Cancel"
        variant="danger"
        loading={sod.resetting}
        onConfirm={handleResetConfirm}
        onCancel={() => setShowResetDialog(false)}
      />
    </div>
  )
}

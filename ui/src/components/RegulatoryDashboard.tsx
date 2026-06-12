import { Calculator, Download, FileText } from 'lucide-react'
import type { FrtbResultDto } from '../types'
import { formatCurrency, formatTimestamp } from '../utils/format'
import { Card, Button, Spinner } from './ui'

interface RegulatoryDashboardProps {
  result: FrtbResultDto | null
  loading: boolean
  error: string | null
  onCalculate: () => void
  onDownloadCsv: () => void
  onDownloadXbrl: () => void
}

// FRTB SbM risk classes always rendered in this order so the table reads as a
// fixed regulatory schedule rather than a sparse list keyed off the response.
const FRTB_RISK_CLASSES = [
  'GIRR',
  'CSR_NON_SEC',
  'CSR_SEC_CTP',
  'CSR_SEC_NON_CTP',
  'EQUITY',
  'COMMODITY',
  'FX',
] as const

export function RegulatoryDashboard({
  result,
  loading,
  error,
  onCalculate,
  onDownloadCsv,
  onDownloadXbrl,
}: RegulatoryDashboardProps) {
  const totalSbm = result ? Number(result.totalSbmCharge) : 0
  const totalDrc = result ? Number(result.netDrc) : 0
  const totalRrao = result ? Number(result.totalRrao) : 0
  const grandTotal = totalSbm + totalDrc + totalRrao

  // Index the SbM charges by risk class so we can render the full schedule
  // even when the backend omits zero-charge rows.
  const chargesByRiskClass = new Map(
    (result?.sbmCharges ?? []).map((charge) => [charge.riskClass, charge]),
  )

  return (
    <Card
      data-testid="regulatory-dashboard"
      header={<span className="flex items-center gap-1.5"><FileText className="h-4 w-4" />Regulatory Reporting — FRTB</span>}
    >
      <div className="flex items-center gap-3 mb-4">
        <Button
          data-testid="frtb-calculate-btn"
          variant="primary"
          icon={<Calculator className="h-3.5 w-3.5" />}
          onClick={onCalculate}
          loading={loading}
        >
          {loading ? 'Calculating...' : 'Calculate FRTB'}
        </Button>
        {/* Same visual weight as XBRL — two equivalent export formats must
            not differ in colour, and a green 'success' button reads as
            enabled even when disabled (UX review). */}
        <Button
          data-testid="download-csv-btn"
          variant="secondary"
          icon={<Download className="h-3.5 w-3.5" />}
          onClick={onDownloadCsv}
          disabled={!result || loading}
        >
          Download CSV
        </Button>
        <Button
          data-testid="download-xbrl-btn"
          variant="secondary"
          icon={<FileText className="h-3.5 w-3.5" />}
          onClick={onDownloadXbrl}
          disabled={!result || loading}
        >
          Download XBRL
        </Button>
      </div>

      {loading && (
        <div data-testid="regulatory-loading" className="flex items-center gap-2 text-slate-500 text-sm">
          <Spinner size="sm" />
          Calculating FRTB capital requirements...
        </div>
      )}

      {error && (
        <div data-testid="regulatory-error" className="text-red-600 text-sm">
          {error}
        </div>
      )}

      {!result && !loading && !error && (
        <div
          data-testid="frtb-empty-state"
          className="text-sm text-slate-500 dark:text-slate-400 py-6"
        >
          No FRTB run for this book yet. Calculate FRTB to compute the
          SbM, DRC, and RRAO capital charges — the latest run is reloaded
          automatically next time you open this tab.
        </div>
      )}

      {result && !loading && (
        <div data-testid="regulatory-results">
          <div data-testid="capital-summary" className="grid grid-cols-4 gap-3 mb-4">
            <div className="bg-slate-50 dark:bg-slate-800/50 rounded-lg p-3 text-center">
              <div className="text-xs text-slate-500 dark:text-slate-400">Total Capital</div>
              <div className="text-lg font-bold text-slate-800 dark:text-slate-100">{formatCurrency(result.totalCapitalCharge)}</div>
            </div>
            <div className="bg-indigo-50 dark:bg-indigo-900/30 rounded-lg p-3 text-center">
              <div className="text-xs text-slate-500 dark:text-slate-400">SbM</div>
              <div className="text-lg font-bold text-indigo-700 dark:text-indigo-300">{formatCurrency(result.totalSbmCharge)}</div>
            </div>
            <div className="bg-orange-50 dark:bg-orange-900/30 rounded-lg p-3 text-center">
              <div className="text-xs text-slate-500 dark:text-slate-400">DRC</div>
              <div className="text-lg font-bold text-orange-700 dark:text-orange-300">{formatCurrency(result.netDrc)}</div>
            </div>
            <div className="bg-red-50 dark:bg-red-900/30 rounded-lg p-3 text-center">
              <div className="text-xs text-slate-500 dark:text-slate-400">RRAO</div>
              <div className="text-lg font-bold text-red-700 dark:text-red-300">{formatCurrency(result.totalRrao)}</div>
            </div>
          </div>

          <div data-testid="charge-proportions" className="flex gap-2 mb-4 text-xs">
            {grandTotal > 0 && (
              <>
                <div
                  className="bg-indigo-500 text-white rounded px-2 py-1"
                  style={{ flex: totalSbm / grandTotal }}
                >
                  SbM {((totalSbm / grandTotal) * 100).toFixed(0)}%
                </div>
                <div
                  className="bg-orange-500 text-white rounded px-2 py-1"
                  style={{ flex: totalDrc / grandTotal }}
                >
                  DRC {((totalDrc / grandTotal) * 100).toFixed(0)}%
                </div>
                <div
                  className="bg-red-500 text-white rounded px-2 py-1"
                  style={{ flex: totalRrao / grandTotal }}
                >
                  RRAO {((totalRrao / grandTotal) * 100).toFixed(0)}%
                </div>
              </>
            )}
          </div>

          <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-2">SbM Breakdown by Risk Class</h3>
          <table
            data-testid="frtb-sbm-table"
            className="w-full text-sm mb-4"
          >
            <thead>
              <tr className="border-b text-left text-slate-600">
                <th className="py-2">Risk Class</th>
                <th className="py-2 text-right">Delta</th>
                <th className="py-2 text-right">Vega</th>
                <th className="py-2 text-right">Curvature</th>
                <th className="py-2 text-right">Total</th>
              </tr>
            </thead>
            <tbody>
              {FRTB_RISK_CLASSES.map((riskClass) => {
                const charge = chargesByRiskClass.get(riskClass) ?? {
                  riskClass,
                  deltaCharge: '0',
                  vegaCharge: '0',
                  curvatureCharge: '0',
                  totalCharge: '0',
                }
                return (
                  <tr
                    key={riskClass}
                    data-testid={`frtb-row-${riskClass}`}
                    className="border-b hover:bg-slate-50 transition-colors"
                  >
                    <td className="py-1.5">{riskClass}</td>
                    <td className="py-1.5 text-right">{formatCurrency(charge.deltaCharge)}</td>
                    <td className="py-1.5 text-right">{formatCurrency(charge.vegaCharge)}</td>
                    <td className="py-1.5 text-right">{formatCurrency(charge.curvatureCharge)}</td>
                    <td className="py-1.5 text-right font-medium">{formatCurrency(charge.totalCharge)}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>

          <div
            data-testid="frtb-totals"
            className="grid grid-cols-2 md:grid-cols-5 gap-3 mb-3 text-sm"
          >
            <div className="bg-slate-50 dark:bg-slate-800/50 rounded-lg p-2">
              <div className="text-xs text-slate-500 dark:text-slate-400">Total SBM</div>
              <div className="font-semibold text-slate-800 dark:text-slate-100">{formatCurrency(result.totalSbmCharge)}</div>
            </div>
            <div className="bg-slate-50 dark:bg-slate-800/50 rounded-lg p-2">
              <div className="text-xs text-slate-500 dark:text-slate-400">Gross JTD</div>
              <div className="font-semibold text-slate-800 dark:text-slate-100">{formatCurrency(result.grossJtd)}</div>
            </div>
            <div className="bg-slate-50 dark:bg-slate-800/50 rounded-lg p-2">
              <div className="text-xs text-slate-500 dark:text-slate-400">Net DRC</div>
              <div className="font-semibold text-slate-800 dark:text-slate-100">{formatCurrency(result.netDrc)}</div>
            </div>
            <div className="bg-slate-50 dark:bg-slate-800/50 rounded-lg p-2">
              <div className="text-xs text-slate-500 dark:text-slate-400">Total RRAO</div>
              <div className="font-semibold text-slate-800 dark:text-slate-100">{formatCurrency(result.totalRrao)}</div>
            </div>
            <div className="bg-slate-50 dark:bg-slate-800/50 rounded-lg p-2">
              <div className="text-xs text-slate-500 dark:text-slate-400">Total Capital Charge</div>
              <div className="font-semibold text-slate-800 dark:text-slate-100">{formatCurrency(result.totalCapitalCharge)}</div>
            </div>
          </div>

          <div
            data-testid="frtb-calculated-at"
            className="text-xs text-slate-500 dark:text-slate-400"
          >
            Calculated at {formatTimestamp(result.calculatedAt)}
          </div>
        </div>
      )}
    </Card>
  )
}

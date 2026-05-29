import { useEffect, useState } from 'react'
import { FileText, Download, Play, ChevronDown, ChevronUp, ArrowRight } from 'lucide-react'
import { SectionHeading, Spinner } from './ui'
import {
  fetchReportTemplates,
  generateReport,
  downloadReportCsv,
  fetchRecentReports,
  type ReportTemplate,
  type ReportOutput,
  type RecentReport,
} from '../api/reports'
import { AIInsightPanel } from './AIInsightPanel'
import { explainReport, type InsightResponse } from '../api/insights'

interface ReportsTabProps {
  bookId: string | null
  /**
   * Cross-tab jump (plan §2.4): open the Risk tab focused on the reported
   * book and valuation date. Receives the report's bookId and the
   * valuation date used when the report was generated. Date may be empty
   * when the report ran "as of today" — the parent should treat that as
   * "use today's date".
   */
  onJumpToRiskAtDate?: (bookId: string, valuationDate: string) => void
}

/**
 * History row: a ReportOutput plus the bookId / date the user supplied
 * to generate it. The backend's ReportOutput shape doesn't echo these
 * back, so we capture them client-side to support cross-tab links.
 */
interface ReportHistoryEntry extends ReportOutput {
  bookId: string
  date: string
}

export function ReportsTab({ bookId, onJumpToRiskAtDate }: ReportsTabProps) {
  const [templates, setTemplates] = useState<ReportTemplate[]>([])
  const [templatesLoading, setTemplatesLoading] = useState(true)
  const [templatesError, setTemplatesError] = useState<string | null>(null)

  const [selectedTemplateId, setSelectedTemplateId] = useState<string>('')
  const [selectedBookId, setSelectedBookId] = useState<string>(bookId ?? '')
  const [selectedDate, setSelectedDate] = useState<string>('')
  const [generating, setGenerating] = useState(false)
  const [generateError, setGenerateError] = useState<string | null>(null)
  const [currentOutput, setCurrentOutput] = useState<ReportOutput | null>(null)
  const [history, setHistory] = useState<ReportHistoryEntry[]>([])
  const [expandedOutputId, setExpandedOutputId] = useState<string | null>(null)
  const [csvDownloading, setCsvDownloading] = useState(false)

  // AI Commentary card (plan §3.4): once the report finishes generating,
  // fetch a narrative summary via the insights service and render it in
  // an AIInsightPanel directly below the generated report output.
  const [commentaryLoading, setCommentaryLoading] = useState(false)
  const [commentaryError, setCommentaryError] = useState<string | null>(null)
  const [commentary, setCommentary] = useState<InsightResponse | null>(null)

  // Recent Reports panel (trader-review P2 #24): server-backed list of the
  // last N generated reports with status (RUNNING / COMPLETE / FAILED),
  // timestamp, user, and a download link — so the user can see what was
  // generated, by whom, when, and whether it's still running.
  const [recentReports, setRecentReports] = useState<RecentReport[]>([])
  const [recentLoading, setRecentLoading] = useState(true)
  const [recentError, setRecentError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false

    async function load() {
      try {
        const result = await fetchReportTemplates()
        if (!cancelled) {
          setTemplates(result)
          setTemplatesError(null)
        }
      } catch (err) {
        if (!cancelled) {
          setTemplatesError(err instanceof Error ? err.message : String(err))
        }
      } finally {
        if (!cancelled) {
          setTemplatesLoading(false)
        }
      }
    }

    load()
    return () => { cancelled = true }
  }, [])

  useEffect(() => {
    let cancelled = false

    async function loadRecent() {
      try {
        const result = await fetchRecentReports()
        if (!cancelled) {
          setRecentReports(result)
          setRecentError(null)
        }
      } catch (err) {
        if (!cancelled) {
          setRecentError(err instanceof Error ? err.message : String(err))
        }
      } finally {
        if (!cancelled) {
          setRecentLoading(false)
        }
      }
    }

    loadRecent()
    return () => { cancelled = true }
  }, [])

  const handleGenerate = async () => {
    if (!selectedTemplateId || !selectedBookId) return

    setGenerating(true)
    setGenerateError(null)
    setCurrentOutput(null)
    // The AI Commentary card should show its loading skeleton from the
    // moment generation starts — well before the commentary fetch fires
    // — so the user sees a single, unified "thinking" state.
    setCommentary(null)
    setCommentaryError(null)
    setCommentaryLoading(true)

    try {
      const output = await generateReport({
        templateId: selectedTemplateId,
        bookId: selectedBookId,
        date: selectedDate || undefined,
        format: 'JSON',
      })
      setCurrentOutput(output)
      // Capture the bookId / date that drove this generation so the
      // history row can power the cross-tab "Open in Risk" link.
      setHistory(prev => [
        { ...output, bookId: selectedBookId, date: selectedDate },
        ...prev,
      ])
      // Kick off AI commentary fetch using whatever context we have.
      // The report output is intentionally minimal (no metrics/drivers
      // echoed back from the backend), so we derive a plausible payload
      // from the user's selections; the canned/live client on the
      // backend handles missing context gracefully.
      try {
        const insight = await explainReport({
          template_id: output.templateId,
          report_date: selectedDate || new Date().toISOString().slice(0, 10),
          summary_metrics: { row_count: output.rowCount },
          top_drivers: [],
          breaches: [],
        })
        setCommentary(insight)
      } catch (err) {
        setCommentaryError(
          err instanceof Error ? err.message : 'Failed to generate AI commentary',
        )
      } finally {
        setCommentaryLoading(false)
      }
    } catch (err) {
      setGenerateError(err instanceof Error ? err.message : 'Failed to generate report')
      // Report failed — don't try to fetch commentary; clear the card.
      setCommentaryLoading(false)
    } finally {
      setGenerating(false)
    }
  }

  const handleDownloadCsv = async (outputId: string) => {
    setCsvDownloading(true)
    try {
      const csv = await downloadReportCsv(outputId)
      if (csv) {
        triggerCsvDownload(csv, `report-${outputId}.csv`)
      }
    } catch (err) {
      setGenerateError(err instanceof Error ? err.message : 'Failed to download CSV')
    } finally {
      setCsvDownloading(false)
    }
  }

  const handleToggleHistory = (output: ReportOutput) => {
    if (expandedOutputId === output.outputId) {
      setExpandedOutputId(null)
    } else {
      setExpandedOutputId(output.outputId)
      setCurrentOutput(output)
    }
  }

  if (templatesLoading) {
    return (
      <div
        data-testid="reports-loading"
        className="flex items-center gap-2 text-slate-500 text-sm"
      >
        <Spinner size="sm" />
        Loading report templates...
      </div>
    )
  }

  if (templatesError) {
    return (
      <div data-testid="reports-error" className="text-red-600 text-sm" role="alert">
        {templatesError}
      </div>
    )
  }

  return (
    <div className="space-y-6">
      <div className="bg-white dark:bg-surface-800 rounded-lg border border-slate-200 dark:border-surface-700 p-6">
        <SectionHeading as="h2" className="mb-4 flex items-center gap-2">
          <FileText className="h-5 w-5 text-primary-500" />
          Generate Report
        </SectionHeading>

        <div className="grid grid-cols-3 gap-4 mb-4">
          <div>
            <label
              htmlFor="report-template-select"
              className="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1"
            >
              Template
            </label>
            <select
              id="report-template-select"
              data-testid="report-template-select"
              value={selectedTemplateId}
              onChange={e => setSelectedTemplateId(e.target.value)}
              className="w-full rounded-md border border-slate-300 dark:border-surface-600 bg-white dark:bg-surface-900 text-slate-800 dark:text-slate-100 text-sm px-3 py-2 focus:outline-none focus:ring-2 focus:ring-primary-500"
            >
              <option value="">Select a template...</option>
              {templates.map(t => (
                <option key={t.templateId} value={t.templateId}>
                  {t.name}
                </option>
              ))}
            </select>
          </div>

          <div>
            <label
              htmlFor="report-book-input"
              className="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1"
            >
              Book ID
            </label>
            <input
              id="report-book-input"
              data-testid="report-book-input"
              type="text"
              value={selectedBookId}
              onChange={e => setSelectedBookId(e.target.value)}
              placeholder="e.g. BOOK-1"
              className="w-full rounded-md border border-slate-300 dark:border-surface-600 bg-white dark:bg-surface-900 text-slate-800 dark:text-slate-100 text-sm px-3 py-2 focus:outline-none focus:ring-2 focus:ring-primary-500"
            />
          </div>

          <div>
            <label
              htmlFor="report-date-input"
              className="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1"
            >
              Date (optional)
            </label>
            <input
              id="report-date-input"
              data-testid="report-date-input"
              type="date"
              value={selectedDate}
              onChange={e => setSelectedDate(e.target.value)}
              className="w-full rounded-md border border-slate-300 dark:border-surface-600 bg-white dark:bg-surface-900 text-slate-800 dark:text-slate-100 text-sm px-3 py-2 focus:outline-none focus:ring-2 focus:ring-primary-500"
            />
          </div>
        </div>

        <div className="flex items-center gap-3">
          <button
            data-testid="report-generate-button"
            onClick={handleGenerate}
            disabled={!selectedTemplateId || !selectedBookId || generating}
            className="inline-flex items-center gap-1.5 px-4 py-2 text-sm font-medium bg-primary-600 text-white rounded-md hover:bg-primary-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            <Play className="h-4 w-4" />
            {generating ? 'Generating...' : 'Generate'}
          </button>

          {currentOutput && (
            <button
              data-testid="report-download-csv-button"
              onClick={() => handleDownloadCsv(currentOutput.outputId)}
              disabled={csvDownloading}
              className="inline-flex items-center gap-1.5 px-4 py-2 text-sm font-medium border border-slate-300 dark:border-surface-600 text-slate-700 dark:text-slate-200 rounded-md hover:bg-slate-50 dark:hover:bg-surface-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              <Download className="h-4 w-4" />
              {csvDownloading ? 'Downloading...' : 'Download CSV'}
            </button>
          )}
        </div>

        {generateError && (
          <div
            data-testid="report-generate-error"
            className="mt-3 text-sm text-red-600 dark:text-red-400"
            role="alert"
          >
            {generateError}
          </div>
        )}
      </div>

      {currentOutput && (
        <div
          data-testid="report-output-panel"
          className="bg-white dark:bg-surface-800 rounded-lg border border-slate-200 dark:border-surface-700 p-6"
        >
          <div className="mb-3">
            <SectionHeading
              right={
                <span
                  data-testid="report-output-meta"
                  className="text-xs text-slate-500 dark:text-slate-400"
                >
                  {currentOutput.rowCount} rows &middot; generated{' '}
                  {new Date(currentOutput.generatedAt).toLocaleString()}
                </span>
              }
            >
              Report Output
            </SectionHeading>
          </div>
          <p
            data-testid="report-no-data-message"
            className="text-sm text-slate-500 dark:text-slate-400"
          >
            Report generated successfully with {currentOutput.rowCount} rows. Download CSV to view
            the data.
          </p>
        </div>
      )}

      {(commentaryLoading || commentaryError || commentary) && (
        <div data-testid="ai-commentary-card">
          <AIInsightPanel
            title="AI Commentary"
            loading={commentaryLoading}
            error={commentaryError}
            insight={commentary}
          />
        </div>
      )}

      {history.length > 0 && (
        <div
          data-testid="report-history-panel"
          className="bg-white dark:bg-surface-800 rounded-lg border border-slate-200 dark:border-surface-700 p-6"
        >
          <SectionHeading className="mb-3">Report History</SectionHeading>
          <ul className="divide-y divide-slate-100 dark:divide-surface-700" role="list">
            {history.map(output => (
              <li
                key={output.outputId}
                data-testid={`report-history-item-${output.outputId}`}
                className="py-2"
              >
                <div className="w-full flex items-center justify-between gap-2 hover:bg-slate-50 dark:hover:bg-surface-700 rounded px-2 py-1 transition-colors">
                  <button
                    className="flex-1 flex items-center justify-between text-left"
                    onClick={() => handleToggleHistory(output)}
                  >
                    <div>
                      <span className="text-sm text-slate-700 dark:text-slate-200 font-medium">
                        {output.templateId}
                      </span>
                      <span className="ml-3 text-xs text-slate-400 dark:text-slate-500">
                        {output.rowCount} rows &middot;{' '}
                        {new Date(output.generatedAt).toLocaleString()}
                      </span>
                    </div>
                    {expandedOutputId === output.outputId ? (
                      <ChevronUp className="h-4 w-4 text-slate-400" />
                    ) : (
                      <ChevronDown className="h-4 w-4 text-slate-400" />
                    )}
                  </button>
                  {onJumpToRiskAtDate && (
                    <button
                      type="button"
                      data-testid={`open-in-risk-${output.outputId}`}
                      onClick={() => onJumpToRiskAtDate(output.bookId, output.date)}
                      title={`Open ${output.bookId} on the Risk tab at ${output.date || 'today'}`}
                      aria-label={`Open ${output.bookId} on the Risk tab at ${output.date || 'today'}`}
                      className="inline-flex items-center gap-1 px-2 py-0.5 text-xs font-medium text-indigo-700 dark:text-indigo-300 border border-indigo-200 dark:border-indigo-800 rounded hover:bg-indigo-50 dark:hover:bg-indigo-900/30 transition-colors"
                    >
                      <ArrowRight className="h-3 w-3" />
                      Open in Risk
                    </button>
                  )}
                </div>
              </li>
            ))}
          </ul>
        </div>
      )}

      <div
        data-testid="recent-reports-panel"
        className="bg-white dark:bg-surface-800 rounded-lg border border-slate-200 dark:border-surface-700 p-6"
      >
        <SectionHeading className="mb-3">Recent Reports</SectionHeading>

        {recentLoading ? (
          <div
            data-testid="recent-reports-loading"
            className="flex items-center gap-2 text-slate-500 text-sm"
          >
            <Spinner size="sm" />
            Loading recent reports...
          </div>
        ) : recentError ? (
          <div
            data-testid="recent-reports-error"
            className="text-sm text-red-600 dark:text-red-400"
            role="alert"
          >
            {recentError}
          </div>
        ) : recentReports.length === 0 ? (
          <p
            data-testid="recent-reports-empty"
            className="text-sm text-slate-500 dark:text-slate-400"
          >
            No reports have been generated yet.
          </p>
        ) : (
          <ul className="divide-y divide-slate-100 dark:divide-surface-700" role="list">
            {recentReports.map(report => (
              <li
                key={report.outputId}
                data-testid={`recent-report-row-${report.outputId}`}
                className="py-2 flex items-center justify-between gap-3 px-2"
              >
                <div className="min-w-0">
                  <span className="text-sm text-slate-700 dark:text-slate-200 font-medium">
                    {report.templateId}
                  </span>
                  <div className="text-xs text-slate-400 dark:text-slate-500">
                    {report.user} &middot;{' '}
                    {new Date(report.timestamp).toLocaleString()}
                  </div>
                </div>
                <div className="flex items-center gap-3 shrink-0">
                  <ReportStatusPill outputId={report.outputId} status={report.status} />
                  {report.status === 'COMPLETE' && (
                    <a
                      data-testid={`recent-report-download-${report.outputId}`}
                      href={report.downloadUrl}
                      className="inline-flex items-center gap-1 text-xs font-medium text-primary-600 dark:text-primary-400 hover:underline"
                    >
                      <Download className="h-3.5 w-3.5" />
                      Download
                    </a>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}

const STATUS_PILL_CLASSES: Record<RecentReport['status'], string> = {
  COMPLETE: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300',
  RUNNING: 'bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300',
  FAILED: 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300',
}

function ReportStatusPill({
  outputId,
  status,
}: {
  outputId: string
  status: RecentReport['status']
}) {
  return (
    <span
      data-testid={`recent-report-status-${outputId}`}
      className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${STATUS_PILL_CLASSES[status]}`}
    >
      {status}
    </span>
  )
}

function triggerCsvDownload(csv: string, filename: string) {
  const blob = new Blob([csv], { type: 'text/csv' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

import { useEffect, useState } from 'react'
import { FileText, Download, Play, ChevronDown, ChevronUp } from 'lucide-react'
import { Spinner } from './ui'
import {
  fetchReportTemplates,
  generateReport,
  downloadReportCsv,
  type ReportTemplate,
  type ReportOutput,
} from '../api/reports'

interface ReportsTabProps {
  bookId: string | null
}

export function ReportsTab({ bookId }: ReportsTabProps) {
  const [templates, setTemplates] = useState<ReportTemplate[]>([])
  const [templatesLoading, setTemplatesLoading] = useState(true)
  const [templatesError, setTemplatesError] = useState<string | null>(null)

  const [selectedTemplateId, setSelectedTemplateId] = useState<string>('')
  const [selectedBookId, setSelectedBookId] = useState<string>(bookId ?? '')
  const [selectedDate, setSelectedDate] = useState<string>('')
  const [generating, setGenerating] = useState(false)
  const [generateError, setGenerateError] = useState<string | null>(null)
  const [currentOutput, setCurrentOutput] = useState<ReportOutput | null>(null)
  const [history, setHistory] = useState<ReportOutput[]>([])
  const [expandedOutputId, setExpandedOutputId] = useState<string | null>(null)
  const [csvDownloading, setCsvDownloading] = useState(false)

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

  const handleGenerate = async () => {
    if (!selectedTemplateId || !selectedBookId) return

    setGenerating(true)
    setGenerateError(null)
    setCurrentOutput(null)

    try {
      const output = await generateReport({
        templateId: selectedTemplateId,
        bookId: selectedBookId,
        date: selectedDate || undefined,
        format: 'JSON',
      })
      setCurrentOutput(output)
      setHistory(prev => [output, ...prev])
    } catch (err) {
      setGenerateError(err instanceof Error ? err.message : 'Failed to generate report')
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
        <h2 className="text-base font-semibold text-slate-800 dark:text-slate-100 mb-4 flex items-center gap-2">
          <FileText className="h-5 w-5 text-primary-500" />
          Generate Report
        </h2>

        <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 mb-4">
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
          <div className="flex items-center justify-between mb-3">
            <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200">
              Report Output
            </h3>
            <span
              data-testid="report-output-meta"
              className="text-xs text-slate-500 dark:text-slate-400"
            >
              {currentOutput.rowCount} rows &middot; generated{' '}
              {new Date(currentOutput.generatedAt).toLocaleString()}
            </span>
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

      {history.length > 0 && (
        <div
          data-testid="report-history-panel"
          className="bg-white dark:bg-surface-800 rounded-lg border border-slate-200 dark:border-surface-700 p-6"
        >
          <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-3">
            Report History
          </h3>
          <ul className="divide-y divide-slate-100 dark:divide-surface-700" role="list">
            {history.map(output => (
              <li
                key={output.outputId}
                data-testid={`report-history-item-${output.outputId}`}
                className="py-2"
              >
                <button
                  className="w-full flex items-center justify-between text-left hover:bg-slate-50 dark:hover:bg-surface-700 rounded px-2 py-1 transition-colors"
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
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
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

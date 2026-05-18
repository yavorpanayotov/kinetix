import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { ReportsTab } from './ReportsTab'

vi.mock('../api/reports', () => ({
  fetchReportTemplates: vi.fn(),
  generateReport: vi.fn(),
  downloadReportCsv: vi.fn(),
}))

import { fetchReportTemplates, generateReport, downloadReportCsv } from '../api/reports'

const mockFetchTemplates = vi.mocked(fetchReportTemplates)
const mockGenerate = vi.mocked(generateReport)
const mockDownloadCsv = vi.mocked(downloadReportCsv)

const TEMPLATES = [
  {
    templateId: 'tpl-risk-summary',
    name: 'Risk Summary',
    templateType: 'RISK_SUMMARY',
    ownerUserId: 'SYSTEM',
    description: 'Per-book VaR and Greeks',
    source: 'risk_positions_flat',
  },
  {
    templateId: 'tpl-pnl-attribution',
    name: 'P&L Attribution',
    templateType: 'PNL_ATTRIBUTION',
    ownerUserId: 'SYSTEM',
    description: 'P&L attribution by component',
    source: 'pnl_attributions',
  },
]

const OUTPUT = {
  outputId: 'out-abc',
  templateId: 'tpl-risk-summary',
  generatedAt: '2025-01-15T10:00:00Z',
  outputFormat: 'JSON',
  rowCount: 42,
}

describe('ReportsTab', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  it('shows loading state while fetching templates', () => {
    mockFetchTemplates.mockReturnValue(new Promise(() => {}))

    render(<ReportsTab bookId="BOOK-1" />)

    expect(screen.getByTestId('reports-loading')).toBeInTheDocument()
  })

  it('renders a Spinner alongside the loading label while fetching templates', () => {
    mockFetchTemplates.mockReturnValue(new Promise(() => {}))

    render(<ReportsTab bookId="BOOK-1" />)

    const loading = screen.getByTestId('reports-loading')
    expect(loading).toBeInTheDocument()
    // Spinner is a Lucide Loader2 svg with the `animate-spin` class
    expect(loading.querySelector('.animate-spin')).toBeInTheDocument()
    // The label text remains visible alongside the spinner
    expect(loading).toHaveTextContent('Loading report templates...')
  })

  it('renders template options after loading', async () => {
    mockFetchTemplates.mockResolvedValue(TEMPLATES)

    render(<ReportsTab bookId="BOOK-1" />)

    await waitFor(() => {
      expect(screen.getByTestId('report-template-select')).toBeInTheDocument()
    })

    expect(screen.getByText('Risk Summary')).toBeInTheDocument()
    expect(screen.getByText('P&L Attribution')).toBeInTheDocument()
  })

  it('shows error message when template fetch fails', async () => {
    mockFetchTemplates.mockRejectedValue(new Error('Network error'))

    render(<ReportsTab bookId="BOOK-1" />)

    await waitFor(() => {
      expect(screen.getByTestId('reports-error')).toBeInTheDocument()
    })

    expect(screen.getByTestId('reports-error')).toHaveTextContent('Network error')
  })

  it('pre-fills book ID from prop', async () => {
    mockFetchTemplates.mockResolvedValue(TEMPLATES)

    render(<ReportsTab bookId="BOOK-99" />)

    await waitFor(() => {
      expect(screen.getByTestId('report-book-input')).toBeInTheDocument()
    })

    expect(screen.getByTestId('report-book-input')).toHaveValue('BOOK-99')
  })

  it('generate button is disabled when no template is selected', async () => {
    mockFetchTemplates.mockResolvedValue(TEMPLATES)

    render(<ReportsTab bookId="BOOK-1" />)

    await waitFor(() => {
      expect(screen.getByTestId('report-generate-button')).toBeInTheDocument()
    })

    expect(screen.getByTestId('report-generate-button')).toBeDisabled()
  })

  it('generate button is disabled when book ID is empty', async () => {
    const user = userEvent.setup()
    mockFetchTemplates.mockResolvedValue(TEMPLATES)

    render(<ReportsTab bookId={null} />)

    await waitFor(() => {
      expect(screen.getByTestId('report-template-select')).toBeInTheDocument()
    })

    await user.selectOptions(screen.getByTestId('report-template-select'), 'tpl-risk-summary')

    // Book ID input is empty (bookId prop is null)
    expect(screen.getByTestId('report-generate-button')).toBeDisabled()
  })

  it('calls generateReport with correct parameters when Generate is clicked', async () => {
    const user = userEvent.setup()
    mockFetchTemplates.mockResolvedValue(TEMPLATES)
    mockGenerate.mockResolvedValue(OUTPUT)

    render(<ReportsTab bookId="BOOK-1" />)

    await waitFor(() => {
      expect(screen.getByTestId('report-template-select')).toBeInTheDocument()
    })

    await user.selectOptions(screen.getByTestId('report-template-select'), 'tpl-risk-summary')
    await user.click(screen.getByTestId('report-generate-button'))

    await waitFor(() => {
      expect(mockGenerate).toHaveBeenCalledWith({
        templateId: 'tpl-risk-summary',
        bookId: 'BOOK-1',
        date: undefined,
        format: 'JSON',
      })
    })
  })

  it('shows report output panel after successful generation', async () => {
    const user = userEvent.setup()
    mockFetchTemplates.mockResolvedValue(TEMPLATES)
    mockGenerate.mockResolvedValue(OUTPUT)

    render(<ReportsTab bookId="BOOK-1" />)

    await waitFor(() => {
      expect(screen.getByTestId('report-template-select')).toBeInTheDocument()
    })

    await user.selectOptions(screen.getByTestId('report-template-select'), 'tpl-risk-summary')
    await user.click(screen.getByTestId('report-generate-button'))

    await waitFor(() => {
      expect(screen.getByTestId('report-output-panel')).toBeInTheDocument()
    })

    expect(screen.getByTestId('report-output-meta')).toHaveTextContent('42 rows')
    expect(screen.getByTestId('report-no-data-message')).toBeInTheDocument()
  })

  it('shows Download CSV button after successful generation', async () => {
    const user = userEvent.setup()
    mockFetchTemplates.mockResolvedValue(TEMPLATES)
    mockGenerate.mockResolvedValue(OUTPUT)

    render(<ReportsTab bookId="BOOK-1" />)

    await waitFor(() => {
      expect(screen.getByTestId('report-template-select')).toBeInTheDocument()
    })

    await user.selectOptions(screen.getByTestId('report-template-select'), 'tpl-risk-summary')
    await user.click(screen.getByTestId('report-generate-button'))

    await waitFor(() => {
      expect(screen.getByTestId('report-download-csv-button')).toBeInTheDocument()
    })
  })

  it('calls downloadReportCsv when Download CSV is clicked', async () => {
    const user = userEvent.setup()
    mockFetchTemplates.mockResolvedValue(TEMPLATES)
    mockGenerate.mockResolvedValue(OUTPUT)
    mockDownloadCsv.mockResolvedValue('book_id,instrument_id\nBOOK-1,AAPL')

    // Mock URL.createObjectURL and URL.revokeObjectURL so the download trigger does not throw
    vi.stubGlobal('URL', {
      createObjectURL: vi.fn().mockReturnValue('blob:fake'),
      revokeObjectURL: vi.fn(),
    })

    render(<ReportsTab bookId="BOOK-1" />)

    await waitFor(() => {
      expect(screen.getByTestId('report-template-select')).toBeInTheDocument()
    })

    await user.selectOptions(screen.getByTestId('report-template-select'), 'tpl-risk-summary')
    await user.click(screen.getByTestId('report-generate-button'))

    await waitFor(() => {
      expect(screen.getByTestId('report-download-csv-button')).toBeInTheDocument()
    })

    await user.click(screen.getByTestId('report-download-csv-button'))

    await waitFor(() => {
      expect(mockDownloadCsv).toHaveBeenCalledWith('out-abc')
    })

    vi.unstubAllGlobals()
  })

  it('shows generate error when generateReport throws', async () => {
    const user = userEvent.setup()
    mockFetchTemplates.mockResolvedValue(TEMPLATES)
    mockGenerate.mockRejectedValue(new Error('Template not found'))

    render(<ReportsTab bookId="BOOK-1" />)

    await waitFor(() => {
      expect(screen.getByTestId('report-template-select')).toBeInTheDocument()
    })

    await user.selectOptions(screen.getByTestId('report-template-select'), 'tpl-risk-summary')
    await user.click(screen.getByTestId('report-generate-button'))

    await waitFor(() => {
      expect(screen.getByTestId('report-generate-error')).toBeInTheDocument()
    })

    expect(screen.getByTestId('report-generate-error')).toHaveTextContent('Template not found')
  })

  it('adds generated report to history', async () => {
    const user = userEvent.setup()
    mockFetchTemplates.mockResolvedValue(TEMPLATES)
    mockGenerate.mockResolvedValue(OUTPUT)

    render(<ReportsTab bookId="BOOK-1" />)

    await waitFor(() => {
      expect(screen.getByTestId('report-template-select')).toBeInTheDocument()
    })

    await user.selectOptions(screen.getByTestId('report-template-select'), 'tpl-risk-summary')
    await user.click(screen.getByTestId('report-generate-button'))

    await waitFor(() => {
      expect(screen.getByTestId('report-history-panel')).toBeInTheDocument()
    })

    expect(screen.getByTestId('report-history-item-out-abc')).toBeInTheDocument()
  })

  it('passes optional date to generateReport when date is set', async () => {
    const user = userEvent.setup()
    mockFetchTemplates.mockResolvedValue(TEMPLATES)
    mockGenerate.mockResolvedValue(OUTPUT)

    render(<ReportsTab bookId="BOOK-1" />)

    await waitFor(() => {
      expect(screen.getByTestId('report-template-select')).toBeInTheDocument()
    })

    await user.selectOptions(screen.getByTestId('report-template-select'), 'tpl-risk-summary')
    await user.type(screen.getByTestId('report-date-input'), '2025-01-15')
    await user.click(screen.getByTestId('report-generate-button'))

    await waitFor(() => {
      expect(mockGenerate).toHaveBeenCalledWith(
        expect.objectContaining({ date: '2025-01-15' }),
      )
    })
  })

  describe('cross-tab jump to Risk at the reported valuation date', () => {
    it('renders an "Open in Risk" button on each generated report row when onJumpToRiskAtDate is provided', async () => {
      const user = userEvent.setup()
      mockFetchTemplates.mockResolvedValue(TEMPLATES)
      mockGenerate.mockResolvedValue(OUTPUT)

      render(<ReportsTab bookId="BOOK-1" onJumpToRiskAtDate={() => {}} />)

      await waitFor(() => {
        expect(screen.getByTestId('report-template-select')).toBeInTheDocument()
      })

      await user.selectOptions(screen.getByTestId('report-template-select'), 'tpl-risk-summary')
      await user.type(screen.getByTestId('report-date-input'), '2025-01-15')
      await user.click(screen.getByTestId('report-generate-button'))

      await waitFor(() => {
        expect(screen.getByTestId('open-in-risk-out-abc')).toBeInTheDocument()
      })
    })

    it('does not render "Open in Risk" when onJumpToRiskAtDate is omitted', async () => {
      const user = userEvent.setup()
      mockFetchTemplates.mockResolvedValue(TEMPLATES)
      mockGenerate.mockResolvedValue(OUTPUT)

      render(<ReportsTab bookId="BOOK-1" />)

      await waitFor(() => {
        expect(screen.getByTestId('report-template-select')).toBeInTheDocument()
      })

      await user.selectOptions(screen.getByTestId('report-template-select'), 'tpl-risk-summary')
      await user.click(screen.getByTestId('report-generate-button'))

      await waitFor(() => {
        expect(screen.getByTestId('report-history-item-out-abc')).toBeInTheDocument()
      })

      expect(screen.queryByTestId('open-in-risk-out-abc')).not.toBeInTheDocument()
    })

    it('clicking "Open in Risk" invokes onJumpToRiskAtDate with the report bookId and date', async () => {
      const user = userEvent.setup()
      mockFetchTemplates.mockResolvedValue(TEMPLATES)
      mockGenerate.mockResolvedValue(OUTPUT)
      const onJumpToRiskAtDate = vi.fn()

      render(
        <ReportsTab bookId="BOOK-1" onJumpToRiskAtDate={onJumpToRiskAtDate} />,
      )

      await waitFor(() => {
        expect(screen.getByTestId('report-template-select')).toBeInTheDocument()
      })

      await user.selectOptions(screen.getByTestId('report-template-select'), 'tpl-risk-summary')
      await user.type(screen.getByTestId('report-date-input'), '2025-01-15')
      await user.click(screen.getByTestId('report-generate-button'))

      await waitFor(() => {
        expect(screen.getByTestId('open-in-risk-out-abc')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('open-in-risk-out-abc'))

      expect(onJumpToRiskAtDate).toHaveBeenCalledWith('BOOK-1', '2025-01-15')
    })
  })
})

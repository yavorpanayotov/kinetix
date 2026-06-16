import { fireEvent, render, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../../hooks/useBookSelector')

import { MobileApp } from './MobileApp'
import { useBookSelector } from '../../hooks/useBookSelector'

const mockUseBookSelector = vi.mocked(useBookSelector)

function setupBookSelector() {
  mockUseBookSelector.mockReturnValue({
    bookOptions: [
      { value: '__ALL__', label: 'All Books' },
      { value: 'book-1', label: 'book-1' },
      { value: 'book-2', label: 'book-2' },
    ],
    selectedBookId: 'book-1',
    isAllSelected: false,
    allBookIds: ['book-1', 'book-2'],
    positions: [],
    aggregatedPositions: [],
    selectBook: vi.fn(),
    loading: false,
    error: null,
  })
}

describe('MobileApp', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    setupBookSelector()
  })

  describe('shell', () => {
    it('renders the header with the Kinetix logo', () => {
      render(<MobileApp />)

      const header = screen.getByTestId('mobile-header')
      expect(header).toBeInTheDocument()
      expect(within(header).getByText('Kinetix')).toBeInTheDocument()
    })

    it('renders a book selector reflecting the available books', () => {
      render(<MobileApp />)

      const selector = screen.getByTestId('mobile-book-selector')
      expect(selector).toBeInTheDocument()
      expect(selector).toHaveValue('book-1')
      expect(within(selector).getByRole('option', { name: 'book-2' })).toBeInTheDocument()
    })

    it('changing the book selector calls selectBook', () => {
      const selectBook = vi.fn()
      mockUseBookSelector.mockReturnValue({
        bookOptions: [
          { value: 'book-1', label: 'book-1' },
          { value: 'book-2', label: 'book-2' },
        ],
        selectedBookId: 'book-1',
        isAllSelected: false,
        allBookIds: ['book-1', 'book-2'],
        positions: [],
        aggregatedPositions: [],
        selectBook,
        loading: false,
        error: null,
      })

      render(<MobileApp />)
      fireEvent.change(screen.getByTestId('mobile-book-selector'), {
        target: { value: 'book-2' },
      })

      expect(selectBook).toHaveBeenCalledWith('book-2')
    })

    it('renders a theme toggle', () => {
      render(<MobileApp />)

      expect(screen.getByTestId('mobile-dark-mode-toggle')).toBeInTheDocument()
    })

    it('renders a bottom tab bar with all four views', () => {
      render(<MobileApp />)

      const tabBar = screen.getByTestId('mobile-tab-bar')
      expect(tabBar).toHaveAttribute('role', 'tablist')
      expect(within(tabBar).getByTestId('mobile-tab-risk')).toBeInTheDocument()
      expect(within(tabBar).getByTestId('mobile-tab-pnl')).toBeInTheDocument()
      expect(within(tabBar).getByTestId('mobile-tab-alerts')).toBeInTheDocument()
      expect(within(tabBar).getByTestId('mobile-tab-positions')).toBeInTheDocument()
    })

    it('places the bottom tab bar after the main content in DOM order (thumb reach)', () => {
      render(<MobileApp />)

      const main = screen.getByTestId('mobile-main')
      const tabBar = screen.getByTestId('mobile-tab-bar')
      const order = main.compareDocumentPosition(tabBar)
      expect(order & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    })
  })

  describe('default view', () => {
    it('renders the Risk view by default', () => {
      render(<MobileApp />)

      expect(screen.getByTestId('mobile-view-risk')).toBeInTheDocument()
      expect(screen.queryByTestId('mobile-view-pnl')).not.toBeInTheDocument()
      expect(screen.queryByTestId('mobile-view-alerts')).not.toBeInTheDocument()
      expect(screen.queryByTestId('mobile-view-positions')).not.toBeInTheDocument()
    })

    it('marks the Risk tab as selected by default', () => {
      render(<MobileApp />)

      expect(screen.getByTestId('mobile-tab-risk')).toHaveAttribute(
        'aria-selected',
        'true',
      )
      expect(screen.getByTestId('mobile-tab-pnl')).toHaveAttribute(
        'aria-selected',
        'false',
      )
    })
  })

  describe('navigation', () => {
    it('switches to the P&L view when the P&L tab is tapped', () => {
      render(<MobileApp />)

      fireEvent.click(screen.getByTestId('mobile-tab-pnl'))

      expect(screen.getByTestId('mobile-view-pnl')).toBeInTheDocument()
      expect(screen.queryByTestId('mobile-view-risk')).not.toBeInTheDocument()
      expect(screen.getByTestId('mobile-tab-pnl')).toHaveAttribute(
        'aria-selected',
        'true',
      )
    })

    it('switches to the Alerts view when the Alerts tab is tapped', () => {
      render(<MobileApp />)

      fireEvent.click(screen.getByTestId('mobile-tab-alerts'))

      expect(screen.getByTestId('mobile-view-alerts')).toBeInTheDocument()
      expect(screen.queryByTestId('mobile-view-risk')).not.toBeInTheDocument()
    })

    it('switches to the Positions view when the Positions tab is tapped', () => {
      render(<MobileApp />)

      fireEvent.click(screen.getByTestId('mobile-tab-positions'))

      expect(screen.getByTestId('mobile-view-positions')).toBeInTheDocument()
      expect(screen.queryByTestId('mobile-view-risk')).not.toBeInTheDocument()
    })

    it('returns to the Risk view after navigating away and back', () => {
      render(<MobileApp />)

      fireEvent.click(screen.getByTestId('mobile-tab-positions'))
      expect(screen.getByTestId('mobile-view-positions')).toBeInTheDocument()

      fireEvent.click(screen.getByTestId('mobile-tab-risk'))
      expect(screen.getByTestId('mobile-view-risk')).toBeInTheDocument()
      expect(screen.queryByTestId('mobile-view-positions')).not.toBeInTheDocument()
    })
  })
})

import { render, screen, fireEvent } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { BlockTradesDialog } from './BlockTradesDialog'

describe('BlockTradesDialog', () => {
  it('renders a confirm prompt scoped to the counterparty', () => {
    render(<BlockTradesDialog counterpartyId="CP-DB" onClose={() => {}} />)

    const dialog = screen.getByTestId('block-trades-dialog')
    expect(dialog).toHaveTextContent('CP-DB')
    expect(screen.getByTestId('block-trades-confirm')).toBeInTheDocument()
    expect(screen.getByTestId('block-trades-cancel')).toBeInTheDocument()
    expect(screen.queryByTestId('block-trades-success')).not.toBeInTheDocument()
  })

  it('shows a success acknowledgement scoped to the counterparty after confirming', () => {
    render(<BlockTradesDialog counterpartyId="CP-DB" onClose={() => {}} />)

    fireEvent.click(screen.getByTestId('block-trades-confirm'))

    const success = screen.getByTestId('block-trades-success')
    expect(success).toBeInTheDocument()
    expect(success).toHaveTextContent('CP-DB')
    // The confirm button is replaced by the success state.
    expect(screen.queryByTestId('block-trades-confirm')).not.toBeInTheDocument()
  })

  it('closes without confirming when Cancel is clicked', () => {
    const onClose = vi.fn()
    render(<BlockTradesDialog counterpartyId="CP-DB" onClose={onClose} />)

    fireEvent.click(screen.getByTestId('block-trades-cancel'))

    expect(onClose).toHaveBeenCalledTimes(1)
    expect(screen.queryByTestId('block-trades-success')).not.toBeInTheDocument()
  })

  it('closes when the overlay backdrop is clicked', () => {
    const onClose = vi.fn()
    render(<BlockTradesDialog counterpartyId="CP-DB" onClose={onClose} />)

    fireEvent.click(screen.getByTestId('block-trades-overlay'))

    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('closes the success view via the Done button', () => {
    const onClose = vi.fn()
    render(<BlockTradesDialog counterpartyId="CP-DB" onClose={onClose} />)

    fireEvent.click(screen.getByTestId('block-trades-confirm'))
    fireEvent.click(screen.getByTestId('block-trades-done'))

    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('closes on Escape', () => {
    const onClose = vi.fn()
    render(<BlockTradesDialog counterpartyId="CP-DB" onClose={onClose} />)

    fireEvent.keyDown(document, { key: 'Escape' })

    expect(onClose).toHaveBeenCalledTimes(1)
  })
})

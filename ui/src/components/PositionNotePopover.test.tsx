import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { PositionNoteDto } from '../types'
import { PositionNotePopover } from './PositionNotePopover'

const note = (overrides: Partial<PositionNoteDto> = {}): PositionNoteDto => ({
  id: 'note-1',
  bookId: 'port-1',
  instrumentId: 'AAPL',
  note: 'Watching earnings',
  author: 'alice',
  createdAt: '2026-05-18T10:00:00Z',
  ...overrides,
})

describe('PositionNotePopover', () => {
  it('renders existing notes for the instrument', () => {
    render(
      <PositionNotePopover
        instrumentId="AAPL"
        notes={[
          note({ id: 'n1', note: 'First', author: 'alice' }),
          note({ id: 'n2', note: 'Second', author: 'bob' }),
        ]}
        onAdd={vi.fn()}
        onDelete={vi.fn()}
        onClose={vi.fn()}
      />,
    )

    const popover = screen.getByTestId('position-note-popover-AAPL')
    expect(within(popover).getByText('First')).toBeInTheDocument()
    expect(within(popover).getByText('Second')).toBeInTheDocument()
    expect(within(popover).getByText('alice')).toBeInTheDocument()
    expect(within(popover).getByText('bob')).toBeInTheDocument()
  })

  it('shows an empty hint when there are no existing notes', () => {
    render(
      <PositionNotePopover
        instrumentId="AAPL"
        notes={[]}
        onAdd={vi.fn()}
        onDelete={vi.fn()}
        onClose={vi.fn()}
      />,
    )

    const popover = screen.getByTestId('position-note-popover-AAPL')
    expect(within(popover).getByText(/no notes yet/i)).toBeInTheDocument()
  })

  it('calls onAdd with the textarea text when the form is submitted', async () => {
    const onAdd = vi.fn().mockResolvedValue(undefined)
    const user = userEvent.setup()

    render(
      <PositionNotePopover
        instrumentId="AAPL"
        notes={[]}
        onAdd={onAdd}
        onDelete={vi.fn()}
        onClose={vi.fn()}
      />,
    )

    const textarea = screen.getByTestId('position-note-input-AAPL')
    await user.type(textarea, 'Need to review hedges')

    await user.click(screen.getByTestId('position-note-submit-AAPL'))

    expect(onAdd).toHaveBeenCalledWith('Need to review hedges')
  })

  it('does not call onAdd when textarea is empty or whitespace', async () => {
    const onAdd = vi.fn()
    const user = userEvent.setup()

    render(
      <PositionNotePopover
        instrumentId="AAPL"
        notes={[]}
        onAdd={onAdd}
        onDelete={vi.fn()}
        onClose={vi.fn()}
      />,
    )

    await user.click(screen.getByTestId('position-note-submit-AAPL'))
    expect(onAdd).not.toHaveBeenCalled()

    await user.type(screen.getByTestId('position-note-input-AAPL'), '   ')
    await user.click(screen.getByTestId('position-note-submit-AAPL'))
    expect(onAdd).not.toHaveBeenCalled()
  })

  it('clears the textarea after a successful add', async () => {
    const onAdd = vi.fn().mockResolvedValue(undefined)
    const user = userEvent.setup()

    render(
      <PositionNotePopover
        instrumentId="AAPL"
        notes={[]}
        onAdd={onAdd}
        onDelete={vi.fn()}
        onClose={vi.fn()}
      />,
    )

    const textarea = screen.getByTestId(
      'position-note-input-AAPL',
    ) as HTMLTextAreaElement
    await user.type(textarea, 'Hello')
    await user.click(screen.getByTestId('position-note-submit-AAPL'))

    expect(textarea.value).toBe('')
  })

  it('calls onDelete with the note id when delete button is clicked', async () => {
    const onDelete = vi.fn().mockResolvedValue(undefined)
    const user = userEvent.setup()

    render(
      <PositionNotePopover
        instrumentId="AAPL"
        notes={[note({ id: 'note-42' })]}
        onAdd={vi.fn()}
        onDelete={onDelete}
        onClose={vi.fn()}
      />,
    )

    await user.click(screen.getByTestId('position-note-delete-note-42'))
    expect(onDelete).toHaveBeenCalledWith('note-42')
  })

  it('calls onClose when Escape is pressed', async () => {
    const onClose = vi.fn()
    const user = userEvent.setup()

    render(
      <PositionNotePopover
        instrumentId="AAPL"
        notes={[]}
        onAdd={vi.fn()}
        onDelete={vi.fn()}
        onClose={onClose}
      />,
    )

    await user.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalled()
  })
})

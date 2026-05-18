import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { SnapshotCompareControl } from './SnapshotCompareControl'

describe('SnapshotCompareControl', () => {
  it('renders an "Off" toggle plus the three snapshot presets', () => {
    render(<SnapshotCompareControl value={null} onChange={() => {}} />)

    expect(screen.getByRole('button', { name: 'Off' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '-15m' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '-1h' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'EOD yesterday' })).toBeInTheDocument()
  })

  it('marks the active preset with aria-pressed="true"', () => {
    render(<SnapshotCompareControl value="-1h" onChange={() => {}} />)

    expect(screen.getByRole('button', { name: '-1h' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: '-15m' })).toHaveAttribute('aria-pressed', 'false')
    expect(screen.getByRole('button', { name: 'Off' })).toHaveAttribute('aria-pressed', 'false')
  })

  it('emits the preset id when a preset is clicked', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()

    render(<SnapshotCompareControl value={null} onChange={onChange} />)
    await user.click(screen.getByRole('button', { name: '-15m' }))

    expect(onChange).toHaveBeenCalledExactlyOnceWith('-15m')
  })

  it('emits null when Off is clicked', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()

    render(<SnapshotCompareControl value="-1h" onChange={onChange} />)
    await user.click(screen.getByRole('button', { name: 'Off' }))

    expect(onChange).toHaveBeenCalledExactlyOnceWith(null)
  })
})

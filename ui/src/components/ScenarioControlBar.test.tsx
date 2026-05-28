import { render, screen, fireEvent } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ScenarioControlBar } from './ScenarioControlBar'

const defaultProps = {
  onRunAll: vi.fn(),
  loading: false,
  confidenceLevel: 'CL_95',
  onConfidenceLevelChange: vi.fn(),
  timeHorizonDays: '1',
  onTimeHorizonDaysChange: vi.fn(),
}

describe('ScenarioControlBar', () => {
  it('should render Run All button, confidence select, horizon select', () => {
    render(<ScenarioControlBar {...defaultProps} />)

    expect(screen.getByTestId('run-all-btn')).toBeInTheDocument()
    expect(screen.getByTestId('confidence-level-select')).toBeInTheDocument()
    expect(screen.getByTestId('time-horizon-select')).toBeInTheDocument()
  })

  it('should call onRunAll when Run All is clicked', () => {
    const onRunAll = vi.fn()
    render(<ScenarioControlBar {...defaultProps} onRunAll={onRunAll} />)

    fireEvent.click(screen.getByTestId('run-all-btn'))
    expect(onRunAll).toHaveBeenCalledOnce()
  })

  it('should call onConfidenceLevelChange when confidence is changed', () => {
    const onChange = vi.fn()
    render(<ScenarioControlBar {...defaultProps} onConfidenceLevelChange={onChange} />)

    fireEvent.change(screen.getByTestId('confidence-level-select'), { target: { value: 'CL_99' } })
    expect(onChange).toHaveBeenCalledWith('CL_99')
  })

  it('should render Custom Scenario button when handler provided', () => {
    render(<ScenarioControlBar {...defaultProps} onCustomScenario={vi.fn()} />)

    expect(screen.getByTestId('custom-scenario-btn')).toBeInTheDocument()
  })

  it('should not render Custom Scenario button when handler not provided', () => {
    render(<ScenarioControlBar {...defaultProps} />)

    expect(screen.queryByTestId('custom-scenario-btn')).not.toBeInTheDocument()
  })

  it('should disable Run All button while loading', () => {
    render(<ScenarioControlBar {...defaultProps} loading={true} />)

    expect(screen.getByTestId('run-all-btn')).toHaveTextContent('Running...')
  })

  describe('Reverse Stress button', () => {
    it('is disabled with a tooltip explaining the gate when no results exist', () => {
      render(
        <ScenarioControlBar
          {...defaultProps}
          onReverseStress={vi.fn()}
          hasResults={false}
        />,
      )

      const btn = screen.getByTestId('reverse-stress-btn')
      expect(btn).toBeDisabled()
      expect(btn).toHaveAttribute('title')
      expect(btn.getAttribute('title')).toMatch(/run all/i)
    })

    it('is enabled when results are available', () => {
      render(
        <ScenarioControlBar
          {...defaultProps}
          onReverseStress={vi.fn()}
          hasResults={true}
        />,
      )

      expect(screen.getByTestId('reverse-stress-btn')).not.toBeDisabled()
    })
  })

  describe('Manage Scenarios button', () => {
    it('has a tooltip describing its purpose', () => {
      render(
        <ScenarioControlBar
          {...defaultProps}
          onManageScenarios={vi.fn()}
        />,
      )

      const btn = screen.getByTestId('manage-scenarios-btn')
      expect(btn).toHaveAttribute('title')
      expect(btn.getAttribute('title')).toMatch(/governance/i)
    })
  })
})

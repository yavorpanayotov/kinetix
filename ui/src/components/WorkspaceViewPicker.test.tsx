import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { WorkspaceViewPicker } from './WorkspaceViewPicker'
import { DEFAULT_PREFERENCES, type SavedView } from '../hooks/useWorkspace'

function makeView(overrides: Partial<SavedView> = {}): SavedView {
  return {
    id: 'view-default',
    name: 'Default',
    prefs: DEFAULT_PREFERENCES,
    ...overrides,
  }
}

describe('WorkspaceViewPicker (plan §2.3)', () => {
  let promptSpy: ReturnType<typeof vi.spyOn>
  let confirmSpy: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    promptSpy = vi.spyOn(window, 'prompt').mockReturnValue(null)
    confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
  })

  it('shows the active view name on the toggle', () => {
    render(
      <WorkspaceViewPicker
        views={[makeView(), makeView({ id: 'view-2', name: 'Equities morning' })]}
        activeViewId="view-2"
        onSwitchView={vi.fn()}
        onSaveAsNewView={vi.fn()}
        onUpdateActiveView={vi.fn()}
        onDeleteView={vi.fn()}
        onRenameView={vi.fn()}
      />,
    )

    expect(screen.getByTestId('workspace-view-toggle')).toHaveTextContent('Equities morning')
  })

  it('opens a panel listing all views', async () => {
    const user = userEvent.setup()
    render(
      <WorkspaceViewPicker
        views={[makeView(), makeView({ id: 'view-2', name: 'Equities morning' })]}
        activeViewId="view-default"
        onSwitchView={vi.fn()}
        onSaveAsNewView={vi.fn()}
        onUpdateActiveView={vi.fn()}
        onDeleteView={vi.fn()}
        onRenameView={vi.fn()}
      />,
    )

    await user.click(screen.getByTestId('workspace-view-toggle'))

    const panel = screen.getByTestId('workspace-view-panel')
    expect(within(panel).getByText('Default')).toBeInTheDocument()
    expect(within(panel).getByText('Equities morning')).toBeInTheDocument()
  })

  it('calls onSwitchView when a non-active view option is clicked', async () => {
    const user = userEvent.setup()
    const onSwitchView = vi.fn()
    render(
      <WorkspaceViewPicker
        views={[makeView(), makeView({ id: 'view-2', name: 'Equities morning' })]}
        activeViewId="view-default"
        onSwitchView={onSwitchView}
        onSaveAsNewView={vi.fn()}
        onUpdateActiveView={vi.fn()}
        onDeleteView={vi.fn()}
        onRenameView={vi.fn()}
      />,
    )

    await user.click(screen.getByTestId('workspace-view-toggle'))
    await user.click(screen.getByTestId('workspace-view-option-view-2'))

    expect(onSwitchView).toHaveBeenCalledWith('view-2')
  })

  it('Save as new view prompts for a name and calls onSaveAsNewView', async () => {
    promptSpy.mockReturnValue('Credit stress monitor')
    const user = userEvent.setup()
    const onSaveAsNewView = vi.fn()
    render(
      <WorkspaceViewPicker
        views={[makeView()]}
        activeViewId="view-default"
        onSwitchView={vi.fn()}
        onSaveAsNewView={onSaveAsNewView}
        onUpdateActiveView={vi.fn()}
        onDeleteView={vi.fn()}
        onRenameView={vi.fn()}
      />,
    )

    await user.click(screen.getByTestId('workspace-view-toggle'))
    await user.click(screen.getByTestId('workspace-view-save-as-new'))

    expect(promptSpy).toHaveBeenCalled()
    expect(onSaveAsNewView).toHaveBeenCalledWith('Credit stress monitor')
  })

  it('Save as new view does nothing when the user cancels the prompt', async () => {
    promptSpy.mockReturnValue(null)
    const user = userEvent.setup()
    const onSaveAsNewView = vi.fn()
    render(
      <WorkspaceViewPicker
        views={[makeView()]}
        activeViewId="view-default"
        onSwitchView={vi.fn()}
        onSaveAsNewView={onSaveAsNewView}
        onUpdateActiveView={vi.fn()}
        onDeleteView={vi.fn()}
        onRenameView={vi.fn()}
      />,
    )

    await user.click(screen.getByTestId('workspace-view-toggle'))
    await user.click(screen.getByTestId('workspace-view-save-as-new'))

    expect(onSaveAsNewView).not.toHaveBeenCalled()
  })

  it('Update current view calls onUpdateActiveView', async () => {
    const user = userEvent.setup()
    const onUpdateActiveView = vi.fn()
    render(
      <WorkspaceViewPicker
        views={[makeView()]}
        activeViewId="view-default"
        onSwitchView={vi.fn()}
        onSaveAsNewView={vi.fn()}
        onUpdateActiveView={onUpdateActiveView}
        onDeleteView={vi.fn()}
        onRenameView={vi.fn()}
      />,
    )

    await user.click(screen.getByTestId('workspace-view-toggle'))
    await user.click(screen.getByTestId('workspace-view-update-current'))

    expect(onUpdateActiveView).toHaveBeenCalled()
  })

  it('Rename current view prompts and calls onRenameView with the active id', async () => {
    promptSpy.mockReturnValue('Renamed view')
    const user = userEvent.setup()
    const onRenameView = vi.fn()
    render(
      <WorkspaceViewPicker
        views={[makeView({ id: 'view-active' })]}
        activeViewId="view-active"
        onSwitchView={vi.fn()}
        onSaveAsNewView={vi.fn()}
        onUpdateActiveView={vi.fn()}
        onDeleteView={vi.fn()}
        onRenameView={onRenameView}
      />,
    )

    await user.click(screen.getByTestId('workspace-view-toggle'))
    await user.click(screen.getByTestId('workspace-view-rename-current'))

    expect(onRenameView).toHaveBeenCalledWith('view-active', 'Renamed view')
  })

  it('Delete current view confirms first and calls onDeleteView with the active id', async () => {
    confirmSpy.mockReturnValue(true)
    const user = userEvent.setup()
    const onDeleteView = vi.fn()
    render(
      <WorkspaceViewPicker
        views={[makeView({ id: 'view-active' }), makeView({ id: 'view-2', name: 'Other' })]}
        activeViewId="view-active"
        onSwitchView={vi.fn()}
        onSaveAsNewView={vi.fn()}
        onUpdateActiveView={vi.fn()}
        onDeleteView={onDeleteView}
        onRenameView={vi.fn()}
      />,
    )

    await user.click(screen.getByTestId('workspace-view-toggle'))
    await user.click(screen.getByTestId('workspace-view-delete-current'))

    expect(confirmSpy).toHaveBeenCalled()
    expect(onDeleteView).toHaveBeenCalledWith('view-active')
  })

  it('does not delete when the user cancels confirm', async () => {
    confirmSpy.mockReturnValue(false)
    const user = userEvent.setup()
    const onDeleteView = vi.fn()
    render(
      <WorkspaceViewPicker
        views={[makeView({ id: 'view-active' }), makeView({ id: 'view-2', name: 'Other' })]}
        activeViewId="view-active"
        onSwitchView={vi.fn()}
        onSaveAsNewView={vi.fn()}
        onUpdateActiveView={vi.fn()}
        onDeleteView={onDeleteView}
        onRenameView={vi.fn()}
      />,
    )

    await user.click(screen.getByTestId('workspace-view-toggle'))
    await user.click(screen.getByTestId('workspace-view-delete-current'))

    expect(onDeleteView).not.toHaveBeenCalled()
  })

  it('disables Delete when only one view exists', async () => {
    const user = userEvent.setup()
    render(
      <WorkspaceViewPicker
        views={[makeView()]}
        activeViewId="view-default"
        onSwitchView={vi.fn()}
        onSaveAsNewView={vi.fn()}
        onUpdateActiveView={vi.fn()}
        onDeleteView={vi.fn()}
        onRenameView={vi.fn()}
      />,
    )

    await user.click(screen.getByTestId('workspace-view-toggle'))
    expect(screen.getByTestId('workspace-view-delete-current')).toBeDisabled()
  })
})

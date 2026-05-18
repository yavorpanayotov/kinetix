import { useRef, useState, useCallback } from 'react'
import { ChevronDown, Check, Plus, Save, Pencil, Trash2 } from 'lucide-react'
import { useClickOutside } from '../hooks/useClickOutside'
import type { SavedView } from '../hooks/useWorkspace'

/**
 * Plan §2.3 — Saved views picker.
 *
 * Risk managers monitor 3-5 "modes" daily (e.g. "Equities morning check",
 * "Credit stress monitor"). Each named view captures the active tab,
 * sub-tab, hierarchy selection, column visibility, time range, and panel
 * collapse state. This component lets the user switch between views and
 * manage the list (save as new, update current, rename, delete).
 *
 * The picker is a presentational component — all view storage lives in
 * `useWorkspace`. App.tsx wires the callbacks.
 */
export interface WorkspaceViewPickerProps {
  views: SavedView[]
  activeViewId: string | null
  onSwitchView: (id: string) => void
  onSaveAsNewView: (name: string) => void
  onUpdateActiveView: () => void
  onDeleteView: (id: string) => void
  onRenameView: (id: string, name: string) => void
}

export function WorkspaceViewPicker(props: WorkspaceViewPickerProps) {
  const {
    views,
    activeViewId,
    onSwitchView,
    onSaveAsNewView,
    onUpdateActiveView,
    onDeleteView,
    onRenameView,
  } = props

  const [open, setOpen] = useState(false)
  const containerRef = useRef<HTMLDivElement>(null)
  const toggleRef = useRef<HTMLButtonElement>(null)

  useClickOutside(containerRef, () => setOpen(false))

  const activeView = views.find((v) => v.id === activeViewId) ?? views[0] ?? null
  const activeName = activeView?.name ?? 'Workspace'
  const canDelete = views.length > 1

  const close = useCallback(() => {
    setOpen(false)
    toggleRef.current?.focus()
  }, [])

  const handleSwitch = useCallback(
    (id: string) => {
      if (id !== activeViewId) onSwitchView(id)
      close()
    },
    [activeViewId, onSwitchView, close],
  )

  const handleSaveAsNew = useCallback(() => {
    const name = window.prompt('Name for the new view:', '')
    if (name === null) return
    const trimmed = name.trim()
    if (!trimmed) return
    onSaveAsNewView(trimmed)
    close()
  }, [onSaveAsNewView, close])

  const handleUpdate = useCallback(() => {
    onUpdateActiveView()
    close()
  }, [onUpdateActiveView, close])

  const handleRename = useCallback(() => {
    if (!activeView) return
    const name = window.prompt('New name for this view:', activeView.name)
    if (name === null) return
    const trimmed = name.trim()
    if (!trimmed) return
    onRenameView(activeView.id, trimmed)
    close()
  }, [activeView, onRenameView, close])

  const handleDelete = useCallback(() => {
    if (!activeView || !canDelete) return
    if (!window.confirm(`Delete view "${activeView.name}"? This cannot be undone.`)) return
    onDeleteView(activeView.id)
    close()
  }, [activeView, canDelete, onDeleteView, close])

  return (
    <div ref={containerRef} className="relative" data-testid="workspace-view-picker">
      <button
        ref={toggleRef}
        data-testid="workspace-view-toggle"
        onClick={() => setOpen((v) => !v)}
        className="flex items-center gap-1.5 bg-surface-800 border border-surface-700 text-white rounded-md px-2.5 py-1 text-xs hover:bg-surface-700 focus:ring-2 focus:ring-primary-500 transition-colors"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={`Saved view: ${activeName}`}
        title="Saved views"
      >
        <Save className="h-3.5 w-3.5 text-slate-400" aria-hidden="true" />
        <span className="max-w-[10rem] truncate">{activeName}</span>
        <ChevronDown className={`h-3.5 w-3.5 text-slate-400 transition-transform ${open ? 'rotate-180' : ''}`} aria-hidden="true" />
      </button>

      {open && (
        <div
          data-testid="workspace-view-panel"
          role="menu"
          aria-label="Saved views menu"
          className="absolute right-0 top-full mt-1 w-64 bg-surface-800 border border-surface-700 rounded-lg shadow-xl z-50 py-1 text-sm"
        >
          <div className="px-3 py-1.5 text-[10px] tracking-wider uppercase text-slate-500 select-none">
            Switch view
          </div>
          {views.map((view) => {
            const isActive = view.id === activeView?.id
            return (
              <button
                key={view.id}
                data-testid={`workspace-view-option-${view.id}`}
                role="menuitemradio"
                aria-checked={isActive}
                onClick={() => handleSwitch(view.id)}
                className={`flex w-full items-center justify-between gap-2 px-3 py-1.5 text-left transition-colors ${
                  isActive ? 'bg-surface-700 text-white' : 'text-slate-200 hover:bg-surface-700/50'
                }`}
              >
                <span className="truncate">{view.name}</span>
                {isActive && <Check className="h-3.5 w-3.5 text-primary-400 flex-shrink-0" aria-hidden="true" />}
              </button>
            )
          })}
          <div className="border-t border-surface-700 my-1" />
          <button
            data-testid="workspace-view-update-current"
            role="menuitem"
            onClick={handleUpdate}
            disabled={!activeView}
            className="flex w-full items-center gap-2 px-3 py-1.5 text-left text-slate-200 hover:bg-surface-700/50 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            <Save className="h-3.5 w-3.5 text-slate-400" aria-hidden="true" />
            <span>Update current view</span>
          </button>
          <button
            data-testid="workspace-view-save-as-new"
            role="menuitem"
            onClick={handleSaveAsNew}
            className="flex w-full items-center gap-2 px-3 py-1.5 text-left text-slate-200 hover:bg-surface-700/50"
          >
            <Plus className="h-3.5 w-3.5 text-slate-400" aria-hidden="true" />
            <span>Save as new view&hellip;</span>
          </button>
          <button
            data-testid="workspace-view-rename-current"
            role="menuitem"
            onClick={handleRename}
            disabled={!activeView}
            className="flex w-full items-center gap-2 px-3 py-1.5 text-left text-slate-200 hover:bg-surface-700/50 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            <Pencil className="h-3.5 w-3.5 text-slate-400" aria-hidden="true" />
            <span>Rename current view&hellip;</span>
          </button>
          <button
            data-testid="workspace-view-delete-current"
            role="menuitem"
            onClick={handleDelete}
            disabled={!canDelete}
            className="flex w-full items-center gap-2 px-3 py-1.5 text-left text-rose-300 hover:bg-rose-900/30 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            <Trash2 className="h-3.5 w-3.5" aria-hidden="true" />
            <span>Delete current view</span>
          </button>
        </div>
      )}
    </div>
  )
}

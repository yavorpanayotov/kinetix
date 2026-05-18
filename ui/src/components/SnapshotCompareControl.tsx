import { SNAPSHOT_PRESETS, type SnapshotPreset } from '../utils/snapshotCompare'

interface SnapshotCompareControlProps {
  /**
   * Currently active preset. `null` means the user is not comparing — no
   * delta overlay is rendered on the dashboard.
   */
  value: SnapshotPreset | null
  /**
   * Called with the new preset (or `null` for "Off").
   */
  onChange: (next: SnapshotPreset | null) => void
}

const BASE_BUTTON =
  'px-2.5 py-1 text-xs font-medium border border-slate-200 dark:border-surface-700 transition-colors'
const ACTIVE_BUTTON = 'bg-primary-100 text-primary-700 dark:bg-primary-900/40 dark:text-primary-200'
const INACTIVE_BUTTON =
  'text-slate-500 dark:text-slate-400 hover:bg-slate-50 dark:hover:bg-surface-800'

/**
 * Plan §8.6 — "Compare with snapshot" segmented button on the Risk
 * Dashboard. Picks one of three time-shifted snapshots (-15m, -1h, EOD
 * yesterday) so the user can answer "what just changed?" without leaving
 * the dashboard. "Off" disables the overlay.
 */
export function SnapshotCompareControl({ value, onChange }: SnapshotCompareControlProps) {
  return (
    <div
      role="group"
      aria-label="Compare with snapshot"
      data-testid="snapshot-compare-control"
      className="inline-flex items-center rounded-md overflow-hidden"
    >
      <span className="px-2 text-xs text-slate-500 dark:text-slate-400">Compare:</span>
      <button
        type="button"
        aria-pressed={value === null}
        onClick={() => onChange(null)}
        className={`${BASE_BUTTON} rounded-l-md ${value === null ? ACTIVE_BUTTON : INACTIVE_BUTTON}`}
        data-testid="snapshot-compare-off"
      >
        Off
      </button>
      {SNAPSHOT_PRESETS.map((preset, idx) => {
        const isActive = value === preset.id
        const isLast = idx === SNAPSHOT_PRESETS.length - 1
        return (
          <button
            key={preset.id}
            type="button"
            aria-pressed={isActive}
            onClick={() => onChange(preset.id)}
            className={`${BASE_BUTTON} ${isLast ? 'rounded-r-md' : ''} ${isActive ? ACTIVE_BUTTON : INACTIVE_BUTTON}`}
            data-testid={`snapshot-compare-${preset.id}`}
          >
            {preset.label}
          </button>
        )
      })}
    </div>
  )
}

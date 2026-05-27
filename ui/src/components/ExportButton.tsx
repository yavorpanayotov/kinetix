import { useId } from 'react'

// Disabled Export button with an explanatory tooltip (kx-4u9h).
//
// When a table is filtered down to zero rows, an active Export button
// either downloads an empty CSV or fails silently — both confusing. We
// disable it visually and via aria, and attach a tooltip describing why
// so the affordance reads as intentional rather than broken.

interface ExportButtonProps {
  rowCount: number
  onExport: () => void
}

export function ExportButton({ rowCount, onExport }: ExportButtonProps) {
  const disabled = rowCount === 0
  const tooltipId = useId()

  const classes = [
    'text-xs px-2 py-1 border rounded',
    'border-slate-300 dark:border-slate-600',
    disabled ? 'opacity-50 cursor-not-allowed' : 'hover:bg-slate-50 dark:hover:bg-slate-700',
  ].join(' ')

  return (
    <span className="relative inline-block">
      <button
        type="button"
        disabled={disabled}
        aria-disabled={disabled}
        aria-describedby={disabled ? tooltipId : undefined}
        onClick={disabled ? undefined : onExport}
        className={classes}
      >
        Export
      </button>
      {disabled && (
        <span
          role="tooltip"
          id={tooltipId}
          className="absolute z-10 top-full mt-1 left-1/2 -translate-x-1/2 whitespace-nowrap text-xs px-2 py-1 rounded bg-slate-800 text-slate-100"
        >
          No rows to export
        </span>
      )}
    </span>
  )
}

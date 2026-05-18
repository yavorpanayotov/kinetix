import { useState, useRef, useEffect, useCallback } from 'react'
import { Activity, AlertCircle, AlertTriangle, CheckCircle } from 'lucide-react'
import { useClickOutside } from '../hooks/useClickOutside'
import type { DataQualityStatus } from '../types'

interface DataQualityIndicatorProps {
  status: DataQualityStatus | null
  loading: boolean
}

export function DataQualityIndicator({ status, loading }: DataQualityIndicatorProps) {
  const [open, setOpen] = useState(false)
  const containerRef = useRef<HTMLDivElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const dropdownRef = useRef<HTMLDivElement>(null)
  const closeDropdown = useCallback(() => setOpen(false), [])
  useClickOutside(containerRef, closeDropdown)

  useEffect(() => {
    if (!open) return
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [open])

  const wasOpenRef = useRef(false)
  useEffect(() => {
    if (open) {
      dropdownRef.current?.focus()
      wasOpenRef.current = true
    } else if (wasOpenRef.current) {
      // Dropdown just closed — return focus to the trigger.
      triggerRef.current?.focus()
      wasOpenRef.current = false
    }
  }, [open])

  if (loading) {
    return (
      <div data-testid="data-quality-loading" className="text-slate-400 text-sm">
        <Activity className="h-4 w-4 animate-pulse" />
      </div>
    )
  }

  if (!status) return null

  const colorClass =
    status.overall === 'CRITICAL'
      ? 'text-red-500'
      : status.overall === 'WARNING'
        ? 'text-amber-500'
        : 'text-green-500'

  const statusTestId =
    status.overall === 'CRITICAL'
      ? 'dq-status-critical'
      : status.overall === 'WARNING'
        ? 'dq-status-warning'
        : 'dq-status-ok'

  return (
    <div ref={containerRef} className="relative" data-testid="data-quality-indicator" onClick={() => setOpen((prev) => !prev)}>
      <button
        ref={triggerRef}
        className={`p-1.5 rounded-md hover:bg-surface-800 transition-colors ${colorClass}`}
        aria-label="Data quality status"
        aria-haspopup="dialog"
        aria-expanded={open}
      >
        {status.overall === 'CRITICAL' ? (
          <AlertCircle data-testid={statusTestId} className="h-4 w-4" />
        ) : status.overall === 'WARNING' ? (
          <AlertTriangle data-testid={statusTestId} className="h-4 w-4" />
        ) : (
          <CheckCircle data-testid={statusTestId} className="h-4 w-4" />
        )}
      </button>

      {open && (
        <div
          ref={dropdownRef}
          role="dialog"
          aria-label="Data quality details"
          tabIndex={-1}
          className="absolute right-0 top-full mt-2 w-80 bg-surface-800 border border-surface-700 rounded-lg shadow-xl z-50 p-3 focus:outline-none"
          data-testid="data-quality-dropdown"
        >
          <div className="text-sm font-medium text-white mb-2">Data Quality</div>
          <div className="space-y-2">
            {status.checks.map((check) => (
              <div
                key={check.name}
                className="flex items-start gap-2 text-sm"
              >
                {check.status === 'CRITICAL' ? (
                  <AlertCircle className="mt-0.5 h-3.5 w-3.5 text-red-500 flex-shrink-0" />
                ) : check.status === 'WARNING' ? (
                  <AlertTriangle className="mt-0.5 h-3.5 w-3.5 text-amber-500 flex-shrink-0" />
                ) : (
                  <CheckCircle className="mt-0.5 h-3.5 w-3.5 text-green-500 flex-shrink-0" />
                )}
                <div>
                  <div className="text-white font-medium">{check.name}</div>
                  <div className="text-slate-400 text-xs">{check.message}</div>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}

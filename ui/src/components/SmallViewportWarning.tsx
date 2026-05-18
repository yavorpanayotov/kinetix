import { useEffect, useState } from 'react'
import { Monitor } from 'lucide-react'

// Plan §9 — Kinetix is desktop-only. The platform's dense, multi-pane layouts
// (tab clusters, ticker strip, breach banner, RiskTab grids) collapse below a
// ~1280px floor. Rather than maintain partial `md:` / `lg:` accommodations
// indefinitely, render a friendly full-screen warning when the viewport is
// narrower than this threshold; flip back to the app when the user resizes.
//
// The threshold is exposed as a named constant so it stays easy to tweak in
// one place and (eventually) align with the cleanup of the partial Tailwind
// `md:` / `lg:` / `hidden sm:` classes still scattered across the app.
export const MIN_VIEWPORT_WIDTH_PX = 1280

function isBelowDesktopFloor(): boolean {
  // SSR / non-browser safety: if `window` isn't available we assume desktop
  // and let the app render. The component is client-only in practice (it
  // mounts under React's normal client tree), but the guard is cheap.
  if (typeof window === 'undefined') return false
  return window.innerWidth < MIN_VIEWPORT_WIDTH_PX
}

export function SmallViewportWarning() {
  const [tooSmall, setTooSmall] = useState<boolean>(() => isBelowDesktopFloor())

  useEffect(() => {
    function handleResize() {
      setTooSmall(isBelowDesktopFloor())
    }
    window.addEventListener('resize', handleResize)
    return () => window.removeEventListener('resize', handleResize)
  }, [])

  if (!tooSmall) return null

  return (
    <div
      data-testid="small-viewport-warning"
      role="alertdialog"
      aria-modal="true"
      aria-labelledby="small-viewport-warning-title"
      aria-describedby="small-viewport-warning-description"
      className="fixed inset-0 z-[9999] flex items-center justify-center bg-surface-900 text-white px-6 py-8"
    >
      <div className="max-w-md text-center flex flex-col items-center gap-4">
        <Monitor className="h-12 w-12 text-primary-400" aria-hidden="true" />
        <h1
          id="small-viewport-warning-title"
          className="text-xl font-semibold tracking-tight"
        >
          Kinetix is desktop-only
        </h1>
        <p
          id="small-viewport-warning-description"
          className="text-sm text-slate-300 leading-relaxed"
        >
          Please use a screen at least {MIN_VIEWPORT_WIDTH_PX}px wide.
          The risk dashboards, blotters, and scenario tools are built for a
          desktop-class display and do not condense well below this threshold.
        </p>
        <p className="text-xs text-slate-400">
          Resize this window or open Kinetix on a larger display to continue.
        </p>
      </div>
    </div>
  )
}

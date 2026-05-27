// Color-blind safe shape icons for Greeks columns (kx-q9l0).
//
// Greeks indicators in tables (Delta / Gamma / Vega / Theta direction
// markers) previously relied on red/green color alone to convey
// "positive" vs "negative" sensitivity. WCAG 1.4.1 (Use of Color) and
// the trader feedback both flag that this excludes deuteranopic and
// protanopic users. We add a shape glyph (▲ for positive, ▼ for
// negative, ● for neutral) so the meaning is conveyed without color.

export type GreekDirection = 'up' | 'down' | 'neutral'

interface GreeksShapeIconProps {
  direction: GreekDirection
  /** Human-readable label for screen readers (e.g. "Delta increased"). */
  label: string
}

const SHAPE: Record<GreekDirection, string> = {
  up: '▲', // BLACK UP-POINTING TRIANGLE
  down: '▼', // BLACK DOWN-POINTING TRIANGLE
  neutral: '●', // BLACK CIRCLE
}

const COLOR_CLASS: Record<GreekDirection, string> = {
  up: 'text-emerald-600',
  down: 'text-rose-600',
  neutral: 'text-slate-500',
}

export function GreeksShapeIcon({ direction, label }: GreeksShapeIconProps) {
  return (
    <span
      role="img"
      aria-label={label}
      data-direction={direction}
      data-testid="greeks-shape-icon"
      className={`inline-block font-bold ${COLOR_CLASS[direction]}`}
    >
      {SHAPE[direction]}
    </span>
  )
}

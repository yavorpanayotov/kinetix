import { useId, type InputHTMLAttributes, type ReactNode } from 'react'

// Reusable form input that auto-pairs <label htmlFor> with <input id> (kx-wwip).
//
// WCAG 1.3.1 / 4.1.2 require every form control to have a programmatic
// label so screen readers announce the field name when focus arrives. The
// most reliable way to enforce that across a large UI is to funnel inputs
// through a single component that owns the id generation — consumers can't
// forget the htmlFor association if the component does it for them.
//
// `useId` gives us a stable, SSR-safe, render-deterministic id without
// requiring callers to supply one. Consumers may still override with their
// own `id` prop when they need a stable selector (tests, query params).

interface FormLabelledInputProps extends InputHTMLAttributes<HTMLInputElement> {
  label: ReactNode
  hint?: ReactNode
}

export function FormLabelledInput({
  label,
  hint,
  id,
  className,
  ...inputProps
}: FormLabelledInputProps) {
  const generatedId = useId()
  const effectiveId = id ?? generatedId
  const hintId = hint ? `${effectiveId}-hint` : undefined

  return (
    <div className="flex flex-col gap-1">
      <label
        htmlFor={effectiveId}
        className="text-xs font-medium text-slate-700 dark:text-slate-300"
      >
        {label}
      </label>
      <input
        id={effectiveId}
        aria-describedby={hintId}
        className={`rounded border border-slate-300 dark:border-slate-600 px-2 py-1 text-sm ${className ?? ''}`}
        {...inputProps}
      />
      {hint && (
        <div id={hintId} className="text-xs text-slate-500 dark:text-slate-400">
          {hint}
        </div>
      )}
    </div>
  )
}

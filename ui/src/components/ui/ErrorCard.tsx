import { RefreshCw } from 'lucide-react'

interface ErrorCardProps {
  message: string
  onRetry?: () => void
  'data-testid'?: string
}

/**
 * Standardised error rendering for failed loads / actions.
 *
 * Visual treatment follows the existing well-styled error in MarginPanel:
 * tinted red background, red border, red text, optional inline retry link.
 *
 * Use this in place of ad-hoc `<p className="text-red-600">{error}</p>`
 * fragments. The container is `role="alert"` so screen readers announce
 * the failure.
 */
export function ErrorCard({ message, onRetry, ...rest }: ErrorCardProps) {
  return (
    <div
      role="alert"
      data-testid={rest['data-testid']}
      className="flex items-center justify-between gap-4 rounded-md bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-700 px-4 py-3 text-sm text-red-700 dark:text-red-400"
    >
      <span>{message}</span>
      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          className="ml-4 flex items-center gap-1 text-xs font-medium underline hover:no-underline"
        >
          <RefreshCw className="h-3 w-3" /> Retry
        </button>
      )}
    </div>
  )
}

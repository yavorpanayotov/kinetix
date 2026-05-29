import { useEffect, useState } from 'react'
import { ShieldAlert, CheckCircle2 } from 'lucide-react'

interface BlockTradesDialogProps {
  /** Counterparty whose expired agreement triggered the remediation. */
  counterpartyId: string
  onClose: () => void
}

/**
 * Remediation workflow for a counterparty whose ISDA / netting agreement has
 * expired (trader-review P2 #27). Lets a credit officer record the intent to
 * block new trades and open a remediation ticket without leaving the
 * Counterparty Risk screen.
 *
 * The confirm step is currently a self-contained client-side acknowledgement:
 * there is no regulatory-service ticketing API contract yet, so we surface the
 * success state in-app rather than invent a backend endpoint. Wiring the
 * confirm step to a real regulatory-service ticket is a tracked follow-up.
 */
export function BlockTradesDialog({ counterpartyId, onClose }: BlockTradesDialogProps) {
  const [confirmed, setConfirmed] = useState(false)

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [onClose])

  return (
    <div
      data-testid="block-trades-overlay"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
      onClick={onClose}
    >
      <div
        data-testid="block-trades-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="block-trades-title"
        className="bg-white dark:bg-slate-800 rounded-lg shadow-xl max-w-md w-full mx-4 p-6 outline-none"
        onClick={(e) => e.stopPropagation()}
      >
        {confirmed ? (
          <div data-testid="block-trades-success" className="space-y-3">
            <div className="flex items-center gap-2 text-emerald-600 dark:text-emerald-400">
              <CheckCircle2 className="h-5 w-5" aria-hidden="true" />
              <h3 id="block-trades-title" className="text-lg font-semibold text-slate-800 dark:text-slate-100">
                Remediation ticket opened
              </h3>
            </div>
            <p className="text-sm text-slate-600 dark:text-slate-400">
              New trades with <span className="font-mono">{counterpartyId}</span> are flagged for blocking
              and a remediation ticket has been recorded. Notify the credit desk to renew the agreement.
            </p>
            <div className="flex justify-end">
              <button
                type="button"
                data-testid="block-trades-done"
                onClick={onClose}
                className="px-3 py-1.5 text-sm font-medium bg-indigo-600 hover:bg-indigo-500 text-white rounded-md transition-colors"
              >
                Done
              </button>
            </div>
          </div>
        ) : (
          <div className="space-y-3">
            <div className="flex items-center gap-2 text-red-600 dark:text-red-400">
              <ShieldAlert className="h-5 w-5" aria-hidden="true" />
              <h3 id="block-trades-title" className="text-lg font-semibold text-slate-800 dark:text-slate-100">
                Block new trades — {counterpartyId}
              </h3>
            </div>
            <p className="text-sm text-slate-600 dark:text-slate-400">
              The ISDA / netting agreement for <span className="font-mono">{counterpartyId}</span> has expired.
              Confirm to flag new trades for blocking and open a remediation ticket for the credit desk.
            </p>
            <div className="flex justify-end gap-3">
              <button
                type="button"
                data-testid="block-trades-cancel"
                onClick={onClose}
                className="px-3 py-1.5 text-sm font-medium text-slate-700 dark:text-slate-300 border border-slate-300 dark:border-slate-600 rounded-md hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
              >
                Cancel
              </button>
              <button
                type="button"
                data-testid="block-trades-confirm"
                onClick={() => setConfirmed(true)}
                className="px-3 py-1.5 text-sm font-medium bg-red-600 hover:bg-red-500 text-white rounded-md transition-colors"
              >
                Block &amp; open ticket
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

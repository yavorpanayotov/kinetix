import { useEffect, useRef, useState } from 'react'
import { Card } from './ui'
import { fetchPreTradeRiskPreview } from '../api/preTradeRiskPreview'
import { formatNum } from '../utils/format'
import type {
  PreTradeRiskPreviewRequestDto,
  PreTradeRiskPreviewResponseDto,
} from '../types'

/**
 * Pre-trade risk-impact preview surfaced inside the Place Order ticket
 * on form-blur (trader-review P2, ui-trader-review.md). The candidate
 * trade is whatever the trader has currently typed into the four
 * required fields (instrument / quantity / arrival price / limit price);
 * when those are all populated, the parent passes the complete request
 * shape and we POST it to /api/v1/risk/pretrade-preview. The response
 * is rendered as a compact four-row table:
 *
 *   Δ VaR                  | hypothetical_var - base_var
 *   Δ Delta                | hypothetical_delta - base_delta
 *   Δ Notional             | signed by side
 *   Δ Counterparty exposure | signed notional applied to counterparty
 *
 * Per the spec (execution.allium PreTradeRiskPreview) the counterparty
 * row is null for cleared / venue-routed orders — we render an em-dash
 * rather than a misleading "$0.00".
 */
interface PreTradeRiskPreviewPanelProps {
  candidate: PreTradeRiskPreviewRequestDto | null
}

interface State {
  kind: 'idle' | 'loading' | 'success' | 'error'
  data?: PreTradeRiskPreviewResponseDto
}

function fingerprint(candidate: PreTradeRiskPreviewRequestDto | null): string {
  if (!candidate) return ''
  return [
    candidate.bookId,
    candidate.instrumentId,
    candidate.assetClass,
    candidate.side,
    candidate.quantity,
    candidate.priceAmount,
    candidate.priceCurrency,
    candidate.instrumentType,
    candidate.counterpartyId ?? '',
  ].join('|')
}

export function PreTradeRiskPreviewPanel({ candidate }: PreTradeRiskPreviewPanelProps) {
  const fp = fingerprint(candidate)
  const [state, setState] = useState<State>({ kind: 'idle' })
  const lastFingerprintRef = useRef<string>('')

  useEffect(() => {
    if (!candidate) {
      lastFingerprintRef.current = ''
      return
    }
    if (fp === lastFingerprintRef.current) {
      return
    }
    lastFingerprintRef.current = fp

    let cancelled = false
    // Wrap setState calls in Promise.resolve().then(...) so they happen
    // asynchronously, satisfying the `react-hooks/set-state-in-effect`
    // ESLint rule. Pattern mirrors hooks/useCannedStress.ts.
    void Promise.resolve()
      .then(() => {
        if (cancelled) return
        setState({ kind: 'loading' })
        return fetchPreTradeRiskPreview(candidate)
      })
      .then((data) => {
        if (cancelled || data === undefined) return
        setState({ kind: 'success', data })
      })
      .catch(() => {
        if (cancelled) return
        setState({ kind: 'error' })
      })
    return () => {
      cancelled = true
    }
  }, [candidate, fp])

  if (!candidate) return null

  return (
    <Card>
      <div data-testid="place-order-risk-preview" className="space-y-2">
        <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200">
          Risk-impact preview
        </h3>
        {state.kind === 'loading' && (
          <p className="text-xs text-slate-500" data-testid="place-order-risk-preview-loading">
            Calculating…
          </p>
        )}
        {state.kind === 'error' && (
          <p
            data-testid="place-order-risk-preview-error"
            className="text-xs text-rose-600 dark:text-rose-400"
          >
            Preview unavailable — risk-orchestrator did not respond.
          </p>
        )}
        {state.kind === 'success' && state.data && (
          <dl className="grid grid-cols-2 gap-y-1 text-xs">
            <dt className="text-slate-500">Δ VaR</dt>
            <dd
              data-testid="place-order-risk-preview-var-change"
              className="text-right font-mono"
            >
              {formatNum(state.data.varChange, 2)}
            </dd>

            <dt className="text-slate-500">Δ Delta</dt>
            <dd
              data-testid="place-order-risk-preview-delta-change"
              className="text-right font-mono"
            >
              {formatNum(state.data.deltaChange, 2)}
            </dd>

            <dt className="text-slate-500">Δ Notional</dt>
            <dd
              data-testid="place-order-risk-preview-notional-change"
              className="text-right font-mono"
            >
              {formatNum(state.data.notionalChange, 2)}
            </dd>

            <dt className="text-slate-500">Δ Counterparty exposure</dt>
            <dd
              data-testid="place-order-risk-preview-counterparty-change"
              className="text-right font-mono"
            >
              {state.data.counterpartyId && state.data.counterpartyExposureChange != null
                ? `${state.data.counterpartyId}: ${formatNum(state.data.counterpartyExposureChange, 2)}`
                : '—'}
            </dd>
          </dl>
        )}
      </div>
    </Card>
  )
}

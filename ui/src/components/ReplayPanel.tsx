import { useEffect } from 'react'
import { RefreshCw, CheckCircle2, XCircle, AlertTriangle } from 'lucide-react'
import type { RunManifestDto, ReplayResponseDto } from '../types'
import { Spinner } from './ui'
import { useReplay } from '../hooks/useReplay'
import { formatMoney } from '../utils/format'

interface ReplayPanelProps {
  jobId: string
  onClose?: () => void
}

function DigestBadge({ matched }: { matched: boolean }) {
  if (matched) {
    return (
      <span data-testid="replay-digest-match" className="inline-flex items-center gap-1 text-xs font-medium text-emerald-700 bg-emerald-50 px-2 py-0.5 rounded-full">
        <CheckCircle2 className="h-3 w-3" /> MATCHED
      </span>
    )
  }
  return (
    <span data-testid="replay-digest-mismatch" className="inline-flex items-center gap-1 text-xs font-medium text-red-700 bg-red-50 px-2 py-0.5 rounded-full">
      <XCircle className="h-3 w-3" /> MISMATCHED
    </span>
  )
}

function ManifestSummary({ manifest }: { manifest: RunManifestDto }) {
  return (
    <div data-testid="manifest-section" className="space-y-2">
      <div className="grid grid-cols-4 gap-x-6 gap-y-1 text-xs">
        <div>
          <span className="text-slate-500">Model Version</span>
          <p data-testid="manifest-model-version" className="font-mono text-slate-700">{manifest.modelVersion || '(not captured)'}</p>
        </div>
        <div>
          <span className="text-slate-500">Calculation</span>
          <p className="text-slate-700">{manifest.calculationType}</p>
        </div>
        <div>
          <span className="text-slate-500">Confidence</span>
          <p className="text-slate-700">{manifest.confidenceLevel}</p>
        </div>
        <div>
          <span className="text-slate-500">Status</span>
          <p data-testid="manifest-status" className={`font-medium ${manifest.status === 'PARTIAL' ? 'text-amber-600' : 'text-slate-700'}`}>
            {manifest.status}
            {manifest.status === 'PARTIAL' && (
              <AlertTriangle data-testid="manifest-partial-warning" className="h-3 w-3 ml-1 inline text-amber-500" />
            )}
          </p>
        </div>
        <div>
          <span className="text-slate-500">Positions</span>
          <p className="text-slate-700">{manifest.positionCount}</p>
        </div>
        <div>
          <span className="text-slate-500">Simulations</span>
          <p className="text-slate-700">{manifest.numSimulations.toLocaleString()}</p>
        </div>
        <div>
          <span className="text-slate-500">Captured</span>
          <p className="text-slate-700">{new Date(manifest.capturedAt).toLocaleTimeString()}</p>
        </div>
        {manifest.monteCarloSeed !== 0 && (
          <div>
            <span className="text-slate-500">MC Seed</span>
            <p className="font-mono text-slate-700">{manifest.monteCarloSeed}</p>
          </div>
        )}
      </div>
      {(manifest.varValue != null || manifest.expectedShortfall != null) && (
        <div className="flex gap-6 pt-1 border-t border-slate-100">
          {manifest.varValue != null && (
            <div data-testid="manifest-original-var">
              <span className="text-xs text-slate-500">Original VaR</span>
              <p className="text-sm font-mono font-semibold text-slate-700">{formatMoney(manifest.varValue.toFixed(2), 'USD')}</p>
            </div>
          )}
          {manifest.expectedShortfall != null && (
            <div data-testid="manifest-original-es">
              <span className="text-xs text-slate-500">Original ES</span>
              <p className="text-sm font-mono font-semibold text-slate-700">{formatMoney(manifest.expectedShortfall.toFixed(2), 'USD')}</p>
            </div>
          )}
        </div>
      )}
      <details className="text-xs">
        <summary className="text-slate-500 cursor-pointer hover:text-slate-700">Digests</summary>
        <div className="mt-1 space-y-0.5 font-mono text-slate-500">
          <p>Input: {manifest.inputDigest}</p>
          <p>Position: {manifest.positionDigest}</p>
          <p>Market Data: {manifest.marketDataDigest}</p>
          {manifest.outputDigest && <p>Output: {manifest.outputDigest}</p>}
        </div>
      </details>
    </div>
  )
}

function ReplayComparison({ result }: { result: ReplayResponseDto }) {
  const varDelta = (result.replayVarValue ?? 0) - (result.originalVarValue ?? 0)
  const esDelta = (result.replayExpectedShortfall ?? 0) - (result.originalExpectedShortfall ?? 0)
  const nearZero = Math.abs(varDelta) < 0.01 && Math.abs(esDelta) < 0.01

  return (
    <div data-testid="replay-result-panel" className="space-y-3">
      <div className="grid grid-cols-2 gap-4">
        <div className="bg-white rounded border border-slate-200 p-3">
          <h5 className="text-xs font-medium text-slate-500 mb-1">ORIGINAL</h5>
          <div className="space-y-1">
            <div data-testid="replay-original-var">
              <span className="text-xs text-slate-500">VaR</span>
              <p className="text-sm font-mono font-semibold">{result.originalVarValue != null ? formatMoney(result.originalVarValue.toFixed(2), 'USD') : '-'}</p>
            </div>
            <div data-testid="replay-original-es">
              <span className="text-xs text-slate-500">ES</span>
              <p className="text-sm font-mono font-semibold">{result.originalExpectedShortfall != null ? formatMoney(result.originalExpectedShortfall.toFixed(2), 'USD') : '-'}</p>
            </div>
          </div>
        </div>
        <div className="bg-white rounded border border-slate-200 p-3">
          <h5 className="text-xs font-medium text-slate-500 mb-1">REPLAY</h5>
          <div className="space-y-1">
            <div data-testid="replay-replayed-var">
              <span className="text-xs text-slate-500">VaR</span>
              <p className="text-sm font-mono font-semibold">{result.replayVarValue != null ? formatMoney(result.replayVarValue.toFixed(2), 'USD') : '-'}</p>
            </div>
            <div data-testid="replay-replayed-es">
              <span className="text-xs text-slate-500">ES</span>
              <p className="text-sm font-mono font-semibold">{result.replayExpectedShortfall != null ? formatMoney(result.replayExpectedShortfall.toFixed(2), 'USD') : '-'}</p>
            </div>
          </div>
        </div>
      </div>

      <div className="flex flex-wrap items-center gap-4 text-xs">
        <div className="flex items-center gap-2">
          <span className="text-slate-500">Inputs</span>
          <DigestBadge matched={result.inputDigestMatch} />
        </div>
        <div className="flex items-center gap-2">
          <span className="text-slate-500">Model</span>
          {result.replayModelVersion === result.manifest.modelVersion ? (
            <span className="inline-flex items-center gap-1 text-emerald-700 bg-emerald-50 px-2 py-0.5 rounded-full font-medium">
              <CheckCircle2 className="h-3 w-3" /> {result.manifest.modelVersion || 'unknown'}
            </span>
          ) : (
            <span data-testid="replay-model-mismatch" className="inline-flex items-center gap-1 text-amber-700 bg-amber-50 px-2 py-0.5 rounded-full font-medium">
              <AlertTriangle className="h-3 w-3" /> {result.manifest.modelVersion} vs {result.replayModelVersion}
            </span>
          )}
        </div>
      </div>

      <div data-testid="replay-var-delta" className="text-xs">
        <span className="text-slate-500">VaR delta: </span>
        {nearZero ? (
          <span className="text-emerald-600 font-medium">No significant change</span>
        ) : (
          <span className={`font-mono font-medium ${varDelta > 0 ? 'text-red-600' : 'text-emerald-600'}`}>
            {varDelta > 0 ? '+' : ''}{formatMoney(varDelta.toFixed(2), 'USD')}
          </span>
        )}
      </div>

      {!result.inputDigestMatch && (
        <div data-testid="replay-digest-mismatch-warning" role="alert" className="bg-red-50 border border-red-200 rounded p-2 text-xs text-red-700">
          <XCircle className="h-3 w-3 inline mr-1" />
          Input Digest Mismatch — the inputs used for this replay differ from the original run. Results may not be comparable.
        </div>
      )}
    </div>
  )
}

export function ReplayPanel({ jobId, onClose }: ReplayPanelProps) {
  const { state, manifest, replayResult, error, errorCode, loadManifest, replay, reset } = useReplay()

  useEffect(() => {
    loadManifest(jobId)
  }, [jobId, loadManifest])

  return (
    <div data-testid="replay-panel" className="space-y-3">
      <div className="flex items-center justify-between">
        <h4 className="text-sm font-semibold text-slate-700">Run Reproducibility</h4>
        {onClose && (
          <button onClick={onClose} className="text-xs text-slate-400 hover:text-slate-600">Close</button>
        )}
      </div>

      {state === 'loading_manifest' && (
        <div className="flex items-center gap-2 text-sm text-slate-500 py-2">
          <Spinner size="sm" />
          Loading manifest...
        </div>
      )}

      {manifest && state !== 'loading_manifest' && (
        <ManifestSummary manifest={manifest} />
      )}

      {!manifest && state === 'idle' && (
        <p className="text-xs text-slate-400">No manifest available for this run.</p>
      )}

      {manifest && state === 'ready' && (
        <button
          data-testid={`replay-btn-${jobId}`}
          onClick={(e) => {
            e.stopPropagation()
            replay(jobId)
          }}
          className="px-3 py-1.5 text-xs font-medium text-primary-700 border border-primary-300 rounded hover:bg-primary-50 transition-colors inline-flex items-center gap-1.5"
        >
          <RefreshCw className="h-3 w-3" />
          Replay Run
        </button>
      )}

      {state === 'replaying' && (
        <div data-testid="replay-loading" className="flex items-center gap-2 text-sm text-slate-500 py-2">
          <Spinner size="sm" />
          Replay in progress...
        </div>
      )}

      {state === 'completed' && replayResult && (
        <>
          <ReplayComparison result={replayResult} />
          <button
            onClick={(e) => {
              e.stopPropagation()
              reset()
              loadManifest(jobId)
            }}
            className="px-3 py-1 text-xs text-slate-500 border border-slate-200 rounded hover:bg-slate-50 inline-flex items-center gap-1"
          >
            <RefreshCw className="h-3 w-3" />
            Replay Again
          </button>
        </>
      )}

      {state === 'error' && (
        <div data-testid="replay-error" className="bg-red-50 border border-red-200 rounded p-2 text-xs text-red-700">
          <XCircle className="h-3 w-3 inline mr-1" />
          {errorCode === 'BLOB_MISSING' ? 'Market data is no longer available for this run. ' : ''}
          {error}
        </div>
      )}
    </div>
  )
}

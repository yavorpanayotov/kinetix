import type { ToolCall } from '../api/copilot'

/**
 * Collapsible panel listing the tool calls that the model executed to
 * assemble an answer. Designed for Marcus (senior trader) who needs to
 * audit how the copilot sourced its numbers.
 *
 * Renders a top-level ``<details>`` (collapsed by default) labelled
 * ``Show reasoning (N tool call[s])``. Each call is a row showing:
 *  - status icon (✓ for ok, ⚠ for error/timeout)
 *  - tool name in monospace
 *  - wall-clock duration (Nms or N.NNs)
 *  - a nested ``<details>`` that reveals the params JSON on expansion
 *
 * Renders ``null`` when ``toolCalls`` is empty or undefined — no empty
 * panel is surfaced to the user.
 *
 * Dark-theme styling mirrors the existing copilot surfaces
 * (``StreamingNarrative``, ``CitationList``).
 */

interface ToolCallListProps {
  toolCalls: ToolCall[] | undefined
}

/**
 * Format a wall-clock duration derived from two ISO-8601 timestamps.
 *
 * - < 1000 ms → ``Nms``
 * - >= 1000 ms → ``N.NNs`` (two decimal places, trailing zeros stripped
 *   by ``parseFloat``)
 */
function formatDuration(startedAt: string, completedAt: string): string {
  const ms = Date.parse(completedAt) - Date.parse(startedAt)
  if (ms < 1000) return `${ms}ms`
  return `${parseFloat((ms / 1000).toFixed(2))}s`
}

function statusIcon(status: ToolCall['status']): string {
  return status === 'ok' ? '✓' : '⚠'
}

export function ToolCallList({ toolCalls }: ToolCallListProps): React.ReactElement | null {
  if (!toolCalls || toolCalls.length === 0) return null

  const count = toolCalls.length
  const label = `Show reasoning (${count} tool call${count === 1 ? '' : 's'})`

  return (
    <details
      data-testid="tool-call-list"
      className="mt-2 text-xs text-slate-400 dark:text-slate-500"
    >
      <summary className="cursor-pointer select-none text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300">
        {label}
      </summary>
      <ul className="mt-1 space-y-1 pl-2">
        {toolCalls.map((tc, idx) => (
          <li
            key={`${tc.name}-${idx}`}
            data-testid="tool-call-row"
            className="flex flex-col gap-0.5"
          >
            <div className="flex items-center gap-1.5">
              <span
                className={
                  tc.status === 'ok'
                    ? 'text-emerald-400'
                    : 'text-amber-400'
                }
                aria-label={`status: ${tc.status}`}
              >
                {statusIcon(tc.status)}
              </span>
              <span className="font-mono text-xs text-slate-300 dark:text-slate-300">
                {tc.name}
              </span>
              <span className="text-slate-500 dark:text-slate-600">
                ({formatDuration(tc.started_at, tc.completed_at)})
              </span>
            </div>
            <details className="pl-4">
              <summary className="cursor-pointer text-[10px] text-slate-500 dark:text-slate-600 hover:text-slate-400">
                params
              </summary>
              <pre className="mt-0.5 overflow-x-auto rounded bg-slate-900/50 p-1.5 text-[10px] leading-relaxed text-slate-400">
                {JSON.stringify(tc.params, null, 2)}
              </pre>
            </details>
          </li>
        ))}
      </ul>
    </details>
  )
}

export default ToolCallList

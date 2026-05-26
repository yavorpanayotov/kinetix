import type { Citation } from '../api/copilot'
import { freshnessUrgency } from './freshnessUtils'

export interface CitationFootnoteProps {
  /** The narrative string (server-streamed, already accumulated). */
  narrative: string

  /** Citations in tool-call order (server-emitted). */
  citations: Citation[]

  /**
   * When ``true``, render ``[uncited]`` after tokens that don't match
   * any citation ``result_value``. Defaults to ``true`` — disable in
   * places that have already gated visibility on the verifier
   * elsewhere.
   */
  flagUncited?: boolean
}

/**
 * Numeric-token regex shared with the backend verifier
 * (``ai-insights-service/.../citations/verifier.py``). Matches plain
 * integers, decimals, thousands-separated values, optional leading
 * ``$``, and optional trailing ``%``. We deliberately re-implement the
 * regex here rather than importing it across the language boundary —
 * the two implementations drift only when the contract itself changes,
 * and tests on either side surface the drift loudly.
 */
const TOKEN_PATTERN = /\$?(?=[\d,]*\d)[\d,]+(?:\.\d+)?%?/g

interface ParsedToken {
  /** Numeric value with ``$``/``%``/``,`` stripped. */
  value: number
  /**
   * Number of digits after the decimal point AS WRITTEN. ``5`` → 0,
   * ``5.20`` → 2. Used to size the matching window so JPY (0dp) is
   * strict-to-integer and BHD (3dp) is strict-to-mille without
   * hardcoding currency tables.
   */
  decimals: number
}

function parseToken(token: string): ParsedToken | null {
  const stripped = token.replace(/^\$/, '').replace(/%$/, '').replace(/,/g, '')
  if (stripped.length === 0) return null
  const value = parseFloat(stripped)
  if (Number.isNaN(value)) return null
  const dotIdx = stripped.indexOf('.')
  const decimals = dotIdx === -1 ? 0 : stripped.length - dotIdx - 1
  return { value, decimals }
}

/**
 * Pull a numeric value out of a citation's ``result_value``. Numbers
 * are returned directly; strings are scanned with the same token regex
 * and the first numeric prefix is parsed — so ``"5.2M USD"`` resolves
 * to ``5.2`` and ``"comfortably above"`` resolves to ``null``.
 */
function coerceCitationValue(raw: number | string): number | null {
  if (typeof raw === 'number') return Number.isFinite(raw) ? raw : null
  TOKEN_PATTERN.lastIndex = 0
  const match = TOKEN_PATTERN.exec(raw)
  TOKEN_PATTERN.lastIndex = 0
  if (!match) return null
  const parsed = parseToken(match[0])
  return parsed?.value ?? null
}

/**
 * True when a token's numeric value lies within the half-open window
 * ``[token - 0.5*unit, token + 0.5*unit)`` of any supplied citation
 * value, where ``unit = 10^-decimals``. Mirrors the backend verifier's
 * ``_matches_any_citation`` so the UI and the verifier agree on which
 * tokens are cited.
 *
 * Half-open instead of Python ``round``-style equality because
 * banker's rounding would let ``5.25`` silently masquerade as a
 * citation for the narrative token ``5.2``.
 */
function findMatchingCitationIndex(
  token: ParsedToken,
  citationValues: Array<number | null>,
): number | null {
  const halfUnit = 0.5 * Math.pow(10, -token.decimals)
  const epsilon = 1e-9
  const lower = token.value - halfUnit
  const upper = token.value + halfUnit
  for (let i = 0; i < citationValues.length; i += 1) {
    const cv = citationValues[i]
    if (cv === null) continue
    if (lower - epsilon <= cv && cv < upper - epsilon) {
      return i + 1 // 1-based footnote index
    }
  }
  return null
}

/**
 * Colour class for the inline urgency dot per urgency tier.
 * Only aging/stale show a dot — fresh is intentionally absent.
 */
const URGENCY_DOT_CLASS: Record<'aging' | 'stale', string> = {
  aging: 'text-amber-300',
  stale: 'text-rose-300',
}

interface Segment {
  kind: 'text' | 'token'
  text: string
  /** Match index in narrative — used as a stable key. */
  offset: number
}

/**
 * Split a narrative into alternating text and token segments. Using
 * ``matchAll`` over a stateful regex with the ``g`` flag preserves
 * source order and gives us the exact match offsets we need to
 * reconstruct the surrounding prose without losing whitespace or
 * punctuation.
 */
function splitNarrative(narrative: string): Segment[] {
  const segments: Segment[] = []
  let cursor = 0
  // Fresh regex per call so we don't have to manage ``lastIndex``.
  const regex = new RegExp(TOKEN_PATTERN.source, 'g')
  for (const match of narrative.matchAll(regex)) {
    const start = match.index ?? 0
    if (start > cursor) {
      segments.push({
        kind: 'text',
        text: narrative.slice(cursor, start),
        offset: cursor,
      })
    }
    segments.push({ kind: 'token', text: match[0], offset: start })
    cursor = start + match[0].length
  }
  if (cursor < narrative.length) {
    segments.push({
      kind: 'text',
      text: narrative.slice(cursor),
      offset: cursor,
    })
  }
  return segments
}

/**
 * Inline-renders a narrative with footnote markers for every numeric
 * token. Cited tokens carry a 1-based superscript matching their
 * position in the ``citations`` array; uncited tokens surface an
 * ``[uncited]`` marker so reviewers can spot hallucinations.
 *
 * The component is purely presentational and pure-functional: the
 * narrative, citations, and ``flagUncited`` prop are sufficient to
 * fully determine the output.
 */
export function CitationFootnote({
  narrative,
  citations,
  flagUncited = true,
}: CitationFootnoteProps): React.ReactElement {
  // Pre-compute each citation's numeric value once so token matching
  // is O(tokens * citations) rather than O(tokens * citations * regex).
  const citationValues = citations.map((c) => coerceCitationValue(c.result_value))
  const segments = splitNarrative(narrative)

  return (
    <span data-testid="citation-footnote">
      {segments.map((segment) => {
        if (segment.kind === 'text') {
          return <span key={`t-${segment.offset}`}>{segment.text}</span>
        }
        const parsed = parseToken(segment.text)
        const matchIndex =
          parsed !== null
            ? findMatchingCitationIndex(parsed, citationValues)
            : null
        if (matchIndex !== null) {
          const matchedCitation = citations[matchIndex - 1]
          const urgency = freshnessUrgency(matchedCitation.freshness_seconds)
          const dotClass = urgency !== 'fresh' ? URGENCY_DOT_CLASS[urgency] : null
          return (
            <cite
              key={`c-${segment.offset}`}
              data-token={segment.text}
              data-citation-index={String(matchIndex)}
              className="not-italic"
            >
              {segment.text}
              <sup className="ml-0.5 text-xs text-primary-600 dark:text-primary-400">
                {matchIndex}
              </sup>
              {dotClass !== null && (
                <span
                  data-testid="urgency-dot"
                  aria-hidden="true"
                  className={`ml-0.5 text-[9px] leading-none ${dotClass}`}
                >
                  {'•'}
                </span>
              )}
            </cite>
          )
        }
        // Uncited token. ``flagUncited`` toggles the visible marker
        // but the ``<cite data-uncited>`` wrapper stays either way so
        // downstream styling / a11y can still target the span.
        return (
          <cite
            key={`u-${segment.offset}`}
            data-token={segment.text}
            data-uncited="true"
            className="not-italic"
          >
            {segment.text}
            {flagUncited && (
              <sup className="ml-0.5 text-xs text-amber-600 dark:text-amber-400">
                [uncited]
              </sup>
            )}
          </cite>
        )
      })}
    </span>
  )
}

export default CitationFootnote

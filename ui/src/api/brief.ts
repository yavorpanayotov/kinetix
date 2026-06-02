/**
 * Client for the v2 morning-brief endpoint.
 *
 * Wraps ``GET /api/v1/insights/brief/today`` (gateway-proxied — see
 * docs/plans/ai-v2.md §6.8). The endpoint either returns a ready brief
 * (HTTP 200) or signals that generation is still in progress
 * (HTTP 202). ``fetchTodayBrief`` collapses both into a single
 * ``BriefTodayResponse`` discriminated by ``status`` so callers branch
 * on the body rather than the HTTP code.
 */

import { authFetch } from '../auth/authFetch'
import type { Citation } from './copilot'

/** One titled block of a morning brief — mirrors the server-side model. */
export interface BriefSection {
  title: string
  narrative: string
  bullets: string[]
  sources: Citation[]
  severity: 'info' | 'warning' | 'critical'
  status: 'ok' | 'error' | 'timeout'
}

/** A complete morning brief for a single book. */
export interface MorningBrief {
  book_id: string
  sections: BriefSection[]
  generated_at: string
  mode: 'live' | 'canned'
}

/**
 * Parsed body of ``GET /api/v1/insights/brief/today``.
 *
 * - ``status:"ready"`` — ``briefs`` is populated.
 * - ``status:"generating"`` — the brief is still being assembled;
 *   ``retry_after`` is a hint (seconds) for when to poll again.
 */
export interface BriefTodayResponse {
  status: 'ready' | 'generating'
  briefs?: MorningBrief[]
  mode?: 'live' | 'canned'
  generated_at?: string
  retry_after?: number
}

const BRIEF_ENDPOINT = '/api/v1/insights/brief/today'

/**
 * Fetch today's morning brief.
 *
 * Returns the parsed body; callers branch on ``status``. A ``202``
 * response (brief still generating) maps to
 * ``{status:"generating", retry_after}`` — ``retry_after`` is taken
 * from the body when present, defaulting to ``5`` seconds. Any other
 * non-ok status throws an ``Error`` carrying the status text.
 */
export async function fetchTodayBrief(): Promise<BriefTodayResponse> {
  const response = await authFetch(BRIEF_ENDPOINT)

  if (response.ok) {
    return (await response.json()) as BriefTodayResponse
  }

  if (response.status === 202) {
    let retryAfter = 5
    try {
      const body = (await response.json()) as { retry_after?: number }
      if (typeof body.retry_after === 'number') retryAfter = body.retry_after
    } catch {
      // Empty / non-JSON 202 body — fall back to the default.
    }
    return { status: 'generating', retry_after: retryAfter }
  }

  throw new Error(
    `Failed to fetch morning brief: ${response.status} ${response.statusText}`,
  )
}

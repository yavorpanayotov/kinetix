"""``GET /api/v1/insights/brief/today`` — the morning brief endpoint.

The route serves the trader's per-book morning brief for the current
day. Behaviour by store state:

* **ready** → ``200`` with ``{"status": "ready", "briefs": [...],
  "mode": ..., "generated_at": ...}``.
* **generating** → ``202`` with ``{"status": "generating",
  "retry_after": 5}`` and a matching ``Retry-After: 5`` header. This
  window exists only while the 06:30 lifespan task is mid-flight.
* **absent** → the *on-demand* path: the route generates the brief
  inline (``await brief_client.generate_brief``), stores it, and
  returns ``200`` with the ready body.

Why on-demand generation is synchronous in v2
---------------------------------------------
The canned client returns in microseconds and a genuinely slow live
generation is pre-empted by the 06:30 scheduler, so blocking the first
request of the day is acceptable for v2. The ``202`` path is still
exercised — it covers the narrow window where the scheduler task is
running when the first request lands.

User resolution
---------------
The caller's :class:`UserContext` is read from the ``X-User-Id`` and
``X-User-Books`` request headers (the same headers
:meth:`UserContext.to_headers` writes; ``X-User-Books`` is a
comma-separated list). When ``X-User-Id`` is absent the route defaults
to ``user_id="anonymous"`` with no books — the unauthenticated demo
path — so the endpoint is usable without a gateway in front of it.
"""

from __future__ import annotations

import time
from datetime import datetime, timezone

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse
from starlette.responses import Response

from kinetix_insights.audit.audit_logger import AuditLogger
from kinetix_insights.audit.audit_record import AuditRecord
from kinetix_insights.audit.token_estimate import estimate_tokens
from kinetix_insights.brief.brief_store import BriefStore
from kinetix_insights.brief.canned import BriefClient
from kinetix_insights.brief.models import MorningBrief
from kinetix_insights.clients.user_context import UserContext

router = APIRouter(prefix="/api/v1/insights", tags=["brief"])

_RETRY_AFTER_SECONDS = 5

# Shared audit logger — stateless, so a module-level singleton is safe.
_AUDIT_LOGGER = AuditLogger()

# Stable "prompt" marker hashed into the brief audit line. The morning
# brief takes no free-form prompt, so the audit trail records the hash of
# this fixed identifier — enough to tag the line as a brief request.
_BRIEF_PROMPT_MARKER = "morning-brief:today"


def _user_from_request(request: Request) -> UserContext:
    """Resolve the caller's :class:`UserContext` from request headers.

    Mirrors :meth:`UserContext.to_headers`: ``X-User-Books`` is a
    comma-separated list. A missing ``X-User-Id`` yields the
    ``"anonymous"`` demo identity with no books.
    """

    user_id = request.headers.get("X-User-Id") or "anonymous"
    raw_books = request.headers.get("X-User-Books", "")
    books = tuple(b for b in (part.strip() for part in raw_books.split(",")) if b)
    return UserContext(user_id=user_id, books=books)


def _ready_body(briefs: list[MorningBrief]) -> dict[str, object]:
    """Build the 200 JSON body for a ready brief list.

    ``mode`` / ``generated_at`` are lifted from the first brief so the
    UI can stamp the digest header without reaching into the list;
    ``mode`` falls back to ``"canned"`` for the (shouldn't-happen)
    empty-list case.
    """

    serialised = [b.model_dump(mode="json") for b in briefs]
    mode = briefs[0].mode if briefs else "canned"
    generated_at = (
        briefs[0].generated_at.isoformat() if briefs else None
    )
    return {
        "status": "ready",
        "briefs": serialised,
        "mode": mode,
        "generated_at": generated_at,
    }


def _brief_tool_calls(briefs: list[MorningBrief]) -> list[str]:
    """Return the distinct MCP tool names cited across every brief section.

    Each :class:`~kinetix_insights.brief.models.BriefSection` carries the
    provenance citations for its numbers; the audit trail records the set
    of tools that fed the brief.
    """

    seen: list[str] = []
    for brief in briefs:
        for section in brief.sections:
            for citation in section.sources:
                if citation.tool not in seen:
                    seen.append(citation.tool)
    return seen


def _brief_text(briefs: list[MorningBrief]) -> str:
    """Concatenate every brief section's narrative + bullets for token sizing."""

    fragments: list[str] = []
    for brief in briefs:
        for section in brief.sections:
            fragments.append(section.narrative)
            fragments.extend(section.bullets)
    return " ".join(f for f in fragments if f)


def _emit_brief_audit(
    *, user_id: str, briefs: list[MorningBrief], started: float, mode: str
) -> None:
    """Emit exactly one structured audit log line for a brief call."""

    latency_ms = (time.monotonic() - started) * 1000.0
    _AUDIT_LOGGER.emit(
        AuditRecord(
            user_id=user_id,
            endpoint="brief",
            prompt_hash=AuditRecord.hash_prompt(_BRIEF_PROMPT_MARKER),
            tool_calls=_brief_tool_calls(briefs),
            tokens_estimated=estimate_tokens(_brief_text(briefs)),
            mode=mode,
            latency_ms=latency_ms,
            timestamp=datetime.now(timezone.utc),
        )
    )


@router.get("/brief/today")
async def brief_today(request: Request) -> Response:
    """Return today's morning brief, generating it on demand if absent.

    Emits exactly one structured audit log line (checkbox 10.3) on every
    return path — the cached ``ready`` hit, the ``generating`` window,
    and the on-demand inline generation.
    """

    started = time.monotonic()
    store: BriefStore = request.app.state.brief_store
    brief_client: BriefClient = request.app.state.brief_client
    user = _user_from_request(request)

    status = store.status_for(user.user_id)

    if status == "ready":
        briefs = store.get(user.user_id) or []
        _emit_brief_audit(
            user_id=user.user_id,
            briefs=briefs,
            started=started,
            mode=briefs[0].mode if briefs else "canned",
        )
        return JSONResponse(_ready_body(briefs))

    if status == "generating":
        _emit_brief_audit(
            user_id=user.user_id, briefs=[], started=started, mode="generating"
        )
        return JSONResponse(
            {"status": "generating", "retry_after": _RETRY_AFTER_SECONDS},
            status_code=202,
            headers={"Retry-After": str(_RETRY_AFTER_SECONDS)},
        )

    # status == "absent" — on-demand inline generation.
    store.mark_generating(user.user_id)
    briefs = await brief_client.generate_brief(user=user)
    store.put(user.user_id, briefs)
    _emit_brief_audit(
        user_id=user.user_id,
        briefs=briefs,
        started=started,
        mode=briefs[0].mode if briefs else "canned",
    )
    return JSONResponse(_ready_body(briefs))

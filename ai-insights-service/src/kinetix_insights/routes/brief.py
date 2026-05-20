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

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse
from starlette.responses import Response

from kinetix_insights.brief.brief_store import BriefStore
from kinetix_insights.brief.canned import BriefClient
from kinetix_insights.brief.models import MorningBrief
from kinetix_insights.clients.user_context import UserContext

router = APIRouter(prefix="/api/v1/insights", tags=["brief"])

_RETRY_AFTER_SECONDS = 5


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


@router.get("/brief/today")
async def brief_today(request: Request) -> Response:
    """Return today's morning brief, generating it on demand if absent."""

    store: BriefStore = request.app.state.brief_store
    brief_client: BriefClient = request.app.state.brief_client
    user = _user_from_request(request)

    status = store.status_for(user.user_id)

    if status == "ready":
        briefs = store.get(user.user_id) or []
        return JSONResponse(_ready_body(briefs))

    if status == "generating":
        return JSONResponse(
            {"status": "generating", "retry_after": _RETRY_AFTER_SECONDS},
            status_code=202,
            headers={"Retry-After": str(_RETRY_AFTER_SECONDS)},
        )

    # status == "absent" — on-demand inline generation.
    store.mark_generating(user.user_id)
    briefs = await brief_client.generate_brief(user=user)
    store.put(user.user_id, briefs)
    return JSONResponse(_ready_body(briefs))

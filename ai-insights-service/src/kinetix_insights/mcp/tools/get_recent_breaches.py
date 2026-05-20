"""MCP tool ``get_recent_breaches`` — book-scoped recent limit-breach read.

Reads the persisted ``limit_breach_events`` for a single book and
projects them onto the v2 tool-output shape defined in
``plans/ai-v2.md`` § PR 6:

    {
        "book_id": str,
        "breaches": [
            {
                "id": str,
                "entity_id": str,
                "limit_type": str,
                "severity": str,
                "current_value": float,
                "limit_value": float,
                "breached_at": str,        # raw upstream ISO string
                "resolved_at": str | None,
                "is_open": bool,           # resolved_at is None
            },
            ...                            # sorted by breached_at desc
        ],
        "total_fetched": int,              # raw upstream length before filter
        "recent_count": int,               # after the since filter
        "open_count": int,                 # is_open == True in `breaches`
        "citation": Citation,
    }

The single :class:`Citation` returned describes the headline
``recent_count`` value — the number of breaches in the requested
window — so callers and the citation verifier have one canonical
numeric anchor for the response.

Upstream HTTP contract — IMPORTANT v2 limitation
------------------------------------------------
The position-service does NOT yet expose an HTTP endpoint for
``limit_breach_events``. Checkbox 6.2 added the table, the
``LimitBreachEventWriter`` persistence hook, and a
``LimitBreachEventWriter.findByBook(bookId)`` repository method — but
NO GET route. This tool targets the *conventional* endpoint

    GET /api/v1/books/{book_id}/limit-breaches

on service short-name ``position``. The unit tests exercise the tool
fully via :class:`tests.fakes.fake_kinetix_http_client.
FakeKinetixHttpClient`, so the tool is testable without the route
existing. Exposing that read route on position-service is a v2
follow-up — the morning-brief generator (checkbox 6.5) that consumes
this tool will need it before the tool works end-to-end against a
real deployment.

Assumptions and known limitations (v2 scope)
--------------------------------------------
* **Single-book only** — the tool requires ``book_id`` to be in the
  caller's :class:`UserContext.books` and reads only that book's
  breaches. Cross-book queries are deferred to a future release.

* **No query parameters upstream** — the conventional endpoint takes
  no query parameters; the ``since`` window (and the default last
  7 days) is applied entirely client-side after fetching.

* **"Recent" default** — when ``since`` is omitted, the tool keeps
  breaches whose ``breachedAt`` falls in the last 7 days relative to
  the injected ``now``. This matches the "recent" semantics of the
  tool name and the morning-brief use case.

* **Currency** — the upstream rows are not currency-tagged. v2
  hard-codes ``"USD"`` on the citation, matching the rest of the PR 2
  tool cohort. The headline ``recent_count`` is a count, not a
  monetary value, so the currency is effectively a placeholder.

* **No upstream timestamp** — the endpoint returns a list with no
  per-call timestamp, so the citation pins ``as_of_timestamp`` to the
  call time (injected ``now``) and ``freshness_seconds`` is therefore
  ``0``. This is correct for "data fetched now" semantics.

* **Not wired into the FastMCP registry** — registry wiring for the
  original 10 PR 2 tools was checkbox 2.11. Wiring this 11th tool into
  ``server.py`` is a separate v2 follow-up, out of scope for 6.3.

The tool fails closed on book-level ACL: if ``book_id`` is not in the
caller's :class:`UserContext.books`, it raises a
``KinetixHttpError(UNAUTHORIZED, 403)`` directly without touching the
HTTP client. It also validates ``since`` BEFORE the HTTP call so
malformed input never reaches the upstream service. Upstream
``KinetixHttpError`` (``NOT_FOUND``, ``UPSTREAM_ERROR``, ...) is
propagated unmodified so callers can map into the citation error
contract uniformly.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Any, Callable

from kinetix_insights.citations.models import Citation
from kinetix_insights.clients.kinetix_http_client import (
    KinetixHttpClient,
    KinetixHttpError,
)
from kinetix_insights.clients.user_context import UserContext

_SERVICE_SHORT_NAME: str = "position"
_DATA_SOURCE: str = "position-service"
_RESULT_CURRENCY: str = "USD"
_DEFAULT_WINDOW_DAYS: int = 7

_OPEN_BREACHES_FLAG: str = "OPEN_BREACHES"


def _default_now() -> datetime:
    return datetime.now(timezone.utc)


def _breaches_path(book_id: str) -> str:
    """Build the conventional position-service limit-breaches path."""

    return f"/api/v1/books/{book_id}/limit-breaches"


def _parse_breached_at(raw: str) -> datetime:
    """Parse the upstream ``breachedAt`` ISO 8601 string.

    The upstream serialises with a trailing ``Z``; ``fromisoformat``
    accepts ``+00:00`` natively, so we normalise.
    """

    normalised = raw.replace("Z", "+00:00") if raw.endswith("Z") else raw
    parsed = datetime.fromisoformat(normalised)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed


def _parse_since(raw: str) -> datetime:
    """Parse a ``YYYY-MM-DD`` ``since`` filter to a start-of-UTC-day datetime.

    Raises ``ValueError`` for any other format (caught by the caller and
    re-raised as ``KinetixHttpError(BAD_REQUEST, 400)``).
    """

    parsed_date = datetime.strptime(raw, "%Y-%m-%d")
    return parsed_date.replace(tzinfo=timezone.utc)


def _map_row(raw: dict[str, Any]) -> dict[str, Any]:
    """Project an upstream ``limit_breach_events`` row onto the tool row."""

    resolved_at = raw.get("resolvedAt")
    return {
        "id": raw["id"],
        "entity_id": raw["entityId"],
        "limit_type": raw["limitType"],
        "severity": raw["severity"],
        "current_value": float(raw["currentValue"]),
        "limit_value": float(raw["limitValue"]),
        "breached_at": raw["breachedAt"],
        "resolved_at": resolved_at,
        "is_open": resolved_at is None,
    }


async def get_recent_breaches(
    *,
    book_id: str,
    since: str | None = None,
    user: UserContext,
    http: KinetixHttpClient,
    now: Callable[[], datetime] | None = None,
) -> dict[str, Any]:
    """Return the recent limit breaches for ``book_id`` with a citation.

    Args:
        book_id: Portfolio identifier. Must be present in ``user.books``
            or the call fails closed with ``UNAUTHORIZED``. Cross-book
            queries are deferred to a future release; the tool is
            always single-book in v2.
        since: Optional client-side filter (``YYYY-MM-DD``) — keeps only
            breaches whose ``breachedAt`` parses to a datetime at or
            after start-of-UTC-day. Any other format raises
            ``BAD_REQUEST`` before the HTTP call. When omitted, the
            tool defaults to the last 7 days relative to ``now``.
        user: Caller identity and book scopes; forwarded to the HTTP
            client which stamps ``X-User-Id`` / ``X-User-Books``.
        http: ``KinetixHttpClient`` to dispatch the upstream call.
        now: Injectable clock used both to compute the default 7-day
            window and to stamp ``as_of_timestamp`` on the citation.
            Defaults to ``datetime.now(timezone.utc)``.

    Returns:
        A dict with ``book_id``, ``breaches`` (filtered/sorted rows in
        tool output shape, newest-first), ``total_fetched`` (raw
        upstream count before filtering), ``recent_count`` (after the
        ``since`` window), ``open_count`` (unresolved rows in
        ``breaches``), and ``citation`` (a :class:`Citation` covering
        ``recent_count``).

    Raises:
        KinetixHttpError: ``BAD_REQUEST``/400 for an invalid ``since``;
            ``UNAUTHORIZED``/403 when ``book_id`` is outside the
            caller's scope; ``UPSTREAM_ERROR``/502 when the upstream
            payload is not a JSON array; ``NOT_FOUND``,
            ``UPSTREAM_ERROR`` (and other coarse categories) when the
            upstream call itself fails. All propagated unmodified.
    """

    path = _breaches_path(book_id)

    # Validate since BEFORE any HTTP call.
    since_dt: datetime | None = None
    if since is not None:
        try:
            since_dt = _parse_since(since)
        except ValueError:
            raise KinetixHttpError(
                status_code=400,
                code="BAD_REQUEST",
                message=(
                    f"invalid since {since!r}; expected ISO date "
                    "'YYYY-MM-DD'"
                ),
                service=_SERVICE_SHORT_NAME,
                path=path,
            ) from None

    # Book-level ACL fails closed before any HTTP call.
    if book_id not in user.books:
        raise KinetixHttpError(
            status_code=403,
            code="UNAUTHORIZED",
            message=f"book {book_id!r} not in user scope",
            service=_SERVICE_SHORT_NAME,
            path=path,
        )

    now_fn = now or _default_now
    as_of_timestamp = now_fn()

    # Default window: last 7 days relative to now.
    if since_dt is None:
        since_dt = as_of_timestamp - timedelta(days=_DEFAULT_WINDOW_DAYS)

    raw_payload = await http.get(
        _SERVICE_SHORT_NAME, path, params=None, user=user
    )
    if not isinstance(raw_payload, list):
        raise KinetixHttpError(
            status_code=502,
            code="UPSTREAM_ERROR",
            message=(
                f"expected array from {_SERVICE_SHORT_NAME}{path}, "
                f"got {type(raw_payload).__name__}"
            ),
            service=_SERVICE_SHORT_NAME,
            path=path,
        )
    raw_breaches: list[dict[str, Any]] = raw_payload

    total_fetched = len(raw_breaches)

    # since window filter.
    recent = [
        row
        for row in raw_breaches
        if _parse_breached_at(row["breachedAt"]) >= since_dt
    ]

    # Sort by breached_at desc (newest first).
    recent.sort(
        key=lambda row: _parse_breached_at(row["breachedAt"]),
        reverse=True,
    )

    mapped = [_map_row(row) for row in recent]
    recent_count = len(mapped)
    open_count = sum(1 for row in mapped if row["is_open"])

    quality_flags: list[str] = []
    if open_count > 0:
        quality_flags.append(_OPEN_BREACHES_FLAG)

    citation = Citation(
        tool="get_recent_breaches",
        params={"book_id": book_id, "since": since},
        result_field="recent_count",
        result_value=float(recent_count),
        result_currency=_RESULT_CURRENCY,
        as_of_timestamp=as_of_timestamp,
        data_source=_DATA_SOURCE,
        freshness_seconds=0,
        quality_flags=quality_flags,
    )

    return {
        "book_id": book_id,
        "breaches": mapped,
        "total_fetched": total_fetched,
        "recent_count": recent_count,
        "open_count": open_count,
        "citation": citation,
    }

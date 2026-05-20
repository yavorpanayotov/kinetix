"""MCP tool ``search_audit_log`` — time-bounded audit-log text search.

Reads the hash-chained ``audit_events`` feed from audit-service
(``GET /api/v1/audit/events``) and projects the matching rows onto the
v2 tool-output shape defined in ``plans/ai-v2.md`` § PR 6:

    {
        "query": str,
        "window": {"since": str, "until": str},   # RESOLVED ISO datetimes
        "events": [
            {
                "id": int,
                "event_type": str,
                "book_id": str | None,
                "user_id": str | None,
                "user_role": str | None,
                "received_at": str,               # raw upstream ISO
                "trade_id": str | None,
                "instrument_id": str | None,
                "details": str | None,
            },
            ...                                   # sorted by received_at desc
        ],
        "total_fetched": int,                     # raw upstream length
        "match_count": int,                       # after time + text filter
        "citation": Citation,
    }

The single :class:`Citation` returned describes the headline
``match_count`` value — the number of audit events matching the query
within the resolved window — so callers and the citation verifier have
one canonical numeric anchor for the response.

Window resolution (the checkbox's headline behaviour)
-----------------------------------------------------
The plan mandates a time-range; this tool always operates inside a
fully-resolved ``[effective_since, effective_until]`` window, with a
default span of 7 days:

* ``effective_until`` = end-of-UTC-day of ``until`` when supplied, else
  end-of-UTC-day of the injected ``now``.
* ``effective_since`` = start-of-UTC-day of ``since`` when supplied,
  else ``effective_until`` minus 7 days.

Therefore:

* Neither bound supplied → the window is exactly the last 7 days
  ending today.
* ``since`` only → window is ``[since, now]``.
* ``until`` only → window is ``[until - 7 days, until]``.

Both ``since`` and ``until`` are ISO dates (``YYYY-MM-DD``). A
malformed date, or a ``since`` that is later than ``until``, raises
``KinetixHttpError(BAD_REQUEST, 400)`` BEFORE any HTTP call.

Text search (client-side)
-------------------------
``query`` is REQUIRED and must be non-empty (empty / whitespace-only
raises ``BAD_REQUEST`` before any HTTP call). An event matches when
``query`` (compared case-insensitively) is a substring of ANY of the
stringified fields ``eventType``, ``userId``, ``userRole``,
``tradeId``, ``instrumentId``, ``details``, ``modelName``,
``scenarioId``, ``limitId`` or ``submissionId``. Null fields are
skipped.

Filtering pipeline (client-side, in order)
------------------------------------------
1. Time window — keep events whose ``receivedAt`` parses into
   ``[effective_since, effective_until]`` inclusive. ``receivedAt`` is
   the canonical timestamp; it is always present.
2. Text — keep events matching ``query`` per the rule above.
3. Sort by ``receivedAt`` descending (newest first).

Upstream HTTP contract — IMPORTANT v2 limitations
-------------------------------------------------
The audit-service ``GET /api/v1/audit/events`` endpoint supports
``bookId``, ``afterId`` (cursor) and ``limit`` query parameters — it
does NOT support time-range or text-search parameters, so both are
applied entirely client-side. The tool requests ``limit=500`` so the
client-side filters have a generous page to work with. v2 does
single-page retrieval only; cursor pagination across ``afterId`` is a
follow-up. When the upstream returns exactly the 500-row page cap the
citation carries the ``RESULT_TRUNCATED`` quality flag, signalling the
window may contain more events than this single page.

ACL — book scope is OPTIONAL
----------------------------
The audit log is a cross-cutting compliance record, so ``book_id`` is
optional. When ``book_id`` IS supplied the tool fails closed if it is
not in the caller's :class:`UserContext.books`, raising
``KinetixHttpError(UNAUTHORIZED, 403)`` without touching the HTTP
client, and forwards ``bookId`` upstream. When ``book_id`` is omitted
the search runs unscoped — the tool does NOT fail closed, but it still
forwards ``user`` so audit-service stamps ``X-User-Id`` /
``X-User-Books`` and can enforce its own ACL.

Other assumptions and known limitations (v2 scope)
--------------------------------------------------
* **Currency** — the headline ``match_count`` is a count, not a
  monetary value, so the citation uses ``result_currency=None``,
  matching ``get_vol_surface`` / ``get_correlation_matrix`` which set
  ``None`` for non-monetary headline values.

* **No upstream timestamp** — the endpoint returns a list with no
  per-call timestamp, so the citation pins ``as_of_timestamp`` to the
  call time (injected ``now``) and ``freshness_seconds`` is therefore
  ``0``. This is correct for "data fetched now" semantics.

* **Not wired into the FastMCP registry** — registry wiring for the
  original 10 PR 2 tools was checkbox 2.11. Wiring this tool into
  ``server.py`` is a separate v2 follow-up, out of scope for 6.4.

Upstream ``KinetixHttpError`` (``NOT_FOUND``, ``UPSTREAM_ERROR``, ...)
is propagated unmodified so callers can map upstream failures into the
citation error contract uniformly.
"""

from __future__ import annotations

from datetime import datetime, time, timedelta, timezone
from typing import Any, Callable

from kinetix_insights.citations.models import Citation
from kinetix_insights.clients.kinetix_http_client import (
    KinetixHttpClient,
    KinetixHttpError,
)
from kinetix_insights.clients.user_context import UserContext

_SERVICE_SHORT_NAME: str = "audit"
_DATA_SOURCE: str = "audit-service"
_EVENTS_PATH: str = "/api/v1/audit/events"
_FETCH_LIMIT: int = 500
_DEFAULT_WINDOW_DAYS: int = 7

_RESULT_TRUNCATED_FLAG: str = "RESULT_TRUNCATED"

# Upstream fields searched (case-insensitive substring) by ``query``.
_SEARCHED_FIELDS: tuple[str, ...] = (
    "eventType",
    "userId",
    "userRole",
    "tradeId",
    "instrumentId",
    "details",
    "modelName",
    "scenarioId",
    "limitId",
    "submissionId",
)


def _default_now() -> datetime:
    return datetime.now(timezone.utc)


def _parse_received_at(raw: str) -> datetime:
    """Parse the upstream ``receivedAt`` ISO 8601 string.

    The upstream serialises with a trailing ``Z``; ``fromisoformat``
    accepts ``+00:00`` natively, so we normalise.
    """

    normalised = raw.replace("Z", "+00:00") if raw.endswith("Z") else raw
    parsed = datetime.fromisoformat(normalised)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed


def _parse_iso_date(raw: str) -> datetime:
    """Parse a ``YYYY-MM-DD`` bound to a date-only UTC datetime.

    Raises ``ValueError`` for any other format (caught by the caller and
    re-raised as ``KinetixHttpError(BAD_REQUEST, 400)``).
    """

    parsed_date = datetime.strptime(raw, "%Y-%m-%d")
    return parsed_date.replace(tzinfo=timezone.utc)


def _start_of_day(value: datetime) -> datetime:
    return datetime.combine(value.date(), time.min, tzinfo=timezone.utc)


def _end_of_day(value: datetime) -> datetime:
    return datetime.combine(value.date(), time.max, tzinfo=timezone.utc)


def _bad_request(message: str) -> KinetixHttpError:
    return KinetixHttpError(
        status_code=400,
        code="BAD_REQUEST",
        message=message,
        service=_SERVICE_SHORT_NAME,
        path=_EVENTS_PATH,
    )


def _matches_query(row: dict[str, Any], needle: str) -> bool:
    """Return True when ``needle`` is a substring of any searched field."""

    for field in _SEARCHED_FIELDS:
        value = row.get(field)
        if value is None:
            continue
        if needle in str(value).casefold():
            return True
    return False


def _map_row(raw: dict[str, Any]) -> dict[str, Any]:
    """Project an upstream ``AuditEventResponse`` onto the tool output row."""

    return {
        "id": raw["id"],
        "event_type": raw["eventType"],
        "book_id": raw.get("bookId"),
        "user_id": raw.get("userId"),
        "user_role": raw.get("userRole"),
        "received_at": raw["receivedAt"],
        "trade_id": raw.get("tradeId"),
        "instrument_id": raw.get("instrumentId"),
        "details": raw.get("details"),
    }


async def search_audit_log(
    *,
    query: str,
    since: str | None = None,
    until: str | None = None,
    book_id: str | None = None,
    user: UserContext,
    http: KinetixHttpClient,
    now: Callable[[], datetime] | None = None,
) -> dict[str, Any]:
    """Search the audit log for events matching ``query`` in a time window.

    Args:
        query: Required non-empty search string. Matched
            case-insensitively as a substring against the searched
            field set (see module docstring). Empty / whitespace-only
            raises ``BAD_REQUEST`` before the HTTP call.
        since: Optional ISO date (``YYYY-MM-DD``) lower bound; resolves
            to start-of-UTC-day. When omitted, defaults to 7 days
            before ``effective_until``.
        until: Optional ISO date (``YYYY-MM-DD``) upper bound; resolves
            to end-of-UTC-day. When omitted, defaults to end-of-day of
            ``now``. A malformed ``since``/``until``, or ``since``
            later than ``until``, raises ``BAD_REQUEST`` before the
            HTTP call.
        book_id: Optional portfolio identifier. When supplied it must
            be in ``user.books`` or the call fails closed with
            ``UNAUTHORIZED``, and it is forwarded as the ``bookId``
            upstream query parameter. When omitted the search runs
            unscoped — audit-service enforces its own ACL via the
            forwarded user-context headers.
        user: Caller identity and book scopes; forwarded to the HTTP
            client which stamps ``X-User-Id`` / ``X-User-Books``.
        http: ``KinetixHttpClient`` to dispatch the upstream call.
        now: Injectable clock used both to resolve the default window
            and to stamp ``as_of_timestamp`` on the citation. Defaults
            to ``datetime.now(timezone.utc)``.

    Returns:
        A dict with ``query``, ``window`` (the resolved ISO since/until
        bounds), ``events`` (filtered/sorted rows in tool output shape,
        newest-first), ``total_fetched`` (raw upstream count before any
        filter), ``match_count`` (after time + text filters), and
        ``citation`` (a :class:`Citation` covering ``match_count``).

    Raises:
        KinetixHttpError: ``BAD_REQUEST``/400 for an empty ``query``,
            invalid ``since``/``until``, or ``since`` after ``until``;
            ``UNAUTHORIZED``/403 when a supplied ``book_id`` is outside
            the caller's scope; ``UPSTREAM_ERROR``/502 when the
            upstream payload is not a JSON array; ``NOT_FOUND``,
            ``UPSTREAM_ERROR`` (and other coarse categories) when the
            upstream call itself fails. All propagated unmodified.
    """

    # Validate query BEFORE any HTTP call.
    needle = query.strip()
    if not needle:
        raise _bad_request("query must be a non-empty search string")

    # Validate and resolve the time window BEFORE any HTTP call.
    now_fn = now or _default_now
    as_of_timestamp = now_fn()

    until_dt: datetime | None = None
    if until is not None:
        try:
            until_dt = _parse_iso_date(until)
        except ValueError:
            raise _bad_request(
                f"invalid until {until!r}; expected ISO date 'YYYY-MM-DD'"
            ) from None

    since_dt: datetime | None = None
    if since is not None:
        try:
            since_dt = _parse_iso_date(since)
        except ValueError:
            raise _bad_request(
                f"invalid since {since!r}; expected ISO date 'YYYY-MM-DD'"
            ) from None

    effective_until = (
        _end_of_day(until_dt) if until_dt is not None
        else _end_of_day(as_of_timestamp)
    )
    effective_since = (
        _start_of_day(since_dt) if since_dt is not None
        else effective_until - timedelta(days=_DEFAULT_WINDOW_DAYS)
    )

    if effective_since > effective_until:
        raise _bad_request(
            f"since {since!r} is later than until {until!r}"
        )

    needle = needle.casefold()

    # Book-level ACL fails closed before any HTTP call — but only when a
    # book is supplied. An omitted book runs unscoped (cross-cutting
    # compliance record); audit-service enforces its own ACL.
    if book_id is not None and book_id not in user.books:
        raise KinetixHttpError(
            status_code=403,
            code="UNAUTHORIZED",
            message=f"book {book_id!r} not in user scope",
            service=_SERVICE_SHORT_NAME,
            path=_EVENTS_PATH,
        )

    params: dict[str, Any] = {"limit": _FETCH_LIMIT}
    if book_id is not None:
        params["bookId"] = book_id

    raw_payload = await http.get(
        _SERVICE_SHORT_NAME, _EVENTS_PATH, params=params, user=user
    )
    if not isinstance(raw_payload, list):
        raise KinetixHttpError(
            status_code=502,
            code="UPSTREAM_ERROR",
            message=(
                f"expected array from {_SERVICE_SHORT_NAME}{_EVENTS_PATH}, "
                f"got {type(raw_payload).__name__}"
            ),
            service=_SERVICE_SHORT_NAME,
            path=_EVENTS_PATH,
        )
    raw_events: list[dict[str, Any]] = raw_payload

    total_fetched = len(raw_events)

    # 1. Time window filter.
    in_window = [
        row
        for row in raw_events
        if effective_since
        <= _parse_received_at(row["receivedAt"])
        <= effective_until
    ]

    # 2. Text filter.
    matched = [row for row in in_window if _matches_query(row, needle)]

    # 3. Sort by received_at desc (newest first).
    matched.sort(
        key=lambda row: _parse_received_at(row["receivedAt"]),
        reverse=True,
    )

    mapped = [_map_row(row) for row in matched]
    match_count = len(mapped)

    quality_flags: list[str] = []
    if total_fetched == _FETCH_LIMIT:
        quality_flags.append(_RESULT_TRUNCATED_FLAG)

    citation = Citation(
        tool="search_audit_log",
        params={
            "query": query,
            "since": since,
            "until": until,
            "book_id": book_id,
        },
        result_field="match_count",
        result_value=float(match_count),
        result_currency=None,
        as_of_timestamp=as_of_timestamp,
        data_source=_DATA_SOURCE,
        freshness_seconds=0,
        quality_flags=quality_flags,
    )

    return {
        "query": query,
        "window": {
            "since": effective_since.isoformat(),
            "until": effective_until.isoformat(),
        },
        "events": mapped,
        "total_fetched": total_fetched,
        "match_count": match_count,
        "citation": citation,
    }

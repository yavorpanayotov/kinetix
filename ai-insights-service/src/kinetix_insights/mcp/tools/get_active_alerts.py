"""MCP tool ``get_active_alerts`` — book-scoped active alert read.

Reads the current alert feed from notification-service
(``GET /api/v1/notifications/alerts``) and maps the upstream
``List[AlertEventResponse]`` payload — filtered to the caller's book
and to non-resolved statuses — onto the v2 tool-output shape defined
in ``docs/plans/ai-v2.md`` § PR 2:

    {
        "book_id": str,
        "alerts": [
            {
                "id": str,
                "rule_id": str,
                "rule_name": str,
                "type": str,
                "severity": str,
                "message": str,
                "current_value": float,
                "threshold": float,
                "triggered_at": str,
                "status": str,
                "suggested_action": str | None,
                "correlation_id": str | None,
            },
            ...                   # sorted by triggered_at desc
        ],
        "total_fetched": int,     # raw upstream length before filtering
        "active_count": int,      # after all filters
        "citation": Citation,
    }

The single :class:`Citation` returned describes the headline
``active_count`` value — the number of active alerts for the book
after all filters — so callers and the citation verifier have one
canonical numeric anchor for the response.

Filtering, ordering, and pagination are entirely client-side. The
upstream endpoint accepts only ``limit`` and ``status`` query
parameters; it does NOT accept ``bookId``, ``severity`` or ``since``.
The tool always requests ``limit=200`` (with no ``status`` param so
ACK / TRIGGERED / ESCALATED all come back in a single call) and prunes
in-memory, in this order:

1. Book scope (``bookId == book_id``).
2. Active status — keep ``TRIGGERED`` / ``ACKNOWLEDGED`` / ``ESCALATED``,
   drop ``RESOLVED``.
3. Optional ``severity`` filter (``LOW`` | ``MEDIUM`` | ``HIGH`` |
   ``CRITICAL``); anything else is rejected up front as ``BAD_REQUEST``.
4. Optional ``since`` filter (``YYYY-MM-DD``); anything else is rejected
   up front as ``BAD_REQUEST``. The comparison is against the parsed
   ``triggeredAt`` timestamp at start-of-UTC-day.
5. Sort by ``triggered_at`` descending (newest first).

Assumptions and known limitations (v2 scope):

* **Single-book only** — the plan defers risk-manager cross-book
  queries to a future release, so the tool requires ``book_id`` to be
  in the caller's :class:`UserContext.books` and filters the upstream
  payload to that single book. The citation always carries the
  ``CROSS_BOOK_NOT_SUPPORTED`` quality flag so consumers know the
  result is single-book-scoped regardless of role.

* **Active only** — only non-resolved alerts are returned. The
  resolved-only upstream fields (``resolvedAt``, ``resolvedReason``,
  ``escalatedAt``, ``escalatedTo``, ``snoozedUntil``) are intentionally
  omitted from the v2 output. The citation surfaces
  ``INCLUDES_ACKNOWLEDGED`` and ``INCLUDES_ESCALATED`` quality flags
  when any returned alert has those statuses so a downstream consumer
  knows whether the active count includes acknowledged or escalated
  rows.

* **Currency** — the upstream rows are not currency-tagged. v2 hard-codes
  ``"USD"`` on the citation, matching the rest of the PR 2 cohort. The
  tool's result is a count, not a monetary value, so the currency is
  effectively a placeholder.

* **No upstream timestamp** — the endpoint returns a list with no
  per-call timestamp, so the citation pins ``as_of_timestamp`` to the
  call time (injected ``now``) and ``freshness_seconds`` is therefore
  ``0``. This is correct for "data fetched now" semantics on a feed
  endpoint.

The tool fails closed on book-level ACL: if ``book_id`` is not in the
caller's :class:`UserContext.books`, it raises a
``KinetixHttpError(UNAUTHORIZED, 403)`` directly without touching the
HTTP client. It also validates ``severity`` and ``since`` BEFORE the
HTTP call so malformed input never reaches the upstream service.
Upstream ``KinetixHttpError`` (``NOT_FOUND``, ``UPSTREAM_ERROR``, ...)
is propagated unmodified so callers can map into the citation error
contract uniformly.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Callable

from kinetix_insights.citations.models import Citation
from kinetix_insights.clients.kinetix_http_client import (
    KinetixHttpClient,
    KinetixHttpError,
)
from kinetix_insights.clients.user_context import UserContext

_SERVICE_SHORT_NAME: str = "notification"
_DATA_SOURCE: str = "notification-service"
_RESULT_CURRENCY: str = "USD"
_ALERTS_PATH: str = "/api/v1/notifications/alerts"
_FETCH_LIMIT: int = 200

_VALID_SEVERITIES: frozenset[str] = frozenset(
    {"LOW", "MEDIUM", "HIGH", "CRITICAL"}
)
_ACTIVE_STATUSES: frozenset[str] = frozenset(
    {"TRIGGERED", "ACKNOWLEDGED", "ESCALATED"}
)

_CROSS_BOOK_NOT_SUPPORTED_FLAG: str = "CROSS_BOOK_NOT_SUPPORTED"
_INCLUDES_ACKNOWLEDGED_FLAG: str = "INCLUDES_ACKNOWLEDGED"
_INCLUDES_ESCALATED_FLAG: str = "INCLUDES_ESCALATED"


def _default_now() -> datetime:
    return datetime.now(timezone.utc)


def _parse_triggered_at(raw: str) -> datetime:
    """Parse the upstream ``triggeredAt`` ISO 8601 string.

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
    """Project an upstream ``AlertEventResponse`` onto the tool output row."""

    return {
        "id": raw["id"],
        "rule_id": raw["ruleId"],
        "rule_name": raw["ruleName"],
        "type": raw["type"],
        "severity": raw["severity"],
        "message": raw["message"],
        "current_value": float(raw["currentValue"]),
        "threshold": float(raw["threshold"]),
        "triggered_at": raw["triggeredAt"],
        "status": raw["status"],
        "suggested_action": raw.get("suggestedAction"),
        "correlation_id": raw.get("correlationId"),
    }


async def get_active_alerts(
    *,
    book_id: str,
    severity: str | None = None,
    since: str | None = None,
    user: UserContext,
    http: KinetixHttpClient,
    now: Callable[[], datetime] | None = None,
) -> dict[str, Any]:
    """Return the active alerts for ``book_id`` with provenance citation.

    Args:
        book_id: Portfolio identifier. Must be present in ``user.books``
            or the call fails closed with ``UNAUTHORIZED``. Cross-book
            queries are deferred to a future release; the tool is
            always single-book in v2.
        severity: Optional client-side filter — keeps only alerts whose
            upstream ``severity`` matches exactly (``LOW`` | ``MEDIUM``
            | ``HIGH`` | ``CRITICAL``). Any other value raises
            ``BAD_REQUEST`` before the HTTP call.
        since: Optional client-side filter (``YYYY-MM-DD``) — keeps only
            alerts whose ``triggeredAt`` parses to a datetime at or
            after start-of-UTC-day. Any other format raises
            ``BAD_REQUEST`` before the HTTP call.
        user: Caller identity and book scopes; forwarded to the HTTP
            client which stamps ``X-User-Id`` / ``X-User-Books``.
        http: ``KinetixHttpClient`` to dispatch the upstream call.
        now: Injectable clock used to stamp ``as_of_timestamp`` on the
            citation. Defaults to ``datetime.now(timezone.utc)``.

    Returns:
        A dict with ``book_id``, ``alerts`` (filtered/sorted rows in
        tool output shape), ``total_fetched`` (raw upstream count
        before filtering), ``active_count`` (after all filters), and
        ``citation`` (a :class:`Citation` covering ``active_count``).

    Raises:
        KinetixHttpError: ``BAD_REQUEST``/400 for invalid ``severity``
            or ``since``; ``UNAUTHORIZED``/403 when ``book_id`` is
            outside the caller's scope; ``UPSTREAM_ERROR``/502 when the
            upstream payload is not a JSON array; ``NOT_FOUND``,
            ``UPSTREAM_ERROR`` (and other coarse categories) when the
            upstream call itself fails. All propagated unmodified.
    """

    # Validate severity BEFORE any HTTP call.
    if severity is not None and severity not in _VALID_SEVERITIES:
        raise KinetixHttpError(
            status_code=400,
            code="BAD_REQUEST",
            message=(
                f"invalid severity {severity!r}; expected one of "
                f"{sorted(_VALID_SEVERITIES)}"
            ),
            service=_SERVICE_SHORT_NAME,
            path=_ALERTS_PATH,
        )

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
                path=_ALERTS_PATH,
            ) from None

    # Book-level ACL fails closed before any HTTP call.
    if book_id not in user.books:
        raise KinetixHttpError(
            status_code=403,
            code="UNAUTHORIZED",
            message=f"book {book_id!r} not in user scope",
            service=_SERVICE_SHORT_NAME,
            path=_ALERTS_PATH,
        )

    raw_payload = await http.get(
        _SERVICE_SHORT_NAME,
        _ALERTS_PATH,
        params={"limit": _FETCH_LIMIT},
        user=user,
    )
    if not isinstance(raw_payload, list):
        raise KinetixHttpError(
            status_code=502,
            code="UPSTREAM_ERROR",
            message=(
                f"expected array from {_SERVICE_SHORT_NAME}{_ALERTS_PATH}, "
                f"got {type(raw_payload).__name__}"
            ),
            service=_SERVICE_SHORT_NAME,
            path=_ALERTS_PATH,
        )
    raw_alerts: list[dict[str, Any]] = raw_payload

    total_fetched = len(raw_alerts)

    # 1. Book scope.
    scoped = [row for row in raw_alerts if row.get("bookId") == book_id]

    # 2. Active status only.
    active = [row for row in scoped if row.get("status") in _ACTIVE_STATUSES]

    # 3. Optional severity filter.
    if severity is not None:
        active = [row for row in active if row.get("severity") == severity]

    # 4. Optional since filter.
    if since_dt is not None:
        active = [
            row
            for row in active
            if _parse_triggered_at(row["triggeredAt"]) >= since_dt
        ]

    # 5. Sort by triggered_at desc.
    active.sort(
        key=lambda row: _parse_triggered_at(row["triggeredAt"]),
        reverse=True,
    )

    mapped = [_map_row(row) for row in active]
    active_count = len(mapped)

    statuses_present = {row["status"] for row in mapped}
    quality_flags: list[str] = [_CROSS_BOOK_NOT_SUPPORTED_FLAG]
    if "ACKNOWLEDGED" in statuses_present:
        quality_flags.append(_INCLUDES_ACKNOWLEDGED_FLAG)
    if "ESCALATED" in statuses_present:
        quality_flags.append(_INCLUDES_ESCALATED_FLAG)

    now_fn = now or _default_now
    as_of_timestamp = now_fn()

    citation = Citation(
        tool="get_active_alerts",
        params={"book_id": book_id, "severity": severity, "since": since},
        result_field="active_count",
        result_value=float(active_count),
        result_currency=_RESULT_CURRENCY,
        as_of_timestamp=as_of_timestamp,
        data_source=_DATA_SOURCE,
        freshness_seconds=0,
        quality_flags=quality_flags,
    )

    return {
        "book_id": book_id,
        "alerts": mapped,
        "total_fetched": total_fetched,
        "active_count": active_count,
        "citation": citation,
    }

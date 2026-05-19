"""MCP tool ``get_limit_utilisation`` — book-scoped limit definitions read.

Reads the configured limit *definitions* from position-service
(``GET /api/v1/limits``) and projects the rows that apply to a single
book onto the v2 tool-output shape defined in ``plans/ai-v2.md`` § PR 2:

    {
        "limits": [
            {
                "name": str,                # f"{level}:{entityId}:{limit_type}"
                "limit_type": str,          # raw enum from upstream
                "current": None,            # always None in v2 — see below
                "limit": float,             # parsed from upstream limitValue
                "utilisation_pct": None,    # always None in v2 — see below
                "status": "UNKNOWN",        # always "UNKNOWN" in v2 — see below
                "intraday_limit": float | None,
                "overnight_limit": float | None,
                "active": bool,
            },
            ...
        ],
        "total_definitions": int,
        "returned_count": int,
        "citation": Citation,
    }

The single :class:`Citation` returned describes the aggregate
``aggregate_limit`` value — the sum of the ``limit`` cap across the
rows actually returned (after BOOK-level and optional ``limit_type``
filtering) — so callers and the citation verifier have one canonical
numeric anchor for the response.

Assumptions and known limitations (v2 scope):

* **No live utilisation endpoint** — the upstream ``GET /api/v1/limits``
  exposes the limit *definitions* (the cap value, optional intraday /
  overnight caps, the active flag) but does NOT expose live
  consumption. The tool therefore cannot compute ``current``,
  ``utilisation_pct``, or a real ``GREEN`` / ``AMBER`` / ``RED``
  status. To stay honest with the consumer and the citation verifier:

  - ``current`` is always ``None`` and the citation always carries
    the ``CURRENT_VALUE_UNAVAILABLE`` quality flag.
  - ``utilisation_pct`` is always ``None`` and the citation always
    carries the ``UTILISATION_UNAVAILABLE`` quality flag.
  - ``status`` is always the string ``"UNKNOWN"`` — deliberately NOT
    one of ``GREEN`` / ``AMBER`` / ``RED`` — and the citation always
    carries the ``STATUS_UNAVAILABLE`` quality flag.

  Adding a real utilisation endpoint on position-service (or composing
  with a separate consumption tool) is the v2 follow-up to drop these
  flags.

* **BOOK-level scope only** — the upstream payload spans
  ``FIRM`` / ``DIVISION`` / ``DESK`` / ``BOOK`` / ``TRADER`` /
  ``COUNTERPARTY`` levels. v2 keeps only rows where ``level == "BOOK"``
  AND ``entityId == book_id``. Higher-level limits (``FIRM`` /
  ``DIVISION`` / ``DESK``) apply transitively, but rolling them up
  requires the book→desk→division→firm hierarchy which is not
  queryable from the tool side today; transitive rollup is a
  follow-up.

* **No upstream timestamp** — the endpoint returns a list with no
  per-call timestamp, so the citation pins ``as_of_timestamp`` to the
  call time (injected ``now``) and ``freshness_seconds`` is therefore
  ``0``. This is correct for "data fetched now" semantics on a
  definitions endpoint.

* **Currency** — the upstream rows are not currency-tagged. v2 hard-codes
  ``"USD"`` on the citation, matching the 2.1 / 2.2 / 2.3 precedent.
  Multi-currency aggregation is a follow-up.

The tool fails closed on book-level ACL: if ``book_id`` is not in the
caller's :class:`UserContext.books`, it raises a
``KinetixHttpError(UNAUTHORIZED, 403)`` directly without touching the
HTTP client. Upstream ``KinetixHttpError`` (``NOT_FOUND``,
``UPSTREAM_ERROR``, ...) is propagated unmodified so callers can map
into the citation error contract uniformly.
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

_SERVICE_SHORT_NAME: str = "position"
_DATA_SOURCE: str = "position-service"
_RESULT_CURRENCY: str = "USD"
_LIMITS_PATH: str = "/api/v1/limits"
_BOOK_LEVEL: str = "BOOK"

_CURRENT_VALUE_UNAVAILABLE_FLAG: str = "CURRENT_VALUE_UNAVAILABLE"
_UTILISATION_UNAVAILABLE_FLAG: str = "UTILISATION_UNAVAILABLE"
_STATUS_UNAVAILABLE_FLAG: str = "STATUS_UNAVAILABLE"

_UNKNOWN_STATUS: str = "UNKNOWN"


def _default_now() -> datetime:
    return datetime.now(timezone.utc)


def _parse_optional_decimal(raw: str | None) -> float | None:
    """Convert an optional string-formatted decimal to a ``float``.

    Upstream serialises optional caps as either an absent JSON ``null``
    or a string-formatted decimal (``"8000000.00"``). ``None`` stays
    ``None``; anything else is coerced via ``float`` because the v2
    tool surface and citation arithmetic are numeric.
    """

    if raw is None:
        return None
    return float(raw)


def _map_row(raw: dict[str, Any]) -> dict[str, Any]:
    """Project an upstream ``LimitDefinitionResponse`` onto the tool row.

    ``current`` / ``utilisation_pct`` / ``status`` are deliberately
    fixed to ``None`` / ``None`` / ``"UNKNOWN"`` because the upstream
    endpoint does not expose live consumption — see module docstring.
    """

    level = raw["level"]
    entity_id = raw["entityId"]
    limit_type = raw["limitType"]
    return {
        "name": f"{level}:{entity_id}:{limit_type}",
        "limit_type": limit_type,
        "current": None,
        "limit": float(raw["limitValue"]),
        "utilisation_pct": None,
        "status": _UNKNOWN_STATUS,
        "intraday_limit": _parse_optional_decimal(raw.get("intradayLimit")),
        "overnight_limit": _parse_optional_decimal(raw.get("overnightLimit")),
        "active": bool(raw["active"]),
    }


async def get_limit_utilisation(
    *,
    book_id: str,
    limit_type: str | None = None,
    user: UserContext,
    http: KinetixHttpClient,
    now: Callable[[], datetime] | None = None,
) -> dict[str, Any]:
    """Return the BOOK-level limit definitions for ``book_id`` with citation.

    Args:
        book_id: Portfolio identifier. Must be present in ``user.books``
            or the call fails closed with ``UNAUTHORIZED``. Used as
            both the scope check and the ``entityId`` filter on
            ``BOOK``-level rows from the upstream payload.
        limit_type: Optional client-side filter — keeps only rows whose
            upstream ``limitType`` matches exactly (``POSITION`` /
            ``NOTIONAL`` / ``VAR`` / ``CONCENTRATION`` /
            ``ADV_CONCENTRATION`` / ``VAR_BUDGET``). NOT sent on the
            wire — the upstream endpoint accepts no query parameters.
        user: Caller identity and book scopes; forwarded to the HTTP
            client which stamps ``X-User-Id`` / ``X-User-Books``.
        http: ``KinetixHttpClient`` to dispatch the upstream call.
        now: Injectable clock used to stamp ``as_of_timestamp`` on the
            citation. Defaults to ``datetime.now(timezone.utc)``.

    Returns:
        A dict with ``limits`` (BOOK-level rows in tool output shape,
        sorted by ``limit`` descending), ``total_definitions`` (full
        upstream count before filtering), ``returned_count`` (after
        BOOK/entity/limit_type filtering), and ``citation`` (a
        :class:`Citation` covering ``aggregate_limit``).

    Raises:
        KinetixHttpError: ``UNAUTHORIZED``/403 when ``book_id`` is
            outside the caller's scope; ``UPSTREAM_ERROR``/502 when
            the upstream payload is not a JSON array; ``NOT_FOUND``,
            ``UPSTREAM_ERROR`` (and other coarse categories) when the
            upstream call itself fails. All propagated unmodified.
    """

    if book_id not in user.books:
        raise KinetixHttpError(
            status_code=403,
            code="UNAUTHORIZED",
            message=f"book {book_id!r} not in user scope",
            service=_SERVICE_SHORT_NAME,
            path=_LIMITS_PATH,
        )

    raw_payload = await http.get(
        _SERVICE_SHORT_NAME, _LIMITS_PATH, params=None, user=user
    )
    if not isinstance(raw_payload, list):
        raise KinetixHttpError(
            status_code=502,
            code="UPSTREAM_ERROR",
            message=(
                f"expected array from {_SERVICE_SHORT_NAME}{_LIMITS_PATH}, "
                f"got {type(raw_payload).__name__}"
            ),
            service=_SERVICE_SHORT_NAME,
            path=_LIMITS_PATH,
        )
    raw_limits: list[dict[str, Any]] = raw_payload

    total_definitions = len(raw_limits)

    # Scope: BOOK-level rows for this book only. Higher-level rows are
    # out of scope for v2 (see module docstring).
    book_rows: list[dict[str, Any]] = [
        row
        for row in raw_limits
        if row.get("level") == _BOOK_LEVEL and row.get("entityId") == book_id
    ]

    if limit_type is not None:
        book_rows = [row for row in book_rows if row.get("limitType") == limit_type]

    mapped = [_map_row(row) for row in book_rows]
    mapped.sort(key=lambda row: row["limit"], reverse=True)

    returned_count = len(mapped)
    aggregate_limit = float(sum(row["limit"] for row in mapped))

    now_fn = now or _default_now
    as_of_timestamp = now_fn()

    citation = Citation(
        tool="get_limit_utilisation",
        params={"book_id": book_id, "limit_type": limit_type},
        result_field="aggregate_limit",
        result_value=aggregate_limit,
        result_currency=_RESULT_CURRENCY,
        as_of_timestamp=as_of_timestamp,
        data_source=_DATA_SOURCE,
        freshness_seconds=0,
        quality_flags=[
            _CURRENT_VALUE_UNAVAILABLE_FLAG,
            _UTILISATION_UNAVAILABLE_FLAG,
            _STATUS_UNAVAILABLE_FLAG,
        ],
    )

    return {
        "limits": mapped,
        "total_definitions": total_definitions,
        "returned_count": returned_count,
        "citation": citation,
    }

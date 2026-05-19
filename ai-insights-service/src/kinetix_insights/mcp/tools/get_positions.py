"""MCP tool ``get_positions`` — book-scoped position book read.

Reads the current positions for a book from position-service
(``GET /api/v1/books/{bookId}/positions``) and maps the upstream
``List[PositionResponse]`` payload to the v2 tool-output shape defined
in ``plans/ai-v2.md`` § PR 2:

    {
        "positions": [
            {
                "instrument_id": str,
                "asset_class": str,
                "quantity": float,
                "mtm": float,
                "delta": None,
                "pnl_today": float,
                "is_stale": None,
                "instrument_type": str | None,
            },
            ...
        ],
        "total_count": int,
        "returned_count": int,
        "citation": Citation,
    }

The single :class:`Citation` returned describes the aggregate
``total_mtm`` — the sum of ``mtm`` across the rows actually returned
(after client-side filtering / ``top_n`` truncation) — so callers and
the citation verifier have one canonical numeric anchor for the
response.

Filtering and ordering are entirely client-side: the upstream endpoint
does not support ``instrumentId``, ``assetClass``, or ``top_n`` as
query parameters, so the tool fetches the full book and prunes
in-memory. ``total_count`` reflects the upstream count before
filtering; ``returned_count`` reflects what's in ``positions``.

Assumptions and known limitations (v2 scope):

* **Currency** — the upstream MoneyDto strings are summed numerically
  and v2 hard-codes ``"USD"`` on the citation, matching the demo and
  the 2.1 precedent. Multi-currency aggregation is a follow-up.
* **``delta``** — position-service does not surface Greeks on this
  endpoint (no field on ``PositionResponse``). Every row's ``delta``
  is therefore ``None`` and the citation always carries the
  ``DELTA_UNAVAILABLE`` quality flag. Adding delta requires either an
  upstream schema change or a parallel risk-engine lookup.
* **``pnl_today``** — position-service does not expose a distinct
  today-only P&L field; the closest analogue is ``unrealizedPnl``
  (lifetime unrealised P&L). v2 maps ``pnl_today`` from
  ``unrealizedPnl.amount`` and always surfaces the
  ``PNL_TODAY_FROM_UNREALIZED`` quality flag so consumers know what
  they are reading. Adding a true today-only P&L is a follow-up.
* **``is_stale`` / staleness** — the upstream DTO does not carry
  ``updatedAt``; staleness against the latest price-service
  timestamp is therefore not computable here in v2. Every row's
  ``is_stale`` is ``None`` and the citation always carries the
  ``STALENESS_UNAVAILABLE`` quality flag. Adding staleness requires
  either a DTO change in position-service or a parallel
  price-service lookup.
* **``as_of_timestamp`` / freshness** — the upstream response carries
  no per-call timestamp, so the citation pins ``as_of_timestamp`` to
  the call time (injected ``now``) and freshness is therefore
  ``0`` seconds. This is correct for "data fetched now" semantics but
  cannot distinguish stale upstream caches from a fresh read; see the
  staleness limitation above.

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

_DELTA_UNAVAILABLE_FLAG: str = "DELTA_UNAVAILABLE"
_PNL_TODAY_FROM_UNREALIZED_FLAG: str = "PNL_TODAY_FROM_UNREALIZED"
_STALENESS_UNAVAILABLE_FLAG: str = "STALENESS_UNAVAILABLE"


def _default_now() -> datetime:
    return datetime.now(timezone.utc)


def _map_row(raw: dict[str, Any]) -> dict[str, Any]:
    """Project an upstream ``PositionResponse`` onto the tool output row.

    Quantity and the MoneyDto amounts are upstream-serialised as
    string-formatted decimals; we convert to ``float`` here because the
    v2 tool surface and citation arithmetic are numeric. ``delta`` and
    ``is_stale`` are unavailable in v2 (see module docstring) — every
    row carries ``None`` for them.
    """

    return {
        "instrument_id": raw["instrumentId"],
        "asset_class": raw["assetClass"],
        "quantity": float(raw["quantity"]),
        "mtm": float(raw["marketValue"]["amount"]),
        "delta": None,
        "pnl_today": float(raw["unrealizedPnl"]["amount"]),
        "is_stale": None,
        "instrument_type": raw.get("instrumentType"),
    }


async def get_positions(
    *,
    book_id: str,
    instrument_id: str | None = None,
    asset_class: str | None = None,
    top_n: int | None = None,
    user: UserContext,
    http: KinetixHttpClient,
    now: Callable[[], datetime] | None = None,
) -> dict[str, Any]:
    """Return the current positions for ``book_id`` with provenance citation.

    Args:
        book_id: Portfolio identifier. Must be present in ``user.books``
            or the call fails closed with ``UNAUTHORIZED``.
        instrument_id: Optional client-side filter — keeps only rows
            whose upstream ``instrumentId`` matches exactly.
        asset_class: Optional client-side filter — keeps only rows
            whose upstream ``assetClass`` matches exactly.
        top_n: Optional truncation — after filtering, sort the
            remaining rows by ``abs(mtm)`` descending and keep the
            first ``top_n``.
        user: Caller identity and book scopes; forwarded to the HTTP
            client which stamps ``X-User-Id`` / ``X-User-Books``.
        http: ``KinetixHttpClient`` to dispatch the upstream call.
        now: Injectable clock used to stamp ``as_of_timestamp`` on the
            citation. Defaults to ``datetime.now(timezone.utc)``.

    Returns:
        A dict with ``positions`` (filtered/truncated rows in tool
        output shape), ``total_count`` (full upstream count before
        filtering), ``returned_count`` (after filtering / ``top_n``),
        and ``citation`` (a :class:`Citation` covering ``total_mtm``).

    Raises:
        KinetixHttpError: ``UNAUTHORIZED``/403 when ``book_id`` is
            outside the caller's scope; ``NOT_FOUND``,
            ``UPSTREAM_ERROR`` (and other coarse categories) when the
            upstream call fails. All propagated unmodified.
    """

    path = f"/api/v1/books/{book_id}/positions"

    if book_id not in user.books:
        raise KinetixHttpError(
            status_code=403,
            code="UNAUTHORIZED",
            message=f"book {book_id!r} not in user scope",
            service=_SERVICE_SHORT_NAME,
            path=path,
        )

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
    raw_positions: list[dict[str, Any]] = raw_payload

    total_count = len(raw_positions)
    mapped: list[dict[str, Any]] = [_map_row(row) for row in raw_positions]

    if instrument_id is not None:
        mapped = [row for row in mapped if row["instrument_id"] == instrument_id]
    if asset_class is not None:
        mapped = [row for row in mapped if row["asset_class"] == asset_class]
    if top_n is not None:
        mapped = sorted(mapped, key=lambda row: abs(row["mtm"]), reverse=True)[
            :top_n
        ]

    returned_count = len(mapped)
    total_mtm = float(sum(row["mtm"] for row in mapped))

    now_fn = now or _default_now
    as_of_timestamp = now_fn()

    citation = Citation(
        tool="get_positions",
        params={
            "book_id": book_id,
            "instrument_id": instrument_id,
            "asset_class": asset_class,
            "top_n": top_n,
        },
        result_field="total_mtm",
        result_value=total_mtm,
        result_currency=_RESULT_CURRENCY,
        as_of_timestamp=as_of_timestamp,
        data_source=_DATA_SOURCE,
        freshness_seconds=0,
        quality_flags=[
            _DELTA_UNAVAILABLE_FLAG,
            _PNL_TODAY_FROM_UNREALIZED_FLAG,
            _STALENESS_UNAVAILABLE_FLAG,
        ],
    )

    return {
        "positions": mapped,
        "total_count": total_count,
        "returned_count": returned_count,
        "citation": citation,
    }

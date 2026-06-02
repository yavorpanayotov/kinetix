"""MCP tool ``get_greeks_summary`` — book-level Greeks read.

Reads the latest cached ``VaRResultResponse`` for a book from
risk-orchestrator (``GET /api/v1/risk/var/{bookId}``) and projects the
optional ``greeks`` / ``positionGreeks`` slices of the payload onto
the v2 tool-output shape defined in ``docs/plans/ai-v2.md`` § PR 2:

    {
        "aggregate": {
            "delta": float,
            "gamma": float,
            "vega":  float,
            "theta": float,
            "rho":   float,
        },
        "by_underlier": [
            {
                "underlier": str,
                "delta":  float,
                "gamma":  float,
                "vega":   float,
                "theta":  float,
                "rho":    float,
            },
            ...
        ],
        "as_of": str | None,
        "underlier": str | None,
        "citation": Citation,
    }

``aggregate.delta``/``gamma``/``vega`` are summed across
``greeks.assetClassGreeks``; ``aggregate.theta``/``rho`` come from the
top-level ``greeks.theta`` / ``greeks.rho`` fields (already aggregate
across asset classes upstream).

``by_underlier`` is bucketed from ``positionGreeks`` using a v2
heuristic: take the substring of ``instrumentId`` before the first
``_``; fall back to the full ``instrumentId`` if no underscore is
present. Rows are sorted by ``abs(delta)`` descending so the
heaviest-exposure underlier comes first. When the caller passes
``underlier``, only matching rows are kept.

The single :class:`Citation` returned describes the headline
``aggregate.delta`` value: where it came from (``risk-orchestrator``),
the upstream ``calculatedAt`` timestamp, and a ``freshness_seconds``
computed at call time against an injectable ``now`` callable so tests
can pin time.

Assumptions and known limitations (v2 scope):

* **SOD snapshot read** — the plan spec calls for reading
  risk-orchestrator's ``sod_greek_snapshots`` table in addition to the
  latest valuation Greeks. The service exposes no HTTP endpoint over
  that table today, so this tool reads only the latest valuation
  Greeks and surfaces the ``SOD_GREEKS_UNAVAILABLE`` quality flag on
  every call. Closing the gap requires a new upstream endpoint
  (cross-service change) and is deferred.
* **``by_underlier`` derivation** — the upstream
  ``positionGreeks`` keys by ``instrumentId``, not by underlier.
  v2 derives underlier client-side from the instrument id
  (``"EURUSD_CALL_20260601_1.09"`` → ``"EURUSD"``;
  ``"EURUSD"`` → ``"EURUSD"``) and surfaces the
  ``BY_UNDERLIER_DERIVED_FROM_INSTRUMENT_ID`` quality flag. A
  structured ``underlier`` field on the upstream DTO would replace
  this heuristic in a follow-up.
* **``underlier`` filter** — not a wire parameter on the upstream
  endpoint; filtering happens client-side after the call.
* **Currency** — the upstream ``VaRResultResponse`` does not carry a
  currency. v2 hard-codes ``"USD"`` on the citation, matching the
  2.1 / 2.2 precedent. Multi-currency citations are a follow-up.

If the upstream response carries no ``greeks`` (e.g. the latest
valuation pipeline ran without Greeks), the tool raises
``KinetixHttpError(NOT_FOUND, 404)`` so callers can map it into the
citation error contract uniformly with other missing-resource cases.

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

_SERVICE: str = "risk-orchestrator"
_RESULT_CURRENCY: str = "USD"

_SOD_GREEKS_UNAVAILABLE_FLAG: str = "SOD_GREEKS_UNAVAILABLE"
_BY_UNDERLIER_DERIVED_FLAG: str = "BY_UNDERLIER_DERIVED_FROM_INSTRUMENT_ID"
_STALE_FLAG: str = "STALE"


def _default_now() -> datetime:
    return datetime.now(timezone.utc)


def _parse_calculated_at(raw: str) -> datetime:
    """Parse the upstream ``calculatedAt`` ISO 8601 string.

    The upstream serialises with a trailing ``Z``; ``fromisoformat``
    accepts ``+00:00`` natively, so we normalise.
    """

    normalised = raw.replace("Z", "+00:00") if raw.endswith("Z") else raw
    parsed = datetime.fromisoformat(normalised)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed


def _derive_underlier(instrument_id: str) -> str:
    """Return the substring of ``instrument_id`` before the first ``_``.

    Falls back to the full ``instrument_id`` when no underscore is
    present. Documented as a v2 heuristic; see module docstring.
    """

    if "_" in instrument_id:
        return instrument_id.split("_", 1)[0]
    return instrument_id


def _aggregate_from_greeks(greeks: dict[str, Any]) -> dict[str, float]:
    """Reduce ``greeks`` to the tool's ``aggregate`` shape.

    ``delta`` / ``gamma`` / ``vega`` are summed across
    ``assetClassGreeks``; ``theta`` / ``rho`` come straight from the
    top-level fields (already aggregate upstream).
    """

    rows: list[dict[str, Any]] = greeks.get("assetClassGreeks") or []
    delta = sum(float(row["delta"]) for row in rows)
    gamma = sum(float(row["gamma"]) for row in rows)
    vega = sum(float(row["vega"]) for row in rows)
    return {
        "delta": float(delta),
        "gamma": float(gamma),
        "vega": float(vega),
        "theta": float(greeks["theta"]),
        "rho": float(greeks["rho"]),
    }


def _bucket_by_underlier(
    position_greeks: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Group ``positionGreeks`` rows by derived underlier and sum Greeks."""

    buckets: dict[str, dict[str, float]] = {}
    for row in position_greeks:
        underlier = _derive_underlier(row["instrumentId"])
        bucket = buckets.setdefault(
            underlier,
            {"delta": 0.0, "gamma": 0.0, "vega": 0.0, "theta": 0.0, "rho": 0.0},
        )
        bucket["delta"] += float(row["delta"])
        bucket["gamma"] += float(row["gamma"])
        bucket["vega"] += float(row["vega"])
        bucket["theta"] += float(row["theta"])
        bucket["rho"] += float(row["rho"])

    return [
        {"underlier": underlier, **values} for underlier, values in buckets.items()
    ]


async def get_greeks_summary(
    *,
    book_id: str,
    as_of: str | None = None,
    underlier: str | None = None,
    user: UserContext,
    http: KinetixHttpClient,
    now: Callable[[], datetime] | None = None,
) -> dict[str, Any]:
    """Return aggregate + by-underlier Greeks for ``book_id`` with citation.

    Args:
        book_id: Portfolio identifier. Must be present in ``user.books``
            or the call fails closed with ``UNAUTHORIZED``.
        as_of: Optional ISO date ``YYYY-MM-DD``. When supplied, sent
            upstream as the ``valuationDate`` query parameter to fetch
            the historical snapshot. Otherwise the latest cached result
            is returned.
        underlier: Optional client-side filter — keeps only the
            by-underlier row whose derived underlier matches exactly.
            NOT sent on the wire (no upstream parameter for this).
        user: Caller identity and book scopes; forwarded to the HTTP
            client which stamps ``X-User-Id`` / ``X-User-Books``.
        http: ``KinetixHttpClient`` to dispatch the upstream call.
        now: Injectable clock used to compute ``freshness_seconds``.
            Defaults to ``datetime.now(timezone.utc)``.

    Returns:
        A dict with ``aggregate`` (book-level Greeks), ``by_underlier``
        (per-underlier breakdown, sorted by ``abs(delta)`` desc and
        optionally filtered), ``as_of`` (echo), ``underlier`` (echo),
        and ``citation`` (a :class:`Citation` covering
        ``aggregate_delta``).

    Raises:
        KinetixHttpError: ``UNAUTHORIZED``/403 when ``book_id`` is
            outside the caller's scope; ``NOT_FOUND``/404 when the
            latest valuation carries no Greeks; ``NOT_FOUND``,
            ``UPSTREAM_ERROR`` (and other coarse categories) when the
            upstream call itself fails. All propagated unmodified.
    """

    path = f"/api/v1/risk/var/{book_id}"

    if book_id not in user.books:
        raise KinetixHttpError(
            status_code=403,
            code="UNAUTHORIZED",
            message=f"book {book_id!r} not in user scope",
            service=_SERVICE,
            path=path,
        )

    params: dict[str, Any] | None = None
    if as_of is not None:
        params = {"valuationDate": as_of}

    raw = await http.get(_SERVICE, path, params=params, user=user)
    if not isinstance(raw, dict):
        raise KinetixHttpError(
            status_code=502,
            code="UPSTREAM_ERROR",
            message=f"expected object from {_SERVICE}{path}, got {type(raw).__name__}",
            service=_SERVICE,
            path=path,
        )
    payload: dict[str, Any] = raw

    greeks = payload.get("greeks")
    if not isinstance(greeks, dict):
        raise KinetixHttpError(
            status_code=404,
            code="NOT_FOUND",
            message="no Greeks in latest valuation",
            service=_SERVICE,
            path=path,
        )

    aggregate = _aggregate_from_greeks(greeks)

    position_greeks_raw = payload.get("positionGreeks") or []
    position_greeks: list[dict[str, Any]] = (
        position_greeks_raw if isinstance(position_greeks_raw, list) else []
    )
    by_underlier = _bucket_by_underlier(position_greeks)
    if underlier is not None:
        by_underlier = [row for row in by_underlier if row["underlier"] == underlier]
    by_underlier.sort(key=lambda row: abs(row["delta"]), reverse=True)

    as_of_timestamp = _parse_calculated_at(payload["calculatedAt"])
    now_fn = now or _default_now
    freshness_seconds = int((now_fn() - as_of_timestamp).total_seconds())

    quality_flags: list[str] = [
        _SOD_GREEKS_UNAVAILABLE_FLAG,
        _BY_UNDERLIER_DERIVED_FLAG,
    ]
    if payload.get("stale") is True:
        quality_flags.append(_STALE_FLAG)

    citation = Citation(
        tool="get_greeks_summary",
        params={"book_id": book_id, "as_of": as_of, "underlier": underlier},
        result_field="aggregate_delta",
        result_value=aggregate["delta"],
        result_currency=_RESULT_CURRENCY,
        as_of_timestamp=as_of_timestamp,
        data_source=_SERVICE,
        freshness_seconds=freshness_seconds,
        quality_flags=quality_flags,
    )

    return {
        "aggregate": aggregate,
        "by_underlier": by_underlier,
        "as_of": as_of,
        "underlier": underlier,
        "citation": citation,
    }

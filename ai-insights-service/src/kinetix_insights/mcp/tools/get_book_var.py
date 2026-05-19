"""MCP tool ``get_book_var`` — single-book VaR read.

Reads the latest cached ``VaRResultResponse`` for a book from
risk-orchestrator (``GET /api/v1/risk/var/{bookId}``) and maps the
upstream payload to the v2 tool-output shape defined in
``plans/ai-v2.md`` § PR 2:

    {
        "total_var": float,
        "var_by_asset_class": [
            {"asset_class": str, "var_contribution": float,
             "percentage_of_total": float},
            ...
        ],
        "confidence_level": str,
        "lookback_days": int | None,
        "citation": Citation,
    }

The single :class:`Citation` returned describes the headline
``total_var`` value: where it came from (``risk-orchestrator``), the
upstream ``calculatedAt`` timestamp, and a ``freshness_seconds``
computed at call time against an injectable ``now`` callable so tests
can pin time.

Assumptions and known limitations (v2 scope):

* **Currency** — the upstream ``VaRResultResponse`` does not carry a
  currency. v2 hard-codes ``"USD"`` on the citation, matching the
  existing demo posture. Multi-currency citations are a follow-up.
* **Lookback** — the upstream response does not expose the lookback
  window used to compute the VaR. ``lookback_days`` in the tool output
  is therefore always ``None`` and the citation always carries the
  ``LOOKBACK_UNAVAILABLE`` quality flag. Adding lookback requires an
  upstream schema change.
* **``method`` parameter** — risk-orchestrator's ``GET`` endpoint does
  not accept a calculation type query parameter; ``method`` is
  therefore recorded in the citation ``params`` for provenance but is
  not sent on the wire. The matching ``POST`` endpoint accepts a
  ``calculationType`` body field but is a write operation outside the
  scope of this read-only tool.
* **``or by jobId`` (per plan)** — the upstream service exposes no
  ``/jobs/{jobId}`` lookup. Until one is added, this tool scopes to the
  latest cached / valuation-date snapshot only. Adding job-id lookup
  is a follow-up.

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
_LOOKBACK_UNAVAILABLE_FLAG: str = "LOOKBACK_UNAVAILABLE"
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


async def get_book_var(
    *,
    book_id: str,
    as_of: str | None = None,
    method: str | None = None,
    user: UserContext,
    http: KinetixHttpClient,
    now: Callable[[], datetime] | None = None,
) -> dict[str, Any]:
    """Return the latest VaR for ``book_id`` with provenance citation.

    Args:
        book_id: Portfolio identifier. Must be present in ``user.books``
            or the call fails closed with ``UNAUTHORIZED``.
        as_of: Optional ISO date ``YYYY-MM-DD``. When supplied, sent
            upstream as the ``valuationDate`` query parameter to fetch
            the historical snapshot. Otherwise the latest cached result
            is returned.
        method: Optional VaR methodology (``PARAMETRIC`` |
            ``HISTORICAL`` | ``MONTE_CARLO``). NOT supported as a query
            parameter by the upstream ``GET`` endpoint; recorded in
            the citation ``params`` for provenance only.
        user: Caller identity and book scopes; forwarded to the HTTP
            client which stamps ``X-User-Id`` / ``X-User-Books``.
        http: ``KinetixHttpClient`` to dispatch the upstream call.
        now: Injectable clock used to compute ``freshness_seconds``.
            Defaults to ``datetime.now(timezone.utc)``.

    Returns:
        A dict with ``total_var`` (float), ``var_by_asset_class``
        (list of breakdown rows), ``confidence_level`` (str),
        ``lookback_days`` (always ``None`` in v2), and ``citation``
        (a :class:`Citation` covering ``total_var``).

    Raises:
        KinetixHttpError: ``UNAUTHORIZED``/403 when ``book_id`` is
            outside the caller's scope; ``NOT_FOUND``,
            ``UPSTREAM_ERROR`` (and other coarse categories) when the
            upstream call fails. All propagated unmodified.
    """

    if book_id not in user.books:
        path = f"/api/v1/risk/var/{book_id}"
        raise KinetixHttpError(
            status_code=403,
            code="UNAUTHORIZED",
            message=f"book {book_id!r} not in user scope",
            service=_SERVICE,
            path=path,
        )

    path = f"/api/v1/risk/var/{book_id}"
    params: dict[str, Any] | None = None
    if as_of is not None:
        params = {"valuationDate": as_of}

    payload = await http.get(_SERVICE, path, params=params, user=user)

    total_var = float(payload["varValue"])
    breakdown_raw = payload.get("componentBreakdown") or []
    var_by_asset_class: list[dict[str, Any]] = [
        {
            "asset_class": row["assetClass"],
            "var_contribution": float(row["varContribution"]),
            "percentage_of_total": float(row["percentageOfTotal"]),
        }
        for row in breakdown_raw
    ]
    confidence_level = payload["confidenceLevel"]

    as_of_timestamp = _parse_calculated_at(payload["calculatedAt"])
    now_fn = now or _default_now
    freshness_seconds = int((now_fn() - as_of_timestamp).total_seconds())

    quality_flags: list[str] = [_LOOKBACK_UNAVAILABLE_FLAG]
    if payload.get("stale") is True:
        quality_flags.append(_STALE_FLAG)

    citation = Citation(
        tool="get_book_var",
        params={"book_id": book_id, "as_of": as_of, "method": method},
        result_field="total_var",
        result_value=total_var,
        result_currency=_RESULT_CURRENCY,
        as_of_timestamp=as_of_timestamp,
        data_source=_SERVICE,
        freshness_seconds=freshness_seconds,
        quality_flags=quality_flags,
    )

    return {
        "total_var": total_var,
        "var_by_asset_class": var_by_asset_class,
        "confidence_level": confidence_level,
        "lookback_days": None,
        "citation": citation,
    }

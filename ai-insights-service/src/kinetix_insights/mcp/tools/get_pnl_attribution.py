"""MCP tool ``get_pnl_attribution`` — book-level P&L attribution read.

Reads first- and second-order P&L attribution for a book from
risk-orchestrator and maps the upstream payload to the v2 tool-output
shape defined in ``plans/ai-v2.md`` § PR 2:

    {
        "period": "daily" | "intraday",
        "book_id": str,
        "date": str | None,
        "total_pnl": float,
        "components": {
            "delta_pnl": float, "gamma_pnl": float, "vega_pnl": float,
            "theta_pnl": float, "rho_pnl": float, "vanna_pnl": float,
            "volga_pnl": float, "charm_pnl": float,
            "cross_gamma_pnl": float, "unexplained_pnl": float,
        },
        "snapshots": list[dict] | None,
        "data_quality": str,
        "citation": Citation,
    }

Two upstream endpoints are fanned out behind a single tool surface:

* **Daily** — ``GET /api/v1/risk/pnl-attribution/{bookId}`` returns a
  single ``PnlAttributionResponse`` carrying first- and second-order
  Greek attribution plus an ``AttributionDataQuality`` enum
  (``FULL_ATTRIBUTION`` | ``PRICE_ONLY`` | ``STALE_GREEKS``). The tool
  forwards ``date`` as the ``date`` query parameter when supplied.
* **Intraday** — ``GET /api/v1/risk/pnl/intraday/{bookId}`` returns an
  ``IntradayPnlSeriesResponse`` (a list of intraday snapshots). The
  upstream endpoint REQUIRES ``from`` / ``to`` ISO-8601 instants; the
  tool synthesises a full-UTC-day window
  ``T00:00:00Z … T23:59:59Z`` from either the supplied ``date`` or the
  day in injected ``now()`` when ``date`` is omitted.

The single :class:`Citation` returned describes the headline
``total_pnl`` value. The plan specifically requires surfacing the
upstream data-quality value in citation ``quality_flags`` rather than
burying it in the response body, so:

* **Daily** — the raw ``dataQualityFlag`` enum string is added as a
  flag (e.g. ``"FULL_ATTRIBUTION"``). When the value is
  ``"STALE_GREEKS"``, the generic ``"STALE"`` flag is ALSO added so
  downstream consumers that recognise only ``STALE`` (matching the
  2.1 convention) still light up.
* **Intraday** — the upstream uses ``dataQualityWarning`` (nullable
  string) instead of an enum. The latest snapshot's value (or
  ``"OK"`` when ``null``) is prefixed with ``"INTRADAY:"`` and added
  as a flag (e.g. ``"INTRADAY:OK"`` or ``"INTRADAY:stale_prices"``).

Assumptions and known limitations (v2 scope):

* **Currency** — neither upstream payload is currency-tagged. v2
  hard-codes ``"USD"`` on the citation, matching the 2.1 / 2.2 / 2.3 /
  2.4 precedent. Multi-currency aggregation is a follow-up.
* **Intraday date inference** — when ``period="intraday"`` and ``date``
  is omitted, the tool uses the day in injected ``now()`` (UTC). This
  matches the "what's happening today" use case while keeping the
  synthesis deterministic under a pinned clock.
* **Empty intraday series** — an empty ``snapshots`` array is treated
  as a ``NOT_FOUND``: there is nothing meaningful to attribute and
  callers should map this into the citation error contract, not
  receive a synthetic zero.
* **``positionAttributions``** — the daily response includes per-
  position breakdowns. v2's tool surface focuses on book-level
  attribution and intentionally drops position-level detail to keep
  the output narrow. Adding a per-position drill-down is a follow-up.

The tool fails closed on:

* **Book-level ACL** — if ``book_id`` is not in the caller's
  :class:`UserContext.books`, ``KinetixHttpError(UNAUTHORIZED, 403)``
  is raised directly without touching the HTTP client.
* **Invalid period** — anything other than ``"daily"`` / ``"intraday"``
  / ``None`` raises ``KinetixHttpError(BAD_REQUEST, 400)`` before any
  upstream call.

Upstream ``KinetixHttpError`` (``NOT_FOUND``, ``UPSTREAM_ERROR``, ...)
is propagated unmodified so callers can map upstream failures into the
citation error contract uniformly.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Callable, Literal

from kinetix_insights.citations.models import Citation
from kinetix_insights.clients.kinetix_http_client import (
    KinetixHttpClient,
    KinetixHttpError,
)
from kinetix_insights.clients.user_context import UserContext

PnlPeriod = Literal["daily", "intraday"]

_SERVICE: str = "risk-orchestrator"
_RESULT_CURRENCY: str = "USD"
_DAILY_PATH_PREFIX: str = "/api/v1/risk/pnl-attribution"
_INTRADAY_PATH_PREFIX: str = "/api/v1/risk/pnl/intraday"

_STALE_FLAG: str = "STALE"
_STALE_GREEKS_VALUE: str = "STALE_GREEKS"
_INTRADAY_FLAG_PREFIX: str = "INTRADAY:"
_INTRADAY_OK_LABEL: str = "OK"

_VALID_PERIODS: tuple[str, ...] = ("daily", "intraday")


def _default_now() -> datetime:
    return datetime.now(timezone.utc)


def _parse_iso_instant(raw: str) -> datetime:
    """Parse an upstream ISO 8601 instant, normalising trailing ``Z``.

    Both ``calculatedAt`` (daily) and ``snapshotAt`` (intraday) are
    serialised with a trailing ``Z``; ``fromisoformat`` accepts
    ``+00:00`` natively, so we normalise.
    """

    normalised = raw.replace("Z", "+00:00") if raw.endswith("Z") else raw
    parsed = datetime.fromisoformat(normalised)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed


def _map_snapshot(raw: dict[str, Any]) -> dict[str, Any]:
    """Project an intraday snapshot DTO onto the narrow tool row.

    Drops ``instrumentPnl`` / ``correlationId`` / ``dataQualityWarning``
    from the per-snapshot mapping — the warning is surfaced once via
    the citation; the others are out of v2 scope.
    """

    return {
        "snapshot_at": raw["snapshotAt"],
        "total_pnl": float(raw["totalPnl"]),
        "delta_pnl": float(raw["deltaPnl"]),
        "gamma_pnl": float(raw["gammaPnl"]),
        "vega_pnl": float(raw["vegaPnl"]),
        "theta_pnl": float(raw["thetaPnl"]),
        "rho_pnl": float(raw["rhoPnl"]),
        "vanna_pnl": float(raw["vannaPnl"]),
        "volga_pnl": float(raw["volgaPnl"]),
        "charm_pnl": float(raw["charmPnl"]),
        "cross_gamma_pnl": float(raw["crossGammaPnl"]),
        "unexplained_pnl": float(raw["unexplainedPnl"]),
    }


def _components_from_daily(payload: dict[str, Any]) -> dict[str, float]:
    return {
        "delta_pnl": float(payload["deltaPnl"]),
        "gamma_pnl": float(payload["gammaPnl"]),
        "vega_pnl": float(payload["vegaPnl"]),
        "theta_pnl": float(payload["thetaPnl"]),
        "rho_pnl": float(payload["rhoPnl"]),
        "vanna_pnl": float(payload["vannaPnl"]),
        "volga_pnl": float(payload["volgaPnl"]),
        "charm_pnl": float(payload["charmPnl"]),
        "cross_gamma_pnl": float(payload["crossGammaPnl"]),
        "unexplained_pnl": float(payload["unexplainedPnl"]),
    }


def _components_from_snapshot(snapshot: dict[str, Any]) -> dict[str, float]:
    """Re-derive the headline ``components`` dict from a mapped snapshot."""

    return {
        "delta_pnl": snapshot["delta_pnl"],
        "gamma_pnl": snapshot["gamma_pnl"],
        "vega_pnl": snapshot["vega_pnl"],
        "theta_pnl": snapshot["theta_pnl"],
        "rho_pnl": snapshot["rho_pnl"],
        "vanna_pnl": snapshot["vanna_pnl"],
        "volga_pnl": snapshot["volga_pnl"],
        "charm_pnl": snapshot["charm_pnl"],
        "cross_gamma_pnl": snapshot["cross_gamma_pnl"],
        "unexplained_pnl": snapshot["unexplained_pnl"],
    }


async def get_pnl_attribution(
    *,
    book_id: str,
    date: str | None = None,
    period: PnlPeriod | None = None,
    user: UserContext,
    http: KinetixHttpClient,
    now: Callable[[], datetime] | None = None,
) -> dict[str, Any]:
    """Return P&L attribution for ``book_id`` with provenance citation.

    Args:
        book_id: Portfolio identifier. Must be present in
            ``user.books`` or the call fails closed with
            ``UNAUTHORIZED``.
        date: Optional ISO date ``YYYY-MM-DD``. For ``period="daily"``,
            forwarded as the ``date`` query parameter. For
            ``period="intraday"``, used to synthesise the
            ``from``/``to`` window bounding the snapshot series.
        period: ``"daily"`` (default when ``None``) or ``"intraday"``.
            Anything else raises ``BAD_REQUEST`` without an upstream
            call.
        user: Caller identity and book scopes; forwarded to the HTTP
            client which stamps ``X-User-Id`` / ``X-User-Books``.
        http: ``KinetixHttpClient`` to dispatch the upstream call.
        now: Injectable clock used to compute ``freshness_seconds`` and
            to derive the intraday window when ``date`` is omitted.
            Defaults to ``datetime.now(timezone.utc)``.

    Returns:
        A dict matching the module docstring shape with ``citation``
        covering ``total_pnl``.

    Raises:
        KinetixHttpError: ``UNAUTHORIZED``/403 when ``book_id`` is
            outside the caller's scope; ``BAD_REQUEST``/400 when
            ``period`` is not ``"daily"`` / ``"intraday"`` / ``None``;
            ``NOT_FOUND``/404 when intraday returns no snapshots;
            ``UPSTREAM_ERROR``/502 on payload-shape drift; upstream
            errors propagate unmodified.
    """

    # Period validation runs BEFORE ACL so an obviously malformed
    # request gets a 400 even if the caller has access — this matches
    # the principle of failing on the cheapest check first.
    if period is not None and period not in _VALID_PERIODS:
        raise KinetixHttpError(
            status_code=400,
            code="BAD_REQUEST",
            message=(
                f"invalid period {period!r}; expected one of "
                f"{_VALID_PERIODS} or None"
            ),
            service=_SERVICE,
            path="",
        )

    effective_period: PnlPeriod = period if period is not None else "daily"

    if effective_period == "daily":
        path = f"{_DAILY_PATH_PREFIX}/{book_id}"
    else:
        path = f"{_INTRADAY_PATH_PREFIX}/{book_id}"

    if book_id not in user.books:
        raise KinetixHttpError(
            status_code=403,
            code="UNAUTHORIZED",
            message=f"book {book_id!r} not in user scope",
            service=_SERVICE,
            path=path,
        )

    now_fn = now or _default_now

    if effective_period == "daily":
        params: dict[str, Any] | None = None
        if date is not None:
            params = {"date": date}

        raw = await http.get(_SERVICE, path, params=params, user=user)
        if not isinstance(raw, dict):
            raise KinetixHttpError(
                status_code=502,
                code="UPSTREAM_ERROR",
                message=(
                    f"expected object from {_SERVICE}{path}, "
                    f"got {type(raw).__name__}"
                ),
                service=_SERVICE,
                path=path,
            )
        payload: dict[str, Any] = raw

        total_pnl = float(payload["totalPnl"])
        components = _components_from_daily(payload)
        echoed_date = payload.get("date")
        as_of_timestamp = _parse_iso_instant(payload["calculatedAt"])
        data_quality_value: str = payload["dataQualityFlag"]
        quality_flags: list[str] = [data_quality_value]
        if data_quality_value == _STALE_GREEKS_VALUE:
            quality_flags.append(_STALE_FLAG)
        snapshots_out: list[dict[str, Any]] | None = None

    else:  # intraday
        # Derive the window from explicit ``date`` or now()'s UTC day.
        window_day = date if date is not None else now_fn().strftime("%Y-%m-%d")
        from_param = f"{window_day}T00:00:00Z"
        to_param = f"{window_day}T23:59:59Z"
        params = {"from": from_param, "to": to_param}

        raw = await http.get(_SERVICE, path, params=params, user=user)
        if not isinstance(raw, dict):
            raise KinetixHttpError(
                status_code=502,
                code="UPSTREAM_ERROR",
                message=(
                    f"expected object from {_SERVICE}{path}, "
                    f"got {type(raw).__name__}"
                ),
                service=_SERVICE,
                path=path,
            )
        payload = raw

        raw_snapshots = payload.get("snapshots") or []
        if not raw_snapshots:
            raise KinetixHttpError(
                status_code=404,
                code="NOT_FOUND",
                message=(
                    f"no intraday snapshots for {book_id} on {window_day}"
                ),
                service=_SERVICE,
                path=path,
            )

        mapped_snapshots = [_map_snapshot(snap) for snap in raw_snapshots]
        last_snapshot_raw = raw_snapshots[-1]
        last_snapshot_mapped = mapped_snapshots[-1]

        total_pnl = last_snapshot_mapped["total_pnl"]
        components = _components_from_snapshot(last_snapshot_mapped)
        echoed_date = window_day
        as_of_timestamp = _parse_iso_instant(last_snapshot_raw["snapshotAt"])

        warning_raw = last_snapshot_raw.get("dataQualityWarning")
        data_quality_value = warning_raw if warning_raw is not None else _INTRADAY_OK_LABEL
        quality_flags = [f"{_INTRADAY_FLAG_PREFIX}{data_quality_value}"]
        snapshots_out = mapped_snapshots

    freshness_seconds = int((now_fn() - as_of_timestamp).total_seconds())

    citation = Citation(
        tool="get_pnl_attribution",
        params={"book_id": book_id, "date": date, "period": period},
        result_field="total_pnl",
        result_value=total_pnl,
        result_currency=_RESULT_CURRENCY,
        as_of_timestamp=as_of_timestamp,
        data_source=_SERVICE,
        freshness_seconds=freshness_seconds,
        quality_flags=quality_flags,
    )

    return {
        "period": effective_period,
        "book_id": book_id,
        "date": echoed_date,
        "total_pnl": total_pnl,
        "components": components,
        "snapshots": snapshots_out,
        "data_quality": data_quality_value,
        "citation": citation,
    }

"""MCP tool ``get_vol_surface`` — volatility surface read with inversion detection.

Reads the latest (or historical) volatility surface for an underlier
from volatility-service and maps the upstream ``VolSurfaceResponse``
payload to the v2 tool-output shape defined in ``docs/plans/ai-v2.md`` § PR
2:

    {
        "underlier": str,
        "as_of_timestamp": str,
        "source": str,
        "tenors": [
            {
                "maturity_days": int,
                "atm_strike": float,
                "atm_vol": float,
                "point_count": int,
            },
            ...   # sorted by maturity_days ascending
        ],
        "points": [
            {"strike": float, "maturity_days": int, "implied_vol": float},
            ...   # the full raw surface, preserved
        ],
        "inversions": [
            {
                "short_maturity_days": int,
                "long_maturity_days": int,
                "short_atm_vol": float,
                "long_atm_vol": float,
                "diff_vol_points": float,
                "threshold_vol_points": float,
            },
            ...
        ],
        "citation": Citation,
    }

Two upstream endpoints are fanned out behind a single tool surface:

* **Latest** — ``GET /api/v1/volatility/{underlier}/surface/latest``
  returns a single ``VolSurfaceResponse``. Called when ``as_of`` is
  ``None``.
* **Historical** — ``GET /api/v1/volatility/{underlier}/surface/history``
  returns a JSON array of surfaces in a ``[from, to]`` window. Called
  when ``as_of`` is supplied; the tool synthesises a full-UTC-day
  window ``T00:00:00Z … T23:59:59Z`` and picks the LAST surface in the
  returned list (most recent within the day). An empty list is mapped
  to a synthetic ``NOT_FOUND`` — callers should map this into the
  citation error contract.

Inversion detection
-------------------

The plan calls for: *"detects inversions (short-dated ATM > long-dated
by >2 vol points)"*. The implementation works in three steps:

1. **Per-tenor ATM extraction.** Points are grouped by
   ``maturityDays``. For each tenor the ATM *strike* is the **median**
   of the tenor's strikes (robust to skew). The ATM *vol* is the
   ``impliedVol`` of the point whose strike is closest to the median.
   For an even number of strikes the lower of the two middle strikes
   is picked — deterministic and stable across runs.
2. **Pair walk.** Tenors are sorted by maturity ascending; every
   (short, long) pair is examined.
3. **Inversion test.** ``short.atm_vol - long.atm_vol >
   _INVERSION_THRESHOLD_VOL_POINTS`` -> inversion. Only inverted pairs
   are returned (not all pairs).

The threshold lives in ``_INVERSION_THRESHOLD_VOL_POINTS = 2.0`` and
the upstream ``impliedVol`` is assumed to be in **percentage points**
(e.g. ``15.0`` == 15%). This assumption is surfaced unconditionally in
the citation as ``VOL_UNIT_ASSUMPTION_PERCENT`` so consumers can audit
it. If upstream ever switches to decimal-fraction units the threshold
constant would need to change; the tool does NOT auto-detect.

ACL note
--------

``get_vol_surface`` is NOT book-scoped. Vol surfaces key on an
**underlier** (reference data), not a ``book_id``, and applying a
``user.books`` filter here would block legitimate lookups of any
underlier outside the caller's book scopes. The volatility-service is
free to enforce its own per-underlier ACL — the user context is still
forwarded so the downstream service receives ``X-User-Id`` /
``X-User-Books`` headers for audit. This is a deliberate departure
from prior PR 2 tools (e.g. ``get_book_var``, ``get_pnl_attribution``).

Citation
--------

The single :class:`Citation` returned describes a headline numeric:
``atm_vol_shortest_tenor`` — the ATM vol of the shortest-maturity
tenor in the response. Vol is dimensionless, so
``result_currency`` is ``None`` (NOT ``"USD"``).

Upstream ``KinetixHttpError`` (``NOT_FOUND``, ``UPSTREAM_ERROR``, ...)
is propagated unmodified so callers can map upstream failures into the
citation error contract uniformly.
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

_SERVICE: str = "volatility"
_DATA_SOURCE: str = "volatility-service"
_INVERSION_THRESHOLD_VOL_POINTS: float = 2.0

_VOL_UNIT_ASSUMPTION_FLAG: str = "VOL_UNIT_ASSUMPTION_PERCENT"
_TERM_STRUCTURE_INVERSION_FLAG: str = "TERM_STRUCTURE_INVERSION_DETECTED"


def _default_now() -> datetime:
    return datetime.now(timezone.utc)


def _parse_iso_instant(raw: str) -> datetime:
    """Parse an upstream ISO 8601 instant, normalising trailing ``Z``.

    ``asOfDate`` is serialised with a trailing ``Z``; ``fromisoformat``
    accepts ``+00:00`` natively, so we normalise.
    """

    normalised = raw.replace("Z", "+00:00") if raw.endswith("Z") else raw
    parsed = datetime.fromisoformat(normalised)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed


def _median_lower(values: list[float]) -> float:
    """Return the lower-middle median of a sorted list.

    For odd-length lists this is the standard median. For even-length
    lists, the LOWER of the two middle elements is returned — this
    keeps the ATM-strike pick deterministic across runs even when an
    even number of strikes is provided per tenor (see module docstring).
    """

    n = len(values)
    if n == 0:
        raise ValueError("cannot take median of empty list")
    sorted_values = sorted(values)
    # For n odd: index n//2 is the centre.
    # For n even: n//2 is the upper of the two middles; we want the
    # lower, so subtract 1.
    if n % 2 == 1:
        return sorted_values[n // 2]
    return sorted_values[n // 2 - 1]


def _extract_tenor(
    maturity_days: int, points: list[dict[str, Any]]
) -> dict[str, Any]:
    """Project a per-tenor list of points onto a single ATM-anchored row."""

    strikes = [float(p["strike"]) for p in points]
    atm_strike = _median_lower(strikes)
    # ATM vol is the impliedVol of the point whose strike equals the
    # median we just picked. Strikes within a tenor are unique on real
    # surfaces; if somehow not unique, ``next`` picks the first match
    # — still deterministic given the sorted input.
    atm_vol = float(
        next(p["impliedVol"] for p in points if float(p["strike"]) == atm_strike)
    )
    return {
        "maturity_days": maturity_days,
        "atm_strike": atm_strike,
        "atm_vol": atm_vol,
        "point_count": len(points),
    }


def _detect_inversions(tenors: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Walk every (short, long) tenor pair and flag inversions.

    A pair is an inversion when ``short.atm_vol - long.atm_vol``
    exceeds :data:`_INVERSION_THRESHOLD_VOL_POINTS`. Tenors must be
    pre-sorted by ``maturity_days`` ascending.
    """

    inversions: list[dict[str, Any]] = []
    for i, short in enumerate(tenors):
        for long in tenors[i + 1 :]:
            diff = short["atm_vol"] - long["atm_vol"]
            if diff > _INVERSION_THRESHOLD_VOL_POINTS:
                inversions.append(
                    {
                        "short_maturity_days": short["maturity_days"],
                        "long_maturity_days": long["maturity_days"],
                        "short_atm_vol": short["atm_vol"],
                        "long_atm_vol": long["atm_vol"],
                        "diff_vol_points": diff,
                        "threshold_vol_points": _INVERSION_THRESHOLD_VOL_POINTS,
                    }
                )
    return inversions


async def get_vol_surface(
    *,
    underlier: str,
    as_of: str | None = None,
    user: UserContext,
    http: KinetixHttpClient,
    now: Callable[[], datetime] | None = None,
) -> dict[str, Any]:
    """Return the volatility surface for ``underlier`` with provenance citation.

    Args:
        underlier: Instrument identifier (e.g. ``"EURUSD"``).
        as_of: Optional ISO date ``YYYY-MM-DD``. When ``None``, the
            latest cached surface is returned. When supplied, the
            historical endpoint is queried with a synthesised full-day
            ``[T00:00:00Z, T23:59:59Z]`` window and the last surface
            in the returned list is taken (most recent within the day).
        user: Caller identity and book scopes; forwarded to the HTTP
            client which stamps ``X-User-Id`` / ``X-User-Books``. Vol
            surfaces are NOT book-scoped — see module docstring.
        http: ``KinetixHttpClient`` to dispatch the upstream call.
        now: Injectable clock used to compute ``freshness_seconds``.
            Defaults to ``datetime.now(timezone.utc)``.

    Returns:
        A dict matching the module docstring shape with ``citation``
        covering the shortest-tenor ATM vol.

    Raises:
        KinetixHttpError: ``NOT_FOUND``/404 when no surface exists for
            the underlier (latest) or when the historical window is
            empty; ``UPSTREAM_ERROR``/502 on payload-shape drift;
            upstream errors propagate unmodified.
    """

    now_fn = now or _default_now

    if as_of is None:
        path = f"/api/v1/volatility/{underlier}/surface/latest"
        raw = await http.get(_SERVICE, path, params=None, user=user)
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
        surface: dict[str, Any] = raw
    else:
        path = f"/api/v1/volatility/{underlier}/surface/history"
        params = {
            "from": f"{as_of}T00:00:00Z",
            "to": f"{as_of}T23:59:59Z",
        }
        raw = await http.get(_SERVICE, path, params=params, user=user)
        if not isinstance(raw, list):
            raise KinetixHttpError(
                status_code=502,
                code="UPSTREAM_ERROR",
                message=(
                    f"expected array from {_SERVICE}{path}, "
                    f"got {type(raw).__name__}"
                ),
                service=_SERVICE,
                path=path,
            )
        if not raw:
            raise KinetixHttpError(
                status_code=404,
                code="NOT_FOUND",
                message=f"no vol surface for {underlier} on {as_of}",
                service=_SERVICE,
                path=path,
            )
        last = raw[-1]
        if not isinstance(last, dict):
            raise KinetixHttpError(
                status_code=502,
                code="UPSTREAM_ERROR",
                message=(
                    f"expected object in array from {_SERVICE}{path}, "
                    f"got {type(last).__name__}"
                ),
                service=_SERVICE,
                path=path,
            )
        surface = last

    as_of_raw: str = surface["asOfDate"]
    as_of_timestamp = _parse_iso_instant(as_of_raw)
    source: str = surface["source"]

    raw_points: list[dict[str, Any]] = list(surface.get("points") or [])
    points_out = [
        {
            "strike": float(p["strike"]),
            "maturity_days": int(p["maturityDays"]),
            "implied_vol": float(p["impliedVol"]),
        }
        for p in raw_points
    ]

    # Group raw points by maturity for per-tenor ATM extraction.
    by_maturity: dict[int, list[dict[str, Any]]] = {}
    for p in raw_points:
        by_maturity.setdefault(int(p["maturityDays"]), []).append(p)

    tenors = [
        _extract_tenor(maturity_days, by_maturity[maturity_days])
        for maturity_days in sorted(by_maturity)
    ]

    inversions = _detect_inversions(tenors)

    # Headline numeric for the citation: shortest-tenor ATM vol.
    headline_atm_vol = tenors[0]["atm_vol"]

    quality_flags: list[str] = [_VOL_UNIT_ASSUMPTION_FLAG]
    if inversions:
        quality_flags.append(_TERM_STRUCTURE_INVERSION_FLAG)

    freshness_seconds = int((now_fn() - as_of_timestamp).total_seconds())

    citation = Citation(
        tool="get_vol_surface",
        params={"underlier": underlier, "as_of": as_of},
        result_field="atm_vol_shortest_tenor",
        result_value=headline_atm_vol,
        result_currency=None,
        as_of_timestamp=as_of_timestamp,
        data_source=_DATA_SOURCE,
        freshness_seconds=freshness_seconds,
        quality_flags=quality_flags,
    )

    return {
        "underlier": underlier,
        "as_of_timestamp": as_of_raw,
        "source": source,
        "tenors": tenors,
        "points": points_out,
        "inversions": inversions,
        "citation": citation,
    }

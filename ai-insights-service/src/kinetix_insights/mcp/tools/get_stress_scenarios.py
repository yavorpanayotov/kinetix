"""MCP tool ``get_stress_scenarios`` — book-level named-scenario stress read.

Returns the worst-first stress P&L impact for a book against a list of
named scenarios, mapped onto the v2 tool-output shape defined in
``plans/ai-v2.md`` § PR 2:

    {
        "book_id": str,
        "scenarios": [
            {
                "name": str,
                "pnl_impact": float,
                "var_impact": float,         # stressed_var - base_var
                "base_var": float,
                "stressed_var": float,
                "key_driver": None,          # see v2 limitations below
            },
            ...   # sorted by ABS(pnl_impact) descending — worst first
        ],
        "worst_scenario_name": str | None,
        "worst_pnl_impact": float | None,
        "failed_scenarios": [
            {"name": str, "error_message": str},
            ...
        ],
        "citation": Citation,
    }

v2 limitations vs the plan spec
-------------------------------

1. **No precomputed-cache GET endpoint exists.** The plan calls for
   *"reads risk-orchestrator precomputed named-scenario cache"*, but
   risk-orchestrator does NOT expose such a GET. The closest available
   endpoint is the **batch compute** ``POST
   /api/v1/risk/stress/{bookId}/batch`` which calculates the stress on
   demand. The compute is idempotent and read-like (it does NOT mutate
   state) — it does not violate the "no write actions" guardrail (that
   rule concerns trade booking / hedge execution / limit adjustment,
   not stress simulation). The follow-up is to add a real read
   endpoint backed by a precomputed cache. To stay honest with the
   consumer, every citation carries the ``COMPUTED_NOT_CACHED`` quality
   flag.

2. **No key-driver attribution from upstream.** The upstream batch
   response carries ``scenarioName``, ``baseVar``, ``stressedVar``,
   ``pnlImpact`` only — no factor attribution. Every row therefore
   returns ``key_driver=None`` and every citation carries the
   ``KEY_DRIVER_UNAVAILABLE`` quality flag.

3. **Ad-hoc scenarios (custom shocks) are out of scope.** The tool
   accepts a ``scenarios?`` list-of-names argument but does NOT
   support vol/price overrides or arbitrary shock specs. The wire
   request only forwards scenario *names*. A separate tool will cover
   ad-hoc scenario specification.

Calculation defaults (always sent, not parameterised in v2)
-----------------------------------------------------------

* ``calculationType="PARAMETRIC"``
* ``confidenceLevel="CL_95"``
* ``timeHorizonDays="1"`` — upstream expects a **string** here (the
  Kotlin DTO carries ``String?`` and calls ``.toInt()``); we send the
  literal ``"1"``.

Default scenarios (when caller doesn't supply)
----------------------------------------------

``["GFC", "EUR-crisis", "Fed+25bps"]`` — matches the named examples in
the plan checkbox.

Citation contract
-----------------

The single :class:`Citation` returned anchors on ``worst_pnl_impact``.
The upstream batch response carries no timestamp so ``as_of_timestamp``
is pinned to ``now()`` and ``freshness_seconds`` is therefore ``0``
("data fetched now" semantics for a compute-on-demand endpoint). When
upstream returns ``worstPnlImpact: null`` (no successful scenarios),
``result_value`` falls back to ``0.0`` because :class:`Citation`
requires a non-null numeric anchor.

ACL / failure modes
-------------------

* Book-level ACL is enforced before any HTTP call: if ``book_id`` is
  not in :attr:`UserContext.books`, the tool raises
  ``KinetixHttpError(UNAUTHORIZED, 403)`` directly.
* Upstream ``KinetixHttpError`` (``NOT_FOUND``, ``UPSTREAM_ERROR``,
  ...) is propagated unmodified.
* A non-dict upstream payload raises ``UPSTREAM_ERROR``/502.
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

_DEFAULT_SCENARIOS: list[str] = ["GFC", "EUR-crisis", "Fed+25bps"]
_DEFAULT_CALCULATION_TYPE: str = "PARAMETRIC"
_DEFAULT_CONFIDENCE_LEVEL: str = "CL_95"
_DEFAULT_TIME_HORIZON_DAYS: str = "1"

_KEY_DRIVER_UNAVAILABLE_FLAG: str = "KEY_DRIVER_UNAVAILABLE"
_COMPUTED_NOT_CACHED_FLAG: str = "COMPUTED_NOT_CACHED"


def _default_now() -> datetime:
    return datetime.now(timezone.utc)


def _map_result_row(raw: dict[str, Any]) -> dict[str, Any]:
    """Project an upstream ``BatchScenarioResultDto`` onto the tool row.

    ``key_driver`` is unconditionally ``None`` because the upstream
    payload does not carry factor attribution — see module docstring.
    """

    base_var = float(raw["baseVar"])
    stressed_var = float(raw["stressedVar"])
    return {
        "name": raw["scenarioName"],
        "pnl_impact": float(raw["pnlImpact"]),
        "var_impact": stressed_var - base_var,
        "base_var": base_var,
        "stressed_var": stressed_var,
        "key_driver": None,
    }


def _map_failed_row(raw: dict[str, Any]) -> dict[str, Any]:
    return {
        "name": raw["scenarioName"],
        "error_message": raw["errorMessage"],
    }


async def get_stress_scenarios(
    *,
    book_id: str,
    scenarios: list[str] | None = None,
    user: UserContext,
    http: KinetixHttpClient,
    now: Callable[[], datetime] | None = None,
) -> dict[str, Any]:
    """Return worst-first named-scenario stress impacts for ``book_id``.

    Args:
        book_id: Portfolio identifier. Must be present in
            ``user.books`` or the call fails closed with
            ``UNAUTHORIZED``.
        scenarios: Optional explicit list of scenario *names*. When
            ``None`` defaults to ``["GFC", "EUR-crisis", "Fed+25bps"]``.
            Custom shock specifications (vol/price overrides) are NOT
            supported in v2 — only scenario names are forwarded.
        user: Caller identity and book scopes; forwarded to the HTTP
            client which stamps ``X-User-Id`` / ``X-User-Books``.
        http: ``KinetixHttpClient`` to dispatch the upstream call.
        now: Injectable clock used to stamp ``as_of_timestamp`` on the
            citation. Defaults to ``datetime.now(timezone.utc)``.

    Returns:
        A dict matching the module docstring shape with ``citation``
        covering ``worst_pnl_impact``.

    Raises:
        KinetixHttpError: ``UNAUTHORIZED``/403 when ``book_id`` is
            outside the caller's scope; ``UPSTREAM_ERROR``/502 when
            the upstream payload is not a JSON object; ``NOT_FOUND``,
            ``UPSTREAM_ERROR`` (and other coarse categories) when the
            upstream call itself fails. All propagated unmodified.
    """

    path = f"/api/v1/risk/stress/{book_id}/batch"

    if book_id not in user.books:
        raise KinetixHttpError(
            status_code=403,
            code="UNAUTHORIZED",
            message=f"book {book_id!r} not in user scope",
            service=_SERVICE,
            path=path,
        )

    effective_scenarios: list[str] = (
        list(scenarios) if scenarios is not None else list(_DEFAULT_SCENARIOS)
    )
    request_body: dict[str, Any] = {
        "scenarioNames": effective_scenarios,
        "calculationType": _DEFAULT_CALCULATION_TYPE,
        "confidenceLevel": _DEFAULT_CONFIDENCE_LEVEL,
        "timeHorizonDays": _DEFAULT_TIME_HORIZON_DAYS,
    }

    raw = await http.post(_SERVICE, path, json=request_body, user=user)
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

    raw_results: list[dict[str, Any]] = list(payload.get("results") or [])
    mapped_scenarios: list[dict[str, Any]] = [
        _map_result_row(row) for row in raw_results
    ]
    mapped_scenarios.sort(key=lambda row: abs(row["pnl_impact"]), reverse=True)

    raw_failures: list[dict[str, Any]] = list(payload.get("failedScenarios") or [])
    failed_scenarios = [_map_failed_row(row) for row in raw_failures]

    worst_scenario_name_raw = payload.get("worstScenarioName")
    worst_scenario_name: str | None = (
        worst_scenario_name_raw if worst_scenario_name_raw is not None else None
    )
    worst_pnl_impact_raw = payload.get("worstPnlImpact")
    worst_pnl_impact: float | None = (
        float(worst_pnl_impact_raw) if worst_pnl_impact_raw is not None else None
    )

    now_fn = now or _default_now
    as_of_timestamp = now_fn()

    citation = Citation(
        tool="get_stress_scenarios",
        params={"book_id": book_id, "scenarios": scenarios},
        result_field="worst_pnl_impact",
        result_value=worst_pnl_impact if worst_pnl_impact is not None else 0.0,
        result_currency=_RESULT_CURRENCY,
        as_of_timestamp=as_of_timestamp,
        data_source=_SERVICE,
        freshness_seconds=0,
        quality_flags=[
            _KEY_DRIVER_UNAVAILABLE_FLAG,
            _COMPUTED_NOT_CACHED_FLAG,
        ],
    )

    return {
        "book_id": book_id,
        "scenarios": mapped_scenarios,
        "worst_scenario_name": worst_scenario_name,
        "worst_pnl_impact": worst_pnl_impact,
        "failed_scenarios": failed_scenarios,
        "citation": citation,
    }

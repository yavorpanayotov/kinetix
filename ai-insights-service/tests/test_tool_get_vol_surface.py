"""Unit tests for the ``get_vol_surface`` MCP tool.

These tests stub the ``KinetixHttpClient`` with
:class:`tests.fakes.fake_kinetix_http_client.FakeKinetixHttpClient` and
assert that:

* the tool routes to the correct volatility-service endpoint — ``/latest``
  when no ``as_of`` is supplied, ``/history`` (with a synthesised
  ``from``/``to`` day window) when ``as_of`` is provided,
* the upstream ``VolSurfaceResponse`` is mapped to the v2 tool output
  shape defined in ``docs/plans/ai-v2.md`` § PR 2 — including a per-tenor
  ATM derivation by median strike,
* term-structure inversions (short-dated ATM > long-dated by more than
  2 vol points) are detected and surfaced both in the response and as
  a citation ``quality_flag``,
* the tool is NOT book-scoped: surfaces key on an underlier (reference
  data), so a caller without any matching ``UserContext.books`` may
  still query — the user context is forwarded for audit only,
* upstream ``NOT_FOUND`` and ``UPSTREAM_ERROR`` errors propagate
  unmodified.

No network, no FastMCP wiring — that lands in checkbox 2.11.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

import pytest

from kinetix_insights.clients.kinetix_http_client import KinetixHttpError
from kinetix_insights.clients.user_context import UserContext
from kinetix_insights.mcp.tools.get_vol_surface import get_vol_surface
from tests.fakes.fake_kinetix_http_client import FakeKinetixHttpClient

pytestmark = pytest.mark.unit


# ---------------------------------------------------------------------------
# Helpers and default fixtures (module-level constants — no ``...`` sentinel)
# ---------------------------------------------------------------------------


_DEFAULT_USER = UserContext(user_id="trader-1", books=("fx-main", "rates-emea"))


# Canonical surface — has an inverted term structure (30d ATM 14.0 vs
# 90d ATM 10.5 vs 365d ATM 9.8).
_CANONICAL_SURFACE: dict[str, Any] = {
    "instrumentId": "EURUSD",
    "asOfDate": "2026-05-19T08:00:00Z",
    "points": [
        {"strike": 1.05, "maturityDays": 30, "impliedVol": 15.0},
        {"strike": 1.08, "maturityDays": 30, "impliedVol": 14.5},
        {"strike": 1.10, "maturityDays": 30, "impliedVol": 14.0},
        {"strike": 1.12, "maturityDays": 30, "impliedVol": 14.6},
        {"strike": 1.15, "maturityDays": 30, "impliedVol": 15.2},
        {"strike": 1.08, "maturityDays": 90, "impliedVol": 11.0},
        {"strike": 1.10, "maturityDays": 90, "impliedVol": 10.5},
        {"strike": 1.12, "maturityDays": 90, "impliedVol": 11.2},
        {"strike": 1.10, "maturityDays": 365, "impliedVol": 9.8},
    ],
    "source": "MARKET",
    "lastUpdatedAt": "2026-05-19T08:00:00Z",
}


# Flat term structure — no inversions (all tenors ATM == 12.0).
_FLAT_SURFACE: dict[str, Any] = {
    "instrumentId": "EURUSD",
    "asOfDate": "2026-05-19T08:00:00Z",
    "points": [
        {"strike": 1.08, "maturityDays": 30, "impliedVol": 13.0},
        {"strike": 1.10, "maturityDays": 30, "impliedVol": 12.0},
        {"strike": 1.12, "maturityDays": 30, "impliedVol": 13.2},
        {"strike": 1.08, "maturityDays": 90, "impliedVol": 12.8},
        {"strike": 1.10, "maturityDays": 90, "impliedVol": 12.0},
        {"strike": 1.12, "maturityDays": 90, "impliedVol": 12.6},
    ],
    "source": "MARKET",
    "lastUpdatedAt": "2026-05-19T08:00:00Z",
}


# Single-tenor surface — exercises the degenerate inversion case.
_SINGLE_TENOR_SURFACE: dict[str, Any] = {
    "instrumentId": "EURUSD",
    "asOfDate": "2026-05-19T08:00:00Z",
    "points": [
        {"strike": 1.08, "maturityDays": 30, "impliedVol": 13.0},
        {"strike": 1.10, "maturityDays": 30, "impliedVol": 12.0},
        {"strike": 1.12, "maturityDays": 30, "impliedVol": 13.2},
    ],
    "source": "MARKET",
    "lastUpdatedAt": "2026-05-19T08:00:00Z",
}


# Even-count tenor — median is the lower of the two middle strikes for
# determinism. Strikes [1.05, 1.08, 1.10, 1.12] sorted -> middle pair
# is (1.08, 1.10); the tool picks 1.08.
_EVEN_TENOR_SURFACE: dict[str, Any] = {
    "instrumentId": "EURUSD",
    "asOfDate": "2026-05-19T08:00:00Z",
    "points": [
        {"strike": 1.05, "maturityDays": 30, "impliedVol": 15.0},
        {"strike": 1.08, "maturityDays": 30, "impliedVol": 13.5},
        {"strike": 1.10, "maturityDays": 30, "impliedVol": 14.0},
        {"strike": 1.12, "maturityDays": 30, "impliedVol": 14.8},
    ],
    "source": "MARKET",
    "lastUpdatedAt": "2026-05-19T08:00:00Z",
}


def _fixed_now() -> datetime:
    # 10 hours after the upstream ``asOfDate`` of 08:00:00Z ->
    # freshness_seconds == 36_000.
    return datetime(2026, 5, 19, 18, 0, 0, tzinfo=timezone.utc)


# ---------------------------------------------------------------------------
# Endpoint and parameter wiring
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_vol_surface_calls_latest_endpoint_when_no_as_of() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "volatility",
        "/api/v1/volatility/EURUSD/surface/latest",
        _CANONICAL_SURFACE,
    )

    await get_vol_surface(
        underlier="EURUSD",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert len(fake.recorded_calls) == 1
    call = fake.recorded_calls[0]
    assert call.method == "GET"
    assert call.service == "volatility"
    assert call.path == "/api/v1/volatility/EURUSD/surface/latest"
    assert call.params is None
    assert call.user == _DEFAULT_USER


@pytest.mark.asyncio
async def test_get_vol_surface_calls_history_endpoint_when_as_of_supplied() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "volatility",
        "/api/v1/volatility/EURUSD/surface/history",
        [_CANONICAL_SURFACE],
    )

    await get_vol_surface(
        underlier="EURUSD",
        as_of="2026-05-18",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    call = fake.recorded_calls[0]
    assert call.path == "/api/v1/volatility/EURUSD/surface/history"
    assert call.params == {
        "from": "2026-05-18T00:00:00Z",
        "to": "2026-05-18T23:59:59Z",
    }


@pytest.mark.asyncio
async def test_get_vol_surface_history_takes_last_surface_in_window() -> None:
    earlier_surface = {
        **_CANONICAL_SURFACE,
        "asOfDate": "2026-05-18T08:00:00Z",
        "lastUpdatedAt": "2026-05-18T08:00:00Z",
    }
    later_surface = {
        **_CANONICAL_SURFACE,
        "asOfDate": "2026-05-18T16:00:00Z",
        "lastUpdatedAt": "2026-05-18T16:00:00Z",
    }
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "volatility",
        "/api/v1/volatility/EURUSD/surface/history",
        [earlier_surface, later_surface],
    )

    result = await get_vol_surface(
        underlier="EURUSD",
        as_of="2026-05-18",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["as_of_timestamp"] == "2026-05-18T16:00:00Z"


@pytest.mark.asyncio
async def test_get_vol_surface_history_empty_list_raises_not_found() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "volatility",
        "/api/v1/volatility/EURUSD/surface/history",
        [],
    )

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_vol_surface(
            underlier="EURUSD",
            as_of="2026-05-18",
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "NOT_FOUND"
    assert excinfo.value.status_code == 404


# ---------------------------------------------------------------------------
# Response mapping
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_vol_surface_returns_points_and_underlier() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "volatility",
        "/api/v1/volatility/EURUSD/surface/latest",
        _CANONICAL_SURFACE,
    )

    result = await get_vol_surface(
        underlier="EURUSD",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["underlier"] == "EURUSD"
    assert result["source"] == "MARKET"
    assert result["as_of_timestamp"] == "2026-05-19T08:00:00Z"
    assert len(result["points"]) == len(_CANONICAL_SURFACE["points"])
    # Raw points are preserved on the v2 shape.
    assert result["points"][0] == {
        "strike": 1.05,
        "maturity_days": 30,
        "implied_vol": 15.0,
    }


@pytest.mark.asyncio
async def test_get_vol_surface_groups_tenors_with_median_atm() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "volatility",
        "/api/v1/volatility/EURUSD/surface/latest",
        _CANONICAL_SURFACE,
    )

    result = await get_vol_surface(
        underlier="EURUSD",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    tenors_by_days = {row["maturity_days"]: row for row in result["tenors"]}

    assert tenors_by_days[30]["atm_strike"] == 1.10
    assert tenors_by_days[30]["atm_vol"] == 14.0
    assert tenors_by_days[30]["point_count"] == 5

    assert tenors_by_days[90]["atm_strike"] == 1.10
    assert tenors_by_days[90]["atm_vol"] == 10.5
    assert tenors_by_days[90]["point_count"] == 3

    assert tenors_by_days[365]["atm_strike"] == 1.10
    assert tenors_by_days[365]["atm_vol"] == 9.8
    assert tenors_by_days[365]["point_count"] == 1


@pytest.mark.asyncio
async def test_get_vol_surface_tenors_sorted_by_maturity_ascending() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "volatility",
        "/api/v1/volatility/EURUSD/surface/latest",
        _CANONICAL_SURFACE,
    )

    result = await get_vol_surface(
        underlier="EURUSD",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    maturities = [row["maturity_days"] for row in result["tenors"]]
    assert maturities == sorted(maturities)
    assert maturities == [30, 90, 365]


# ---------------------------------------------------------------------------
# Inversion detection
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_vol_surface_detects_inversion_when_short_atm_exceeds_long_by_threshold() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "volatility",
        "/api/v1/volatility/EURUSD/surface/latest",
        _CANONICAL_SURFACE,
    )

    result = await get_vol_surface(
        underlier="EURUSD",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    inversions = result["inversions"]
    assert len(inversions) == 2

    pairs = {(row["short_maturity_days"], row["long_maturity_days"]) for row in inversions}
    assert pairs == {(30, 90), (30, 365)}


@pytest.mark.asyncio
async def test_get_vol_surface_inversion_records_diff_and_threshold() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "volatility",
        "/api/v1/volatility/EURUSD/surface/latest",
        _CANONICAL_SURFACE,
    )

    result = await get_vol_surface(
        underlier="EURUSD",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    by_pair = {
        (row["short_maturity_days"], row["long_maturity_days"]): row
        for row in result["inversions"]
    }

    inv_30_90 = by_pair[(30, 90)]
    assert inv_30_90["short_atm_vol"] == 14.0
    assert inv_30_90["long_atm_vol"] == 10.5
    assert inv_30_90["diff_vol_points"] == pytest.approx(3.5)
    assert inv_30_90["threshold_vol_points"] == 2.0

    inv_30_365 = by_pair[(30, 365)]
    assert inv_30_365["short_atm_vol"] == 14.0
    assert inv_30_365["long_atm_vol"] == 9.8
    assert inv_30_365["diff_vol_points"] == pytest.approx(4.2)
    assert inv_30_365["threshold_vol_points"] == 2.0


@pytest.mark.asyncio
async def test_get_vol_surface_no_inversions_returns_empty_list_and_no_flag() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "volatility",
        "/api/v1/volatility/EURUSD/surface/latest",
        _FLAT_SURFACE,
    )

    result = await get_vol_surface(
        underlier="EURUSD",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["inversions"] == []
    assert "TERM_STRUCTURE_INVERSION_DETECTED" not in result["citation"].quality_flags


@pytest.mark.asyncio
async def test_get_vol_surface_inversion_present_adds_quality_flag() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "volatility",
        "/api/v1/volatility/EURUSD/surface/latest",
        _CANONICAL_SURFACE,
    )

    result = await get_vol_surface(
        underlier="EURUSD",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert "TERM_STRUCTURE_INVERSION_DETECTED" in result["citation"].quality_flags


# ---------------------------------------------------------------------------
# Citation
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_vol_surface_citation_always_flags_vol_unit_assumption() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "volatility",
        "/api/v1/volatility/EURUSD/surface/latest",
        _FLAT_SURFACE,
    )

    result = await get_vol_surface(
        underlier="EURUSD",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert "VOL_UNIT_ASSUMPTION_PERCENT" in result["citation"].quality_flags


@pytest.mark.asyncio
async def test_get_vol_surface_citation_result_value_is_shortest_tenor_atm() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "volatility",
        "/api/v1/volatility/EURUSD/surface/latest",
        _CANONICAL_SURFACE,
    )

    result = await get_vol_surface(
        underlier="EURUSD",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    citation = result["citation"]
    assert citation.result_field == "atm_vol_shortest_tenor"
    assert citation.result_value == 14.0


@pytest.mark.asyncio
async def test_get_vol_surface_citation_records_call_params() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "volatility",
        "/api/v1/volatility/EURUSD/surface/latest",
        _CANONICAL_SURFACE,
    )

    result = await get_vol_surface(
        underlier="EURUSD",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    # ``None`` values are preserved, not stripped — matches 2.1/2.2/2.3.
    assert result["citation"].params == {
        "underlier": "EURUSD",
        "as_of": None,
    }
    assert result["citation"].tool == "get_vol_surface"
    assert result["citation"].data_source == "volatility-service"
    # Upstream asOfDate is 08:00:00Z, _fixed_now() is 18:00:00Z -> 10h.
    assert result["citation"].freshness_seconds == 10 * 3600
    assert result["citation"].as_of_timestamp == datetime(
        2026, 5, 19, 8, 0, 0, tzinfo=timezone.utc
    )


@pytest.mark.asyncio
async def test_get_vol_surface_citation_result_currency_is_none() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "volatility",
        "/api/v1/volatility/EURUSD/surface/latest",
        _CANONICAL_SURFACE,
    )

    result = await get_vol_surface(
        underlier="EURUSD",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["citation"].result_currency is None


# ---------------------------------------------------------------------------
# Upstream error propagation
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_vol_surface_propagates_not_found() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "volatility",
        "/api/v1/volatility/EURUSD/surface/latest",
        KinetixHttpError(
            status_code=404,
            code="NOT_FOUND",
            message="no surface for underlier",
            service="volatility",
            path="/api/v1/volatility/EURUSD/surface/latest",
        ),
    )

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_vol_surface(
            underlier="EURUSD",
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "NOT_FOUND"
    assert excinfo.value.status_code == 404


@pytest.mark.asyncio
async def test_get_vol_surface_propagates_upstream_error() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "volatility",
        "/api/v1/volatility/EURUSD/surface/latest",
        KinetixHttpError(
            status_code=502,
            code="UPSTREAM_ERROR",
            message="volatility service unreachable",
            service="volatility",
            path="/api/v1/volatility/EURUSD/surface/latest",
        ),
    )

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_vol_surface(
            underlier="EURUSD",
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "UPSTREAM_ERROR"
    assert excinfo.value.status_code == 502


# ---------------------------------------------------------------------------
# ACL — this tool is NOT book-scoped
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_vol_surface_does_not_book_scope() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "volatility",
        "/api/v1/volatility/EURUSD/surface/latest",
        _CANONICAL_SURFACE,
    )

    # User has no matching book scope — vol surfaces are reference data,
    # not book-scoped, so the call must still go through. User context is
    # forwarded so the downstream service can run its own audit.
    user = UserContext(user_id="trader-1", books=("rates-only",))

    await get_vol_surface(
        underlier="EURUSD",
        user=user,
        http=fake,
        now=_fixed_now,
    )

    assert len(fake.recorded_calls) == 1
    assert fake.recorded_calls[0].user == user


# ---------------------------------------------------------------------------
# Degenerate shapes
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_vol_surface_handles_single_tenor() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "volatility",
        "/api/v1/volatility/EURUSD/surface/latest",
        _SINGLE_TENOR_SURFACE,
    )

    result = await get_vol_surface(
        underlier="EURUSD",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert len(result["tenors"]) == 1
    assert result["tenors"][0]["maturity_days"] == 30
    assert result["tenors"][0]["atm_strike"] == 1.10
    assert result["tenors"][0]["atm_vol"] == 12.0
    assert result["inversions"] == []
    # Citation works — headline value is the only tenor's ATM vol.
    assert result["citation"].result_value == 12.0


@pytest.mark.asyncio
async def test_get_vol_surface_handles_even_number_of_strikes() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "volatility",
        "/api/v1/volatility/EURUSD/surface/latest",
        _EVEN_TENOR_SURFACE,
    )

    result = await get_vol_surface(
        underlier="EURUSD",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    # Strikes sorted = [1.05, 1.08, 1.10, 1.12]; even count -> the LOWER
    # of the two middle strikes (1.08) is the deterministic ATM choice.
    assert len(result["tenors"]) == 1
    assert result["tenors"][0]["atm_strike"] == 1.08
    assert result["tenors"][0]["atm_vol"] == 13.5

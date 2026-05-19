"""Unit tests for the ``get_greeks_summary`` MCP tool.

These tests stub the ``KinetixHttpClient`` with
:class:`tests.fakes.fake_kinetix_http_client.FakeKinetixHttpClient` and
assert that:

* the tool routes to the correct risk-orchestrator endpoint, with
  ``as_of`` forwarded as the ``valuationDate`` query parameter and
  ``underlier`` filtered client-side,
* the upstream ``VaRResultResponse.greeks`` is aggregated to the
  tool's ``aggregate`` shape, and ``positionGreeks`` are bucketed by
  a derived underlier (substring before the first ``_``),
* the citation for ``aggregate_delta`` is populated with the right
  provenance fields and always carries the v2 gap quality flags
  (``SOD_GREEKS_UNAVAILABLE`` and
  ``BY_UNDERLIER_DERIVED_FROM_INSTRUMENT_ID``),
* a missing ``greeks`` field raises ``NOT_FOUND``,
* book-level ACL fails closed before the HTTP client is ever touched,
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
from kinetix_insights.mcp.tools.get_greeks_summary import get_greeks_summary
from tests.fakes.fake_kinetix_http_client import FakeKinetixHttpClient

pytestmark = pytest.mark.unit


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


_DEFAULT_USER = UserContext(user_id="trader-1", books=("fx-main", "rates-emea"))

_DEFAULT_GREEKS: dict[str, Any] = {
    "bookId": "fx-main",
    "assetClassGreeks": [
        {
            "assetClass": "FX",
            "delta": "1234.567890",
            "gamma": "12.345678",
            "vega": "45.678901",
        },
        {
            "assetClass": "RATES",
            "delta": "100.000000",
            "gamma": "0.000000",
            "vega": "0.000000",
        },
    ],
    "theta": "-50.123456",
    "rho": "120.654321",
    "calculatedAt": "2026-05-19T08:00:00Z",
}

_DEFAULT_POSITION_GREEKS: list[dict[str, Any]] = [
    {
        "instrumentId": "EURUSD_CALL_20260601_1.09",
        "delta": "500.0",
        "gamma": "5.0",
        "vega": "20.0",
        "theta": "-2.5",
        "rho": "10.0",
    },
    {
        "instrumentId": "EURUSD_PUT_20260601_1.08",
        "delta": "-200.0",
        "gamma": "3.0",
        "vega": "15.0",
        "theta": "-1.0",
        "rho": "5.0",
    },
    {
        "instrumentId": "GBPUSD_CALL_20260701_1.30",
        "delta": "300.0",
        "gamma": "2.0",
        "vega": "10.0",
        "theta": "-1.5",
        "rho": "8.0",
    },
    {
        "instrumentId": "EURUSD",
        "delta": "634.567890",
        "gamma": "4.345678",
        "vega": "0.678901",
        "theta": "-45.6",
        "rho": "97.6",
    },
]


def _sample_greeks_response(
    *,
    stale: bool | None = None,
    position_greeks: list[dict[str, Any]] | None = _DEFAULT_POSITION_GREEKS,
    greeks: dict[str, Any] | None = _DEFAULT_GREEKS,
) -> dict[str, Any]:
    """A representative upstream ``VaRResultResponse`` payload with Greeks.

    ``position_greeks`` and ``greeks`` default to canonical samples;
    pass ``None`` to set the field to ``null`` explicitly.
    """

    payload: dict[str, Any] = {
        "bookId": "fx-main",
        "calculationType": "PARAMETRIC",
        "confidenceLevel": "CL_95",
        "varValue": "1234567.89",
        "expectedShortfall": "1500000.00",
        "componentBreakdown": [],
        "calculatedAt": "2026-05-19T08:00:00Z",
        "marketDataComplete": True,
        "greeks": greeks,
        "positionGreeks": position_greeks,
    }
    if stale is not None:
        payload["stale"] = stale
    return payload


def _fixed_now() -> datetime:
    # 8 hours after the sample ``calculatedAt`` -> freshness_seconds == 28_800.
    return datetime(2026, 5, 19, 16, 0, 0, tzinfo=timezone.utc)


# ---------------------------------------------------------------------------
# Endpoint and parameter wiring
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_greeks_summary_calls_correct_endpoint_with_no_as_of() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/var/fx-main",
        _sample_greeks_response(),
    )

    await get_greeks_summary(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert len(fake.recorded_calls) == 1
    call = fake.recorded_calls[0]
    assert call.method == "GET"
    assert call.service == "risk-orchestrator"
    assert call.path == "/api/v1/risk/var/fx-main"
    assert call.params is None
    assert call.user == _DEFAULT_USER


@pytest.mark.asyncio
async def test_get_greeks_summary_forwards_as_of_as_valuation_date() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/var/fx-main",
        _sample_greeks_response(),
    )

    await get_greeks_summary(
        book_id="fx-main",
        as_of="2026-05-18",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    call = fake.recorded_calls[0]
    assert call.params == {"valuationDate": "2026-05-18"}


# ---------------------------------------------------------------------------
# Response mapping — aggregate
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_greeks_summary_aggregates_asset_class_greeks() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/var/fx-main",
        _sample_greeks_response(),
    )

    result = await get_greeks_summary(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    aggregate = result["aggregate"]
    assert aggregate["delta"] == pytest.approx(1234.567890 + 100.000000)
    assert aggregate["gamma"] == pytest.approx(12.345678 + 0.000000)
    assert aggregate["vega"] == pytest.approx(45.678901 + 0.000000)
    assert aggregate["theta"] == pytest.approx(-50.123456)
    assert aggregate["rho"] == pytest.approx(120.654321)


# ---------------------------------------------------------------------------
# Response mapping — by_underlier
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_greeks_summary_derives_underlier_from_instrument_id() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/var/fx-main",
        _sample_greeks_response(),
    )

    result = await get_greeks_summary(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    by_underlier = result["by_underlier"]
    underliers = {row["underlier"] for row in by_underlier}
    assert underliers == {"EURUSD", "GBPUSD"}

    eurusd = next(row for row in by_underlier if row["underlier"] == "EURUSD")
    assert eurusd["delta"] == pytest.approx(500.0 + -200.0 + 634.567890)
    assert eurusd["gamma"] == pytest.approx(5.0 + 3.0 + 4.345678)
    assert eurusd["vega"] == pytest.approx(20.0 + 15.0 + 0.678901)
    assert eurusd["theta"] == pytest.approx(-2.5 + -1.0 + -45.6)
    assert eurusd["rho"] == pytest.approx(10.0 + 5.0 + 97.6)

    gbpusd = next(row for row in by_underlier if row["underlier"] == "GBPUSD")
    assert gbpusd["delta"] == pytest.approx(300.0)
    assert gbpusd["gamma"] == pytest.approx(2.0)
    assert gbpusd["vega"] == pytest.approx(10.0)
    assert gbpusd["theta"] == pytest.approx(-1.5)
    assert gbpusd["rho"] == pytest.approx(8.0)


@pytest.mark.asyncio
async def test_get_greeks_summary_sorts_by_abs_delta_desc() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/var/fx-main",
        _sample_greeks_response(),
    )

    result = await get_greeks_summary(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    underliers_in_order = [row["underlier"] for row in result["by_underlier"]]
    # EURUSD aggregate delta |934.5..| > GBPUSD |300|.
    assert underliers_in_order == ["EURUSD", "GBPUSD"]


@pytest.mark.asyncio
async def test_get_greeks_summary_filters_by_underlier() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/var/fx-main",
        _sample_greeks_response(),
    )

    result = await get_greeks_summary(
        book_id="fx-main",
        underlier="GBPUSD",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert len(result["by_underlier"]) == 1
    only = result["by_underlier"][0]
    assert only["underlier"] == "GBPUSD"
    assert only["delta"] == pytest.approx(300.0)


@pytest.mark.asyncio
async def test_get_greeks_summary_returns_empty_by_underlier_when_no_position_greeks() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/var/fx-main",
        _sample_greeks_response(position_greeks=None),
    )

    result = await get_greeks_summary(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    # Aggregate still populated from greeks.
    assert result["aggregate"]["delta"] == pytest.approx(1334.567890)
    assert result["by_underlier"] == []


# ---------------------------------------------------------------------------
# Citation
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_greeks_summary_returns_citation_for_aggregate_delta() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/var/fx-main",
        _sample_greeks_response(),
    )

    result = await get_greeks_summary(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    citation = result["citation"]
    assert citation.tool == "get_greeks_summary"
    assert citation.result_field == "aggregate_delta"
    assert citation.result_value == result["aggregate"]["delta"]
    assert citation.result_currency == "USD"
    assert citation.data_source == "risk-orchestrator"
    assert citation.as_of_timestamp == datetime(
        2026, 5, 19, 8, 0, 0, tzinfo=timezone.utc
    )
    assert citation.freshness_seconds == 8 * 3600


@pytest.mark.asyncio
async def test_get_greeks_summary_citation_always_flags_sod_unavailable_and_by_underlier_derived() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/var/fx-main",
        _sample_greeks_response(),
    )

    result = await get_greeks_summary(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    flags = result["citation"].quality_flags
    assert "SOD_GREEKS_UNAVAILABLE" in flags
    assert "BY_UNDERLIER_DERIVED_FROM_INSTRUMENT_ID" in flags


@pytest.mark.asyncio
async def test_get_greeks_summary_citation_records_call_params() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/var/fx-main",
        _sample_greeks_response(),
    )

    result = await get_greeks_summary(
        book_id="fx-main",
        as_of="2026-05-18",
        underlier="EURUSD",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["citation"].params == {
        "book_id": "fx-main",
        "as_of": "2026-05-18",
        "underlier": "EURUSD",
    }


@pytest.mark.asyncio
async def test_get_greeks_summary_flags_stale_when_upstream_marks_stale() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/var/fx-main",
        _sample_greeks_response(stale=True),
    )

    result = await get_greeks_summary(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    flags = result["citation"].quality_flags
    assert "STALE" in flags
    assert "SOD_GREEKS_UNAVAILABLE" in flags
    assert "BY_UNDERLIER_DERIVED_FROM_INSTRUMENT_ID" in flags


# ---------------------------------------------------------------------------
# Missing-greeks NOT_FOUND
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_greeks_summary_raises_not_found_when_greeks_missing() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/var/fx-main",
        _sample_greeks_response(greeks=None),
    )

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_greeks_summary(
            book_id="fx-main",
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "NOT_FOUND"
    assert excinfo.value.status_code == 404


# ---------------------------------------------------------------------------
# ACL / fails closed
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_greeks_summary_fails_closed_when_book_not_in_user_scope() -> None:
    fake = FakeKinetixHttpClient()
    user = UserContext(user_id="t1", books=("rates-only",))

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_greeks_summary(
            book_id="fx-main",
            user=user,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "UNAUTHORIZED"
    assert excinfo.value.status_code == 403
    assert fake.recorded_calls == []


# ---------------------------------------------------------------------------
# Upstream error propagation
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_greeks_summary_propagates_not_found_from_upstream() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/var/fx-main",
        KinetixHttpError(
            status_code=404,
            code="NOT_FOUND",
            message="no risk snapshot",
            service="risk-orchestrator",
            path="/api/v1/risk/var/fx-main",
        ),
    )

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_greeks_summary(
            book_id="fx-main",
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "NOT_FOUND"
    assert excinfo.value.status_code == 404


@pytest.mark.asyncio
async def test_get_greeks_summary_propagates_upstream_error() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/var/fx-main",
        KinetixHttpError(
            status_code=502,
            code="UPSTREAM_ERROR",
            message="risk engine unreachable",
            service="risk-orchestrator",
            path="/api/v1/risk/var/fx-main",
        ),
    )

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_greeks_summary(
            book_id="fx-main",
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "UPSTREAM_ERROR"
    assert excinfo.value.status_code == 502

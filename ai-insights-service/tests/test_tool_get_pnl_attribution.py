"""Unit tests for the ``get_pnl_attribution`` MCP tool.

These tests stub the ``KinetixHttpClient`` with
:class:`tests.fakes.fake_kinetix_http_client.FakeKinetixHttpClient` and
assert that:

* the tool routes to the correct risk-orchestrator endpoints — daily
  uses ``GET /api/v1/risk/pnl-attribution/{bookId}`` (optionally with a
  ``date`` query parameter), intraday uses
  ``GET /api/v1/risk/pnl/intraday/{bookId}`` with synthesised
  ``from`` / ``to`` ISO instants bounding the requested day,
* the upstream daily ``PnlAttributionResponse`` and the intraday
  ``IntradayPnlSeriesResponse`` payloads are mapped to the v2 tool
  output shape defined in ``docs/plans/ai-v2.md`` § PR 2,
* the upstream ``dataQualityFlag`` (daily) and ``dataQualityWarning``
  (intraday, latest snapshot) end up in the citation ``quality_flags``
  — not buried in the response body — per the plan's "surfaces
  dataQualityFlag in citation quality_flags" requirement,
* ``period`` validation fails closed with ``BAD_REQUEST`` before the
  HTTP client is ever touched,
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
from kinetix_insights.mcp.tools.get_pnl_attribution import (
    get_pnl_attribution,
)
from tests.fakes.fake_kinetix_http_client import FakeKinetixHttpClient

pytestmark = pytest.mark.unit


# ---------------------------------------------------------------------------
# Helpers and default fixtures (module-level constants — no ``...`` sentinel)
# ---------------------------------------------------------------------------


_DEFAULT_USER = UserContext(user_id="trader-1", books=("fx-main", "rates-emea"))


_DAILY_RESPONSE: dict[str, Any] = {
    "bookId": "fx-main",
    "date": "2026-05-19",
    "totalPnl": "12345.67",
    "deltaPnl": "10000.00",
    "gammaPnl": "500.00",
    "vegaPnl": "1200.00",
    "thetaPnl": "-100.00",
    "rhoPnl": "50.00",
    "vannaPnl": "20.00",
    "volgaPnl": "15.00",
    "charmPnl": "-25.00",
    "crossGammaPnl": "10.00",
    "unexplainedPnl": "675.67",
    "positionAttributions": [],
    "dataQualityFlag": "FULL_ATTRIBUTION",
    "calculatedAt": "2026-05-19T08:00:00Z",
}


_DAILY_STALE_GREEKS_RESPONSE: dict[str, Any] = {
    **_DAILY_RESPONSE,
    "dataQualityFlag": "STALE_GREEKS",
}


_INTRADAY_SNAPSHOT_09: dict[str, Any] = {
    "snapshotAt": "2026-05-19T09:00:00Z",
    "baseCurrency": "USD",
    "trigger": "scheduled",
    "totalPnl": "1000.00",
    "realisedPnl": "200.00",
    "unrealisedPnl": "800.00",
    "deltaPnl": "750.00",
    "gammaPnl": "50.00",
    "vegaPnl": "100.00",
    "thetaPnl": "-20.00",
    "rhoPnl": "10.00",
    "vannaPnl": "5.00",
    "volgaPnl": "3.00",
    "charmPnl": "-2.00",
    "crossGammaPnl": "4.00",
    "unexplainedPnl": "100.00",
    "unexplainedPct": 0.1,
    "pnlVsSod": "1000.00",
    "highWaterMark": "1050.00",
    "instrumentPnl": [],
    "correlationId": "corr-09",
    "dataQualityWarning": None,
}


_INTRADAY_SNAPSHOT_12: dict[str, Any] = {
    "snapshotAt": "2026-05-19T12:00:00Z",
    "baseCurrency": "USD",
    "trigger": "scheduled",
    "totalPnl": "2500.50",
    "realisedPnl": "500.00",
    "unrealisedPnl": "2000.50",
    "deltaPnl": "1800.00",
    "gammaPnl": "120.00",
    "vegaPnl": "300.50",
    "thetaPnl": "-40.00",
    "rhoPnl": "20.00",
    "vannaPnl": "8.00",
    "volgaPnl": "6.00",
    "charmPnl": "-4.00",
    "crossGammaPnl": "10.00",
    "unexplainedPnl": "280.00",
    "unexplainedPct": 0.11,
    "pnlVsSod": "2500.50",
    "highWaterMark": "2600.00",
    "instrumentPnl": [],
    "correlationId": "corr-12",
    "dataQualityWarning": None,
}


_INTRADAY_RESPONSE: dict[str, Any] = {
    "bookId": "fx-main",
    "snapshots": [_INTRADAY_SNAPSHOT_09, _INTRADAY_SNAPSHOT_12],
}


def _intraday_with_warning(warning: str | None) -> dict[str, Any]:
    last_snapshot = {**_INTRADAY_SNAPSHOT_12, "dataQualityWarning": warning}
    return {
        "bookId": "fx-main",
        "snapshots": [_INTRADAY_SNAPSHOT_09, last_snapshot],
    }


def _fixed_now() -> datetime:
    # 10 hours after the daily ``calculatedAt`` of 08:00:00Z, i.e.
    # daily freshness_seconds == 36_000. For intraday the last snapshot
    # is 12:00:00Z, so intraday freshness_seconds == 6 * 3600 == 21_600.
    return datetime(2026, 5, 19, 18, 0, 0, tzinfo=timezone.utc)


# ---------------------------------------------------------------------------
# Daily — endpoint and parameter wiring
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_daily_calls_correct_endpoint_with_no_date() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/pnl-attribution/fx-main",
        _DAILY_RESPONSE,
    )

    await get_pnl_attribution(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert len(fake.recorded_calls) == 1
    call = fake.recorded_calls[0]
    assert call.method == "GET"
    assert call.service == "risk-orchestrator"
    assert call.path == "/api/v1/risk/pnl-attribution/fx-main"
    assert call.params is None
    assert call.user == _DEFAULT_USER


@pytest.mark.asyncio
async def test_daily_forwards_date_as_query() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/pnl-attribution/fx-main",
        _DAILY_RESPONSE,
    )

    await get_pnl_attribution(
        book_id="fx-main",
        date="2026-05-18",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    call = fake.recorded_calls[0]
    assert call.params == {"date": "2026-05-18"}


@pytest.mark.asyncio
async def test_daily_period_explicit_uses_daily_endpoint() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/pnl-attribution/fx-main",
        _DAILY_RESPONSE,
    )

    await get_pnl_attribution(
        book_id="fx-main",
        period="daily",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    call = fake.recorded_calls[0]
    assert call.method == "GET"
    assert call.service == "risk-orchestrator"
    assert call.path == "/api/v1/risk/pnl-attribution/fx-main"
    assert call.params is None


# ---------------------------------------------------------------------------
# Daily — response mapping
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_daily_parses_components_and_total() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/pnl-attribution/fx-main",
        _DAILY_RESPONSE,
    )

    result = await get_pnl_attribution(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["period"] == "daily"
    assert result["book_id"] == "fx-main"
    assert result["date"] == "2026-05-19"
    assert result["snapshots"] is None
    assert result["total_pnl"] == 12345.67
    assert result["components"] == {
        "delta_pnl": 10000.00,
        "gamma_pnl": 500.00,
        "vega_pnl": 1200.00,
        "theta_pnl": -100.00,
        "rho_pnl": 50.00,
        "vanna_pnl": 20.00,
        "volga_pnl": 15.00,
        "charm_pnl": -25.00,
        "cross_gamma_pnl": 10.00,
        "unexplained_pnl": 675.67,
    }


@pytest.mark.asyncio
async def test_daily_surfaces_data_quality_flag() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/pnl-attribution/fx-main",
        _DAILY_RESPONSE,
    )

    result = await get_pnl_attribution(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["data_quality"] == "FULL_ATTRIBUTION"
    assert "FULL_ATTRIBUTION" in result["citation"].quality_flags


@pytest.mark.asyncio
async def test_daily_stale_greeks_also_adds_stale_flag() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/pnl-attribution/fx-main",
        _DAILY_STALE_GREEKS_RESPONSE,
    )

    result = await get_pnl_attribution(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    flags = result["citation"].quality_flags
    assert "STALE_GREEKS" in flags
    assert "STALE" in flags


# ---------------------------------------------------------------------------
# Intraday — endpoint and parameter wiring
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_intraday_calls_correct_endpoint_with_from_and_to_for_today() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/pnl/intraday/fx-main",
        _INTRADAY_RESPONSE,
    )

    await get_pnl_attribution(
        book_id="fx-main",
        period="intraday",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert len(fake.recorded_calls) == 1
    call = fake.recorded_calls[0]
    assert call.method == "GET"
    assert call.service == "risk-orchestrator"
    assert call.path == "/api/v1/risk/pnl/intraday/fx-main"
    # Day in injected ``now()`` is 2026-05-19; the tool synthesises a
    # full-day window bounded by midnight start / 23:59:59 end UTC.
    assert call.params == {
        "from": "2026-05-19T00:00:00Z",
        "to": "2026-05-19T23:59:59Z",
    }


@pytest.mark.asyncio
async def test_intraday_forwards_date_when_supplied() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/pnl/intraday/fx-main",
        _INTRADAY_RESPONSE,
    )

    await get_pnl_attribution(
        book_id="fx-main",
        date="2026-05-18",
        period="intraday",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    call = fake.recorded_calls[0]
    assert call.params == {
        "from": "2026-05-18T00:00:00Z",
        "to": "2026-05-18T23:59:59Z",
    }


# ---------------------------------------------------------------------------
# Intraday — response mapping
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_intraday_returns_snapshots_and_uses_last_total_pnl() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/pnl/intraday/fx-main",
        _INTRADAY_RESPONSE,
    )

    result = await get_pnl_attribution(
        book_id="fx-main",
        period="intraday",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["period"] == "intraday"
    assert result["book_id"] == "fx-main"
    # No date supplied — derived from now() UTC.
    assert result["date"] == "2026-05-19"
    # ``total_pnl`` reflects the LAST snapshot's totalPnl.
    assert result["total_pnl"] == 2500.50
    snapshots = result["snapshots"]
    assert snapshots is not None
    assert len(snapshots) == 2
    assert snapshots[0] == {
        "snapshot_at": "2026-05-19T09:00:00Z",
        "total_pnl": 1000.00,
        "delta_pnl": 750.00,
        "gamma_pnl": 50.00,
        "vega_pnl": 100.00,
        "theta_pnl": -20.00,
        "rho_pnl": 10.00,
        "vanna_pnl": 5.00,
        "volga_pnl": 3.00,
        "charm_pnl": -2.00,
        "cross_gamma_pnl": 4.00,
        "unexplained_pnl": 100.00,
    }
    assert snapshots[1] == {
        "snapshot_at": "2026-05-19T12:00:00Z",
        "total_pnl": 2500.50,
        "delta_pnl": 1800.00,
        "gamma_pnl": 120.00,
        "vega_pnl": 300.50,
        "theta_pnl": -40.00,
        "rho_pnl": 20.00,
        "vanna_pnl": 8.00,
        "volga_pnl": 6.00,
        "charm_pnl": -4.00,
        "cross_gamma_pnl": 10.00,
        "unexplained_pnl": 280.00,
    }
    # Per-snapshot mapping skips instrumentPnl / correlationId / warning.
    assert "instrument_pnl" not in snapshots[0]
    assert "correlation_id" not in snapshots[0]
    assert "data_quality_warning" not in snapshots[0]


@pytest.mark.asyncio
async def test_intraday_data_quality_uses_warning_field() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/pnl/intraday/fx-main",
        _intraday_with_warning("stale_prices"),
    )

    result = await get_pnl_attribution(
        book_id="fx-main",
        period="intraday",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["data_quality"] == "stale_prices"
    assert "INTRADAY:stale_prices" in result["citation"].quality_flags

    # Null warning — surfaces "OK".
    fake_ok = FakeKinetixHttpClient()
    fake_ok.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/pnl/intraday/fx-main",
        _intraday_with_warning(None),
    )

    result_ok = await get_pnl_attribution(
        book_id="fx-main",
        period="intraday",
        user=_DEFAULT_USER,
        http=fake_ok,
        now=_fixed_now,
    )

    assert result_ok["data_quality"] == "OK"
    assert "INTRADAY:OK" in result_ok["citation"].quality_flags


@pytest.mark.asyncio
async def test_intraday_empty_snapshots_raises_not_found() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/pnl/intraday/fx-main",
        {"bookId": "fx-main", "snapshots": []},
    )

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_pnl_attribution(
            book_id="fx-main",
            period="intraday",
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "NOT_FOUND"
    assert excinfo.value.status_code == 404


# ---------------------------------------------------------------------------
# Validation
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_invalid_period_raises_bad_request_without_calling_upstream() -> None:
    fake = FakeKinetixHttpClient()

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_pnl_attribution(
            book_id="fx-main",
            period="weekly",  # type: ignore[arg-type]
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "BAD_REQUEST"
    assert excinfo.value.status_code == 400
    assert fake.recorded_calls == []


# ---------------------------------------------------------------------------
# Citation
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_citation_records_call_params() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/pnl-attribution/fx-main",
        _DAILY_RESPONSE,
    )

    result = await get_pnl_attribution(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    # ``None`` values are preserved, not stripped — matches 2.1/2.2/2.3.
    assert result["citation"].params == {
        "book_id": "fx-main",
        "date": None,
        "period": None,
    }


@pytest.mark.asyncio
async def test_citation_result_value_and_field() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/pnl-attribution/fx-main",
        _DAILY_RESPONSE,
    )

    result = await get_pnl_attribution(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    citation = result["citation"]
    assert citation.result_field == "total_pnl"
    assert citation.result_value == result["total_pnl"]
    assert citation.result_currency == "USD"
    assert citation.data_source == "risk-orchestrator"
    assert citation.tool == "get_pnl_attribution"


@pytest.mark.asyncio
async def test_citation_uses_injected_now_for_freshness() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/pnl-attribution/fx-main",
        _DAILY_RESPONSE,
    )

    result = await get_pnl_attribution(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    citation = result["citation"]
    # Daily calculatedAt = 2026-05-19T08:00:00Z; now() = 2026-05-19T18:00:00Z.
    assert citation.as_of_timestamp == datetime(
        2026, 5, 19, 8, 0, 0, tzinfo=timezone.utc
    )
    assert citation.freshness_seconds == 10 * 3600


# ---------------------------------------------------------------------------
# ACL / fails closed
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_fails_closed_when_book_not_in_user_scope() -> None:
    fake = FakeKinetixHttpClient()
    user = UserContext(user_id="t1", books=("rates-only",))

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_pnl_attribution(
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
async def test_daily_propagates_not_found() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/pnl-attribution/fx-main",
        KinetixHttpError(
            status_code=404,
            code="NOT_FOUND",
            message="no attribution for date",
            service="risk-orchestrator",
            path="/api/v1/risk/pnl-attribution/fx-main",
        ),
    )

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_pnl_attribution(
            book_id="fx-main",
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "NOT_FOUND"
    assert excinfo.value.status_code == 404


@pytest.mark.asyncio
async def test_intraday_propagates_upstream_error() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/pnl/intraday/fx-main",
        KinetixHttpError(
            status_code=502,
            code="UPSTREAM_ERROR",
            message="risk orchestrator unreachable",
            service="risk-orchestrator",
            path="/api/v1/risk/pnl/intraday/fx-main",
        ),
    )

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_pnl_attribution(
            book_id="fx-main",
            period="intraday",
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "UPSTREAM_ERROR"
    assert excinfo.value.status_code == 502

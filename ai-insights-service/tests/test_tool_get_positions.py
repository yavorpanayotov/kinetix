"""Unit tests for the ``get_positions`` MCP tool.

These tests stub the ``KinetixHttpClient`` with
:class:`tests.fakes.fake_kinetix_http_client.FakeKinetixHttpClient` and
assert that:

* the tool routes to the correct position-service endpoint and does not
  forward any unsupported query parameters,
* the upstream ``List[PositionResponse]`` payload is mapped to the v2
  tool output shape defined in ``docs/plans/ai-v2.md`` § PR 2,
* a single :class:`Citation` describing the aggregate ``total_mtm`` value
  is populated with the expected provenance fields, including the v2
  quality flags surfacing the gaps vs the plan spec
  (``DELTA_UNAVAILABLE``, ``PNL_TODAY_FROM_UNREALIZED``,
  ``STALENESS_UNAVAILABLE``),
* client-side filtering by ``instrument_id`` / ``asset_class`` and
  ``top_n`` ordering by ``abs(mtm)`` desc behave as specified,
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
from kinetix_insights.mcp.tools.get_positions import get_positions
from tests.fakes.fake_kinetix_http_client import FakeKinetixHttpClient

pytestmark = pytest.mark.unit


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


_DEFAULT_USER = UserContext(user_id="trader-1", books=("fx-main", "rates-emea"))


def _position(
    *,
    instrument_id: str,
    asset_class: str,
    quantity: str = "1000000.00",
    market_value: str = "1090000.00",
    unrealized_pnl: str = "5000.00",
    instrument_type: str | None = "FX_SPOT",
) -> dict[str, Any]:
    """Build a representative upstream ``PositionResponse`` row."""

    return {
        "bookId": "fx-main",
        "instrumentId": instrument_id,
        "assetClass": asset_class,
        "quantity": quantity,
        "averageCost": {"amount": "1.0850", "currency": "USD"},
        "marketPrice": {"amount": "1.0900", "currency": "USD"},
        "marketValue": {"amount": market_value, "currency": "USD"},
        "unrealizedPnl": {"amount": unrealized_pnl, "currency": "USD"},
        "realizedPnl": {"amount": "1200.00", "currency": "USD"},
        "instrumentType": instrument_type,
        "strategyId": None,
        "strategyType": None,
        "strategyName": None,
    }


def _fixed_now() -> datetime:
    return datetime(2026, 5, 19, 16, 0, 0, tzinfo=timezone.utc)


# ---------------------------------------------------------------------------
# Endpoint and parameter wiring
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_positions_calls_correct_endpoint() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET", "position", "/api/v1/books/fx-main/positions", []
    )

    await get_positions(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert len(fake.recorded_calls) == 1
    call = fake.recorded_calls[0]
    assert call.method == "GET"
    assert call.service == "position"
    assert call.path == "/api/v1/books/fx-main/positions"
    # The upstream endpoint does not support query parameters; the tool
    # must not invent any even when callers supply filters.
    assert call.params is None
    assert call.user == _DEFAULT_USER


@pytest.mark.asyncio
async def test_get_positions_does_not_forward_filters_on_wire() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET", "position", "/api/v1/books/fx-main/positions", []
    )

    await get_positions(
        book_id="fx-main",
        instrument_id="EURUSD",
        asset_class="FX",
        top_n=5,
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    call = fake.recorded_calls[0]
    assert call.params is None


# ---------------------------------------------------------------------------
# Response mapping
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_positions_maps_response_fields() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "position",
        "/api/v1/books/fx-main/positions",
        [
            _position(
                instrument_id="EURUSD",
                asset_class="FX",
                quantity="1000000.00",
                market_value="1090000.00",
                unrealized_pnl="5000.00",
                instrument_type="FX_SPOT",
            ),
            _position(
                instrument_id="GBPUSD",
                asset_class="FX",
                quantity="500000.00",
                market_value="630000.00",
                unrealized_pnl="-1200.00",
                instrument_type="FX_SPOT",
            ),
        ],
    )

    result = await get_positions(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["total_count"] == 2
    assert result["returned_count"] == 2
    assert result["positions"] == [
        {
            "instrument_id": "EURUSD",
            "asset_class": "FX",
            "quantity": 1000000.00,
            "mtm": 1090000.00,
            "delta": None,
            "pnl_today": 5000.00,
            "is_stale": None,
            "instrument_type": "FX_SPOT",
        },
        {
            "instrument_id": "GBPUSD",
            "asset_class": "FX",
            "quantity": 500000.00,
            "mtm": 630000.00,
            "delta": None,
            "pnl_today": -1200.00,
            "is_stale": None,
            "instrument_type": "FX_SPOT",
        },
    ]


# ---------------------------------------------------------------------------
# Client-side filtering and ordering
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_positions_filters_by_instrument_id() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "position",
        "/api/v1/books/fx-main/positions",
        [
            _position(instrument_id="EURUSD", asset_class="FX"),
            _position(instrument_id="GBPUSD", asset_class="FX"),
            _position(instrument_id="USDJPY", asset_class="FX"),
        ],
    )

    result = await get_positions(
        book_id="fx-main",
        instrument_id="GBPUSD",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["total_count"] == 3
    assert result["returned_count"] == 1
    assert len(result["positions"]) == 1
    assert result["positions"][0]["instrument_id"] == "GBPUSD"


@pytest.mark.asyncio
async def test_get_positions_filters_by_asset_class() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "position",
        "/api/v1/books/fx-main/positions",
        [
            _position(instrument_id="EURUSD", asset_class="FX"),
            _position(instrument_id="UST10Y", asset_class="RATES"),
            _position(instrument_id="USDJPY", asset_class="FX"),
        ],
    )

    result = await get_positions(
        book_id="fx-main",
        asset_class="RATES",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["total_count"] == 3
    assert result["returned_count"] == 1
    assert result["positions"][0]["asset_class"] == "RATES"
    assert result["positions"][0]["instrument_id"] == "UST10Y"


@pytest.mark.asyncio
async def test_get_positions_top_n_orders_by_abs_mtm_desc() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "position",
        "/api/v1/books/fx-main/positions",
        [
            _position(instrument_id="A", asset_class="FX", market_value="100.00"),
            _position(instrument_id="B", asset_class="FX", market_value="-500.00"),
            _position(instrument_id="C", asset_class="FX", market_value="250.00"),
        ],
    )

    result = await get_positions(
        book_id="fx-main",
        top_n=2,
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["total_count"] == 3
    assert result["returned_count"] == 2
    assert [p["instrument_id"] for p in result["positions"]] == ["B", "C"]
    assert [p["mtm"] for p in result["positions"]] == [-500.00, 250.00]


@pytest.mark.asyncio
async def test_get_positions_combines_filters_and_top_n() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "position",
        "/api/v1/books/fx-main/positions",
        [
            _position(instrument_id="EURUSD", asset_class="FX", market_value="100.00"),
            _position(instrument_id="GBPUSD", asset_class="FX", market_value="-900.00"),
            _position(instrument_id="UST10Y", asset_class="RATES", market_value="5000.00"),
            _position(instrument_id="USDJPY", asset_class="FX", market_value="500.00"),
        ],
    )

    result = await get_positions(
        book_id="fx-main",
        asset_class="FX",
        top_n=1,
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["total_count"] == 4
    assert result["returned_count"] == 1
    assert result["positions"][0]["instrument_id"] == "GBPUSD"
    assert result["positions"][0]["mtm"] == -900.00


# ---------------------------------------------------------------------------
# Citation population
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_positions_citation_aggregates_total_mtm() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "position",
        "/api/v1/books/fx-main/positions",
        [
            _position(instrument_id="EURUSD", asset_class="FX", market_value="100.00"),
            _position(instrument_id="GBPUSD", asset_class="FX", market_value="-250.00"),
            _position(instrument_id="USDJPY", asset_class="FX", market_value="50.00"),
        ],
    )

    result = await get_positions(
        book_id="fx-main",
        top_n=2,
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    citation = result["citation"]
    assert citation.tool == "get_positions"
    assert citation.result_field == "total_mtm"
    # top_n=2 retains the two highest abs(mtm): -250.00 and 100.00 → sum = -150.00
    assert citation.result_value == -150.00
    assert citation.result_currency == "USD"
    assert citation.data_source == "position-service"
    assert citation.as_of_timestamp == _fixed_now()
    assert citation.freshness_seconds == 0
    flags = citation.quality_flags
    assert "DELTA_UNAVAILABLE" in flags
    assert "PNL_TODAY_FROM_UNREALIZED" in flags
    assert "STALENESS_UNAVAILABLE" in flags


@pytest.mark.asyncio
async def test_get_positions_citation_records_call_params() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET", "position", "/api/v1/books/fx-main/positions", []
    )

    result = await get_positions(
        book_id="fx-main",
        instrument_id="EURUSD",
        asset_class="FX",
        top_n=5,
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["citation"].params == {
        "book_id": "fx-main",
        "instrument_id": "EURUSD",
        "asset_class": "FX",
        "top_n": 5,
    }


# ---------------------------------------------------------------------------
# ACL / fails closed
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_positions_fails_closed_when_book_not_in_user_scope() -> None:
    fake = FakeKinetixHttpClient()
    user = UserContext(user_id="t1", books=("rates-only",))

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_positions(
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
async def test_get_positions_propagates_not_found() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "position",
        "/api/v1/books/fx-main/positions",
        KinetixHttpError(
            status_code=404,
            code="NOT_FOUND",
            message="unknown book",
            service="position",
            path="/api/v1/books/fx-main/positions",
        ),
    )

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_positions(
            book_id="fx-main",
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "NOT_FOUND"
    assert excinfo.value.status_code == 404


@pytest.mark.asyncio
async def test_get_positions_propagates_upstream_error() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "position",
        "/api/v1/books/fx-main/positions",
        KinetixHttpError(
            status_code=502,
            code="UPSTREAM_ERROR",
            message="position-service unreachable",
            service="position",
            path="/api/v1/books/fx-main/positions",
        ),
    )

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_positions(
            book_id="fx-main",
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "UPSTREAM_ERROR"
    assert excinfo.value.status_code == 502


# ---------------------------------------------------------------------------
# Empty book
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_positions_handles_empty_book() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET", "position", "/api/v1/books/fx-main/positions", []
    )

    result = await get_positions(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["positions"] == []
    assert result["total_count"] == 0
    assert result["returned_count"] == 0
    assert result["citation"].result_value == 0.0

"""Unit tests for the ``get_limit_utilisation`` MCP tool.

These tests stub the ``KinetixHttpClient`` with
:class:`tests.fakes.fake_kinetix_http_client.FakeKinetixHttpClient` and
assert that:

* the tool routes to the correct position-service endpoint
  (``GET /api/v1/limits``) with no query parameters — the upstream
  endpoint accepts none and filtering happens client-side,
* the upstream ``List[LimitDefinitionResponse]`` payload is filtered
  to BOOK-level rows whose ``entityId`` matches ``book_id``, optionally
  narrowed by ``limit_type``, and mapped to the v2 tool output shape
  defined in ``docs/plans/ai-v2.md`` § PR 2,
* every row carries v2 placeholders (``current is None``,
  ``utilisation_pct is None``, ``status == "UNKNOWN"``) and the
  citation always carries the matching gap quality flags
  (``CURRENT_VALUE_UNAVAILABLE``, ``UTILISATION_UNAVAILABLE``,
  ``STATUS_UNAVAILABLE``),
* a single :class:`Citation` describing the aggregate ``aggregate_limit``
  value is populated with the expected provenance fields,
* book-level ACL fails closed before the HTTP client is ever touched,
* upstream ``NOT_FOUND`` and ``UPSTREAM_ERROR`` errors propagate
  unmodified,
* a non-list upstream payload raises ``UPSTREAM_ERROR`` so a wire-shape
  drift is surfaced loudly.

No network, no FastMCP wiring — that lands in checkbox 2.11.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

import pytest

from kinetix_insights.clients.kinetix_http_client import KinetixHttpError
from kinetix_insights.clients.user_context import UserContext
from kinetix_insights.mcp.tools.get_limit_utilisation import (
    get_limit_utilisation,
)
from tests.fakes.fake_kinetix_http_client import FakeKinetixHttpClient

pytestmark = pytest.mark.unit


# ---------------------------------------------------------------------------
# Helpers and default fixtures (module-level constants — no ``...`` sentinel)
# ---------------------------------------------------------------------------


_DEFAULT_USER = UserContext(user_id="trader-1", books=("fx-main", "rates-emea"))

_BOOK_NOTIONAL_LIMIT: dict[str, Any] = {
    "id": "lim-001",
    "level": "BOOK",
    "entityId": "fx-main",
    "limitType": "NOTIONAL",
    "limitValue": "10000000.00",
    "intradayLimit": "8000000.00",
    "overnightLimit": "5000000.00",
    "active": True,
}

_BOOK_VAR_LIMIT: dict[str, Any] = {
    "id": "lim-002",
    "level": "BOOK",
    "entityId": "fx-main",
    "limitType": "VAR",
    "limitValue": "2500000.00",
    "intradayLimit": None,
    "overnightLimit": None,
    "active": True,
}

_OTHER_BOOK_LIMIT: dict[str, Any] = {
    "id": "lim-003",
    "level": "BOOK",
    "entityId": "rates-emea",
    "limitType": "NOTIONAL",
    "limitValue": "20000000.00",
    "intradayLimit": None,
    "overnightLimit": None,
    "active": True,
}

_FIRM_LIMIT: dict[str, Any] = {
    "id": "lim-004",
    "level": "FIRM",
    "entityId": "acme",
    "limitType": "NOTIONAL",
    "limitValue": "100000000.00",
    "intradayLimit": None,
    "overnightLimit": None,
    "active": True,
}

_COUNTERPARTY_LIMIT: dict[str, Any] = {
    "id": "lim-005",
    "level": "COUNTERPARTY",
    "entityId": "cp-1",
    "limitType": "CONCENTRATION",
    "limitValue": "5000000.00",
    "intradayLimit": None,
    "overnightLimit": None,
    "active": True,
}


def _fixed_now() -> datetime:
    return datetime(2026, 5, 19, 16, 0, 0, tzinfo=timezone.utc)


# ---------------------------------------------------------------------------
# Endpoint and parameter wiring
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_limit_utilisation_calls_correct_endpoint() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", "position", "/api/v1/limits", [])

    await get_limit_utilisation(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert len(fake.recorded_calls) == 1
    call = fake.recorded_calls[0]
    assert call.method == "GET"
    assert call.service == "position"
    assert call.path == "/api/v1/limits"
    # The upstream endpoint does not accept query parameters; the tool
    # must filter client-side and not invent any.
    assert call.params is None
    assert call.user == _DEFAULT_USER


# ---------------------------------------------------------------------------
# Client-side filtering: scope to BOOK-level rows for this book_id
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_limit_utilisation_filters_to_book_and_book_level() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "position",
        "/api/v1/limits",
        [
            _BOOK_NOTIONAL_LIMIT,
            _BOOK_VAR_LIMIT,
            _OTHER_BOOK_LIMIT,
            _FIRM_LIMIT,
            _COUNTERPARTY_LIMIT,
        ],
    )

    result = await get_limit_utilisation(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    names = {row["name"] for row in result["limits"]}
    assert names == {
        "BOOK:fx-main:NOTIONAL",
        "BOOK:fx-main:VAR",
    }


@pytest.mark.asyncio
async def test_get_limit_utilisation_filters_by_limit_type() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "position",
        "/api/v1/limits",
        [_BOOK_NOTIONAL_LIMIT, _BOOK_VAR_LIMIT],
    )

    result = await get_limit_utilisation(
        book_id="fx-main",
        limit_type="NOTIONAL",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert len(result["limits"]) == 1
    assert result["limits"][0]["limit_type"] == "NOTIONAL"


# ---------------------------------------------------------------------------
# Response mapping
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_limit_utilisation_maps_response_fields() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "position",
        "/api/v1/limits",
        [_BOOK_NOTIONAL_LIMIT],
    )

    result = await get_limit_utilisation(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["limits"] == [
        {
            "name": "BOOK:fx-main:NOTIONAL",
            "limit_type": "NOTIONAL",
            "current": None,
            "limit": 10_000_000.00,
            "utilisation_pct": None,
            "status": "UNKNOWN",
            "intraday_limit": 8_000_000.00,
            "overnight_limit": 5_000_000.00,
            "active": True,
        }
    ]


@pytest.mark.asyncio
async def test_get_limit_utilisation_sorts_by_limit_desc() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "position",
        "/api/v1/limits",
        # Intentionally not pre-sorted — tool must order largest-first.
        [_BOOK_VAR_LIMIT, _BOOK_NOTIONAL_LIMIT],
    )

    result = await get_limit_utilisation(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    limits = [row["limit"] for row in result["limits"]]
    assert limits == [10_000_000.00, 2_500_000.00]


@pytest.mark.asyncio
async def test_get_limit_utilisation_handles_null_intraday_overnight() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "position",
        "/api/v1/limits",
        [_BOOK_VAR_LIMIT],  # intradayLimit / overnightLimit both null
    )

    result = await get_limit_utilisation(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    row = result["limits"][0]
    assert row["intraday_limit"] is None
    assert row["overnight_limit"] is None


# ---------------------------------------------------------------------------
# Counts
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_limit_utilisation_total_definitions_and_returned_count() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "position",
        "/api/v1/limits",
        [
            _BOOK_NOTIONAL_LIMIT,
            _BOOK_VAR_LIMIT,
            _OTHER_BOOK_LIMIT,
            _FIRM_LIMIT,
        ],
    )

    result = await get_limit_utilisation(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["total_definitions"] == 4
    assert result["returned_count"] == 2
    assert result["returned_count"] == len(result["limits"])


# ---------------------------------------------------------------------------
# Citation
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_limit_utilisation_citation_aggregates_total_limit() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "position",
        "/api/v1/limits",
        [_BOOK_NOTIONAL_LIMIT, _BOOK_VAR_LIMIT],
    )

    result = await get_limit_utilisation(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    citation = result["citation"]
    assert citation.tool == "get_limit_utilisation"
    assert citation.result_field == "aggregate_limit"
    assert citation.result_value == 10_000_000.00 + 2_500_000.00
    assert citation.result_currency == "USD"
    assert citation.data_source == "position-service"
    flags = citation.quality_flags
    assert "CURRENT_VALUE_UNAVAILABLE" in flags
    assert "UTILISATION_UNAVAILABLE" in flags
    assert "STATUS_UNAVAILABLE" in flags


@pytest.mark.asyncio
async def test_get_limit_utilisation_citation_records_call_params() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", "position", "/api/v1/limits", [])

    result = await get_limit_utilisation(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    # ``None`` values are kept, not stripped — matches 2.1/2.2/2.3.
    assert result["citation"].params == {
        "book_id": "fx-main",
        "limit_type": None,
    }


@pytest.mark.asyncio
async def test_get_limit_utilisation_citation_as_of_uses_now_callable() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", "position", "/api/v1/limits", [])

    result = await get_limit_utilisation(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["citation"].as_of_timestamp == _fixed_now()
    assert result["citation"].freshness_seconds == 0


# ---------------------------------------------------------------------------
# ACL / fails closed
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_limit_utilisation_fails_closed_when_book_not_in_user_scope() -> None:
    fake = FakeKinetixHttpClient()
    user = UserContext(user_id="t1", books=("rates-only",))

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_limit_utilisation(
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
async def test_get_limit_utilisation_propagates_not_found_from_upstream() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "position",
        "/api/v1/limits",
        KinetixHttpError(
            status_code=404,
            code="NOT_FOUND",
            message="not found",
            service="position",
            path="/api/v1/limits",
        ),
    )

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_limit_utilisation(
            book_id="fx-main",
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "NOT_FOUND"
    assert excinfo.value.status_code == 404


@pytest.mark.asyncio
async def test_get_limit_utilisation_propagates_upstream_error() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "position",
        "/api/v1/limits",
        KinetixHttpError(
            status_code=502,
            code="UPSTREAM_ERROR",
            message="position-service unreachable",
            service="position",
            path="/api/v1/limits",
        ),
    )

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_limit_utilisation(
            book_id="fx-main",
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "UPSTREAM_ERROR"
    assert excinfo.value.status_code == 502


# ---------------------------------------------------------------------------
# Empty / no-limits-for-book
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_limit_utilisation_handles_no_limits_for_book() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", "position", "/api/v1/limits", [])

    result = await get_limit_utilisation(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["limits"] == []
    assert result["total_definitions"] == 0
    assert result["returned_count"] == 0
    assert result["citation"].result_value == 0.0


# ---------------------------------------------------------------------------
# Wire-shape drift
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_limit_utilisation_raises_upstream_error_on_non_list_payload() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "position",
        "/api/v1/limits",
        {"unexpected": "object-shape"},
    )

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_limit_utilisation(
            book_id="fx-main",
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "UPSTREAM_ERROR"
    assert excinfo.value.status_code == 502

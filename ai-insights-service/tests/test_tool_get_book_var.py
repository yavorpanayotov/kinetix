"""Unit tests for the ``get_book_var`` MCP tool.

These tests stub the ``KinetixHttpClient`` with
:class:`tests.fakes.fake_kinetix_http_client.FakeKinetixHttpClient` and
assert that:

* the tool routes to the correct risk-orchestrator endpoint,
* the upstream ``VaRResultResponse`` is mapped to the tool output shape
  defined in ``plans/ai-v2.md`` § PR 2,
* a single :class:`Citation` describing ``total_var`` is populated with
  the right provenance fields,
* book-level ACL fails closed before the HTTP client is ever touched,
* upstream ``NOT_FOUND`` and ``UPSTREAM_ERROR`` errors propagate
  unmodified.

No network, no FastMCP wiring — that lands in checkbox 2.11.
"""

from __future__ import annotations

from datetime import datetime, timezone

import pytest

from kinetix_insights.clients.kinetix_http_client import KinetixHttpError
from kinetix_insights.clients.user_context import UserContext
from kinetix_insights.mcp.tools.get_book_var import get_book_var
from tests.fakes.fake_kinetix_http_client import FakeKinetixHttpClient

pytestmark = pytest.mark.unit


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


_DEFAULT_USER = UserContext(user_id="trader-1", books=("fx-main", "rates-emea"))


def _sample_var_response(*, stale: bool | None = None) -> dict[str, object]:
    """A representative upstream ``VaRResultResponse`` payload."""

    payload: dict[str, object] = {
        "bookId": "fx-main",
        "calculationType": "PARAMETRIC",
        "confidenceLevel": "CL_95",
        "varValue": "1234567.89",
        "expectedShortfall": "1500000.00",
        "componentBreakdown": [
            {
                "assetClass": "FX",
                "varContribution": "1000000.00",
                "percentageOfTotal": "81.00",
            },
            {
                "assetClass": "RATES",
                "varContribution": "234567.89",
                "percentageOfTotal": "19.00",
            },
        ],
        "calculatedAt": "2026-05-19T08:00:00Z",
        "marketDataComplete": True,
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
async def test_get_book_var_calls_correct_endpoint_with_no_as_of() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET", "risk-orchestrator", "/api/v1/risk/var/fx-main", _sample_var_response()
    )

    await get_book_var(
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
    assert call.params in (None, {})
    assert call.user == _DEFAULT_USER


@pytest.mark.asyncio
async def test_get_book_var_forwards_as_of_as_valuation_date_param() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET", "risk-orchestrator", "/api/v1/risk/var/fx-main", _sample_var_response()
    )

    await get_book_var(
        book_id="fx-main",
        as_of="2026-05-18",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    call = fake.recorded_calls[0]
    assert call.params == {"valuationDate": "2026-05-18"}


@pytest.mark.asyncio
async def test_get_book_var_does_not_send_method_on_wire_but_records_it_in_citation() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET", "risk-orchestrator", "/api/v1/risk/var/fx-main", _sample_var_response()
    )

    result = await get_book_var(
        book_id="fx-main",
        method="PARAMETRIC",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    call = fake.recorded_calls[0]
    sent_params = call.params or {}
    assert "method" not in sent_params
    assert "calculationType" not in sent_params

    citation = result["citation"]
    assert citation.params["method"] == "PARAMETRIC"


# ---------------------------------------------------------------------------
# Response mapping
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_book_var_parses_total_var_and_breakdown() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET", "risk-orchestrator", "/api/v1/risk/var/fx-main", _sample_var_response()
    )

    result = await get_book_var(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["total_var"] == 1234567.89
    assert result["confidence_level"] == "CL_95"
    assert result["lookback_days"] is None
    assert result["var_by_asset_class"] == [
        {
            "asset_class": "FX",
            "var_contribution": 1000000.00,
            "percentage_of_total": 81.00,
        },
        {
            "asset_class": "RATES",
            "var_contribution": 234567.89,
            "percentage_of_total": 19.00,
        },
    ]


# ---------------------------------------------------------------------------
# Citation population
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_book_var_returns_citation_for_total_var() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET", "risk-orchestrator", "/api/v1/risk/var/fx-main", _sample_var_response()
    )

    result = await get_book_var(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    citation = result["citation"]
    assert citation.tool == "get_book_var"
    assert citation.result_field == "total_var"
    assert citation.result_value == 1234567.89
    assert citation.data_source == "risk-orchestrator"
    assert citation.as_of_timestamp == datetime(
        2026, 5, 19, 8, 0, 0, tzinfo=timezone.utc
    )
    assert citation.freshness_seconds == 8 * 3600


@pytest.mark.asyncio
async def test_get_book_var_flags_lookback_unavailable_in_citation() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET", "risk-orchestrator", "/api/v1/risk/var/fx-main", _sample_var_response()
    )

    result = await get_book_var(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert "LOOKBACK_UNAVAILABLE" in result["citation"].quality_flags


@pytest.mark.asyncio
async def test_get_book_var_flags_stale_when_upstream_marks_stale() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/var/fx-main",
        _sample_var_response(stale=True),
    )

    result = await get_book_var(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    flags = result["citation"].quality_flags
    assert "STALE" in flags
    assert "LOOKBACK_UNAVAILABLE" in flags


# ---------------------------------------------------------------------------
# ACL / fails closed
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_book_var_fails_closed_when_book_not_in_user_scope() -> None:
    fake = FakeKinetixHttpClient()
    user = UserContext(user_id="t1", books=("rates-only",))

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_book_var(
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
async def test_get_book_var_propagates_not_found_from_upstream() -> None:
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
        await get_book_var(
            book_id="fx-main",
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "NOT_FOUND"
    assert excinfo.value.status_code == 404


@pytest.mark.asyncio
async def test_get_book_var_propagates_upstream_error() -> None:
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
        await get_book_var(
            book_id="fx-main",
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "UPSTREAM_ERROR"
    assert excinfo.value.status_code == 502

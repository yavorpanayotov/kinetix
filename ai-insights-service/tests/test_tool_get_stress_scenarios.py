"""Unit tests for the ``get_stress_scenarios`` MCP tool.

These tests stub the ``KinetixHttpClient`` with
:class:`tests.fakes.fake_kinetix_http_client.FakeKinetixHttpClient` and
assert that:

* the tool routes to the correct risk-orchestrator endpoint
  (``POST /api/v1/risk/stress/{bookId}/batch``) with the canonical
  v2 defaults (``calculationType=PARAMETRIC``, ``confidenceLevel=CL_95``,
  ``timeHorizonDays="1"``) and the canonical default scenarios
  (``GFC``, ``EUR-crisis``, ``Fed+25bps``) when the caller doesn't
  supply any,
* caller-supplied scenarios are forwarded verbatim,
* the upstream ``BatchStressRunResultResponse`` payload maps to the
  v2 tool output shape (``name``, ``pnl_impact``, ``var_impact``,
  ``base_var``, ``stressed_var``, ``key_driver=None``), sorted by
  ``abs(pnl_impact)`` descending,
* ``worstScenarioName`` / ``worstPnlImpact`` (nullable upstream) are
  echoed onto the tool output and the citation,
* ``failedScenarios`` are echoed onto the tool output verbatim,
* every citation always carries ``KEY_DRIVER_UNAVAILABLE`` and
  ``COMPUTED_NOT_CACHED`` quality flags (v2 gaps vs the plan spec),
* book-level ACL fails closed before the HTTP client is ever touched,
* upstream ``NOT_FOUND`` and ``UPSTREAM_ERROR`` errors propagate
  unmodified,
* non-dict upstream payloads raise ``UPSTREAM_ERROR``/502.

No network, no FastMCP wiring — that lands in checkbox 2.11.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

import pytest

from kinetix_insights.clients.kinetix_http_client import KinetixHttpError
from kinetix_insights.clients.user_context import UserContext
from kinetix_insights.mcp.tools.get_stress_scenarios import (
    get_stress_scenarios,
)
from tests.fakes.fake_kinetix_http_client import FakeKinetixHttpClient

pytestmark = pytest.mark.unit


# ---------------------------------------------------------------------------
# Helpers and default fixtures (module-level constants — no ``...`` sentinel)
# ---------------------------------------------------------------------------


_DEFAULT_USER = UserContext(user_id="trader-1", books=("fx-main", "rates-emea"))

_BOOK_ID = "fx-main"
_BATCH_PATH = f"/api/v1/risk/stress/{_BOOK_ID}/batch"
_SERVICE = "risk-orchestrator"


_SAMPLE_BATCH_RESPONSE: dict[str, Any] = {
    "results": [
        {
            "scenarioName": "GFC",
            "baseVar": "1000000.00",
            "stressedVar": "3500000.00",
            "pnlImpact": "-2500000.00",
        },
        {
            "scenarioName": "EUR-crisis",
            "baseVar": "1000000.00",
            "stressedVar": "2200000.00",
            "pnlImpact": "-1200000.00",
        },
        {
            "scenarioName": "Fed+25bps",
            "baseVar": "1000000.00",
            "stressedVar": "1150000.00",
            "pnlImpact": "-150000.00",
        },
    ],
    "failedScenarios": [
        {
            "scenarioName": "TaperTantrum",
            "errorMessage": "missing market data",
        },
    ],
    "worstScenarioName": "GFC",
    "worstPnlImpact": "-2500000.00",
}


_BATCH_RESPONSE_NULL_WORST: dict[str, Any] = {
    "results": [],
    "failedScenarios": [],
    "worstScenarioName": None,
    "worstPnlImpact": None,
}


_BATCH_RESPONSE_EMPTY_FAILED: dict[str, Any] = {
    "results": [
        {
            "scenarioName": "GFC",
            "baseVar": "1000000.00",
            "stressedVar": "3500000.00",
            "pnlImpact": "-2500000.00",
        },
    ],
    "failedScenarios": [],
    "worstScenarioName": "GFC",
    "worstPnlImpact": "-2500000.00",
}


def _fixed_now() -> datetime:
    return datetime(2026, 5, 19, 18, 0, 0, tzinfo=timezone.utc)


# ---------------------------------------------------------------------------
# Endpoint & parameter wiring
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_stress_scenarios_calls_correct_endpoint_with_default_scenarios() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("POST", _SERVICE, _BATCH_PATH, _SAMPLE_BATCH_RESPONSE)

    await get_stress_scenarios(
        book_id=_BOOK_ID,
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert len(fake.recorded_calls) == 1
    call = fake.recorded_calls[0]
    assert call.method == "POST"
    assert call.service == _SERVICE
    assert call.path == _BATCH_PATH
    assert call.user == _DEFAULT_USER
    assert call.json is not None
    assert call.json["scenarioNames"] == ["GFC", "EUR-crisis", "Fed+25bps"]
    assert call.json["calculationType"] == "PARAMETRIC"
    assert call.json["confidenceLevel"] == "CL_95"
    assert call.json["timeHorizonDays"] == "1"


@pytest.mark.asyncio
async def test_get_stress_scenarios_forwards_supplied_scenarios() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("POST", _SERVICE, _BATCH_PATH, _SAMPLE_BATCH_RESPONSE)

    await get_stress_scenarios(
        book_id=_BOOK_ID,
        scenarios=["GFC", "FlashCrash"],
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    call = fake.recorded_calls[0]
    assert call.json is not None
    assert call.json["scenarioNames"] == ["GFC", "FlashCrash"]


# ---------------------------------------------------------------------------
# Response mapping
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_stress_scenarios_maps_results() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("POST", _SERVICE, _BATCH_PATH, _SAMPLE_BATCH_RESPONSE)

    result = await get_stress_scenarios(
        book_id=_BOOK_ID,
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["book_id"] == _BOOK_ID
    for row in result["scenarios"]:
        # Every row carries the full keyset.
        assert set(row.keys()) == {
            "name",
            "pnl_impact",
            "var_impact",
            "base_var",
            "stressed_var",
            "key_driver",
        }
        assert isinstance(row["pnl_impact"], float)
        assert isinstance(row["base_var"], float)
        assert isinstance(row["stressed_var"], float)
        # var_impact == stressed_var - base_var by construction.
        assert row["var_impact"] == row["stressed_var"] - row["base_var"]
        # v2 limitation — no key driver attribution from upstream.
        assert row["key_driver"] is None


@pytest.mark.asyncio
async def test_get_stress_scenarios_sorts_by_abs_pnl_impact_desc() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("POST", _SERVICE, _BATCH_PATH, _SAMPLE_BATCH_RESPONSE)

    result = await get_stress_scenarios(
        book_id=_BOOK_ID,
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    names = [row["name"] for row in result["scenarios"]]
    # GFC -2.5M > EUR-crisis -1.2M > Fed+25bps -150K by abs(pnl_impact).
    assert names == ["GFC", "EUR-crisis", "Fed+25bps"]


@pytest.mark.asyncio
async def test_get_stress_scenarios_echoes_worst_fields() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("POST", _SERVICE, _BATCH_PATH, _SAMPLE_BATCH_RESPONSE)

    result = await get_stress_scenarios(
        book_id=_BOOK_ID,
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["worst_scenario_name"] == "GFC"
    assert result["worst_pnl_impact"] == -2_500_000.0


@pytest.mark.asyncio
async def test_get_stress_scenarios_handles_null_worst_fields() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("POST", _SERVICE, _BATCH_PATH, _BATCH_RESPONSE_NULL_WORST)

    result = await get_stress_scenarios(
        book_id=_BOOK_ID,
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["worst_scenario_name"] is None
    assert result["worst_pnl_impact"] is None
    # Citation must still be well-formed; result_value falls back to 0.0.
    assert result["citation"].result_value == 0.0


@pytest.mark.asyncio
async def test_get_stress_scenarios_maps_failed_scenarios() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("POST", _SERVICE, _BATCH_PATH, _SAMPLE_BATCH_RESPONSE)

    result = await get_stress_scenarios(
        book_id=_BOOK_ID,
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["failed_scenarios"] == [
        {"name": "TaperTantrum", "error_message": "missing market data"}
    ]


@pytest.mark.asyncio
async def test_get_stress_scenarios_empty_failed_scenarios() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("POST", _SERVICE, _BATCH_PATH, _BATCH_RESPONSE_EMPTY_FAILED)

    result = await get_stress_scenarios(
        book_id=_BOOK_ID,
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["failed_scenarios"] == []


# ---------------------------------------------------------------------------
# Citation
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_stress_scenarios_citation_records_call_params() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("POST", _SERVICE, _BATCH_PATH, _SAMPLE_BATCH_RESPONSE)

    # Default — scenarios kwarg omitted: citation preserves ``None``.
    result_default = await get_stress_scenarios(
        book_id=_BOOK_ID,
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )
    assert result_default["citation"].params == {
        "book_id": _BOOK_ID,
        "scenarios": None,
    }

    # Supplied — citation echoes the exact list back.
    fake2 = FakeKinetixHttpClient()
    fake2.register_response("POST", _SERVICE, _BATCH_PATH, _SAMPLE_BATCH_RESPONSE)
    supplied = ["GFC", "FlashCrash"]
    result_supplied = await get_stress_scenarios(
        book_id=_BOOK_ID,
        scenarios=supplied,
        user=_DEFAULT_USER,
        http=fake2,
        now=_fixed_now,
    )
    assert result_supplied["citation"].params == {
        "book_id": _BOOK_ID,
        "scenarios": supplied,
    }


@pytest.mark.asyncio
async def test_get_stress_scenarios_citation_always_flags_key_driver_unavailable_and_computed_not_cached() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("POST", _SERVICE, _BATCH_PATH, _SAMPLE_BATCH_RESPONSE)

    result = await get_stress_scenarios(
        book_id=_BOOK_ID,
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    flags = result["citation"].quality_flags
    assert "KEY_DRIVER_UNAVAILABLE" in flags
    assert "COMPUTED_NOT_CACHED" in flags


@pytest.mark.asyncio
async def test_get_stress_scenarios_citation_result_value_is_worst_pnl_impact() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("POST", _SERVICE, _BATCH_PATH, _SAMPLE_BATCH_RESPONSE)

    result = await get_stress_scenarios(
        book_id=_BOOK_ID,
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    citation = result["citation"]
    assert citation.tool == "get_stress_scenarios"
    assert citation.result_field == "worst_pnl_impact"
    assert citation.result_value == -2_500_000.0
    assert citation.result_currency == "USD"
    assert citation.data_source == _SERVICE


@pytest.mark.asyncio
async def test_get_stress_scenarios_citation_freshness_zero() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("POST", _SERVICE, _BATCH_PATH, _SAMPLE_BATCH_RESPONSE)

    result = await get_stress_scenarios(
        book_id=_BOOK_ID,
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    citation = result["citation"]
    assert citation.freshness_seconds == 0
    assert citation.as_of_timestamp == _fixed_now()


# ---------------------------------------------------------------------------
# ACL / fails closed
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_stress_scenarios_fails_closed_when_book_not_in_user_scope() -> None:
    fake = FakeKinetixHttpClient()
    user = UserContext(user_id="t1", books=("rates-only",))

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_stress_scenarios(
            book_id=_BOOK_ID,
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
async def test_get_stress_scenarios_propagates_not_found() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "POST",
        _SERVICE,
        _BATCH_PATH,
        KinetixHttpError(
            status_code=404,
            code="NOT_FOUND",
            message="book not found",
            service=_SERVICE,
            path=_BATCH_PATH,
        ),
    )

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_stress_scenarios(
            book_id=_BOOK_ID,
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "NOT_FOUND"
    assert excinfo.value.status_code == 404


@pytest.mark.asyncio
async def test_get_stress_scenarios_propagates_upstream_error() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "POST",
        _SERVICE,
        _BATCH_PATH,
        KinetixHttpError(
            status_code=502,
            code="UPSTREAM_ERROR",
            message="risk orchestrator unreachable",
            service=_SERVICE,
            path=_BATCH_PATH,
        ),
    )

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_stress_scenarios(
            book_id=_BOOK_ID,
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "UPSTREAM_ERROR"
    assert excinfo.value.status_code == 502


@pytest.mark.asyncio
async def test_get_stress_scenarios_raises_upstream_error_on_non_dict_payload() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("POST", _SERVICE, _BATCH_PATH, [])  # list, not dict

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_stress_scenarios(
            book_id=_BOOK_ID,
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "UPSTREAM_ERROR"
    assert excinfo.value.status_code == 502

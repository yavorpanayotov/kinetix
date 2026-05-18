import pytest

from kinetix_insights.canned import CannedInsightClient
from kinetix_insights.models import InsightRequest

pytestmark = pytest.mark.unit


@pytest.mark.asyncio
async def test_same_var_input_yields_same_output() -> None:
    client = CannedInsightClient()
    request = InsightRequest(
        kind="var",
        payload={"as_of": "2026-05-18", "confidence": 0.99, "horizon_days": 1},
    )
    first = await client.explain(request)
    second = await client.explain(request)
    assert first == second


@pytest.mark.asyncio
async def test_same_report_input_yields_same_output() -> None:
    client = CannedInsightClient()
    request = InsightRequest(
        kind="report",
        payload={"period": "Q1-2026", "desk": "rates"},
    )
    first = await client.explain(request)
    second = await client.explain(request)
    assert first == second


@pytest.mark.asyncio
async def test_returns_mode_canned_for_var() -> None:
    client = CannedInsightClient()
    response = await client.explain(InsightRequest(kind="var", payload={"x": 1}))
    assert response.mode == "canned"


@pytest.mark.asyncio
async def test_returns_mode_canned_for_report() -> None:
    client = CannedInsightClient()
    response = await client.explain(InsightRequest(kind="report", payload={"period": "Q1"}))
    assert response.mode == "canned"


@pytest.mark.asyncio
async def test_different_payloads_can_yield_different_narratives() -> None:
    client = CannedInsightClient()
    payloads: list[dict[str, object]] = [
        {"confidence": 0.99, "horizon_days": 1},
        {"confidence": 0.95, "horizon_days": 1},
        {"confidence": 0.99, "horizon_days": 10},
        {"confidence": 0.975, "horizon_days": 5},
        {"as_of": "2026-05-18"},
        {"as_of": "2026-05-19"},
        {"book": "fx"},
        {"book": "rates"},
    ]
    narratives = {
        (await client.explain(InsightRequest(kind="var", payload=p))).narrative
        for p in payloads
    }
    assert len(narratives) > 1


@pytest.mark.asyncio
async def test_var_bullets_reference_top_contributors_when_present() -> None:
    client = CannedInsightClient()
    request = InsightRequest(
        kind="var",
        payload={"top_contributors": [{"instrument": "AAPL", "contribution_pct": 0.42}]},
    )
    response = await client.explain(request)
    assert any("AAPL" in bullet for bullet in response.bullets)


@pytest.mark.asyncio
async def test_report_bullets_reference_drivers_or_breaches_when_present() -> None:
    client = CannedInsightClient()
    request = InsightRequest(
        kind="report",
        payload={
            "top_drivers": ["equity-vol-spike", "credit-spread-widening"],
            "breaches": ["limit-exceeded"],
        },
    )
    response = await client.explain(request)
    joined = " ".join(response.bullets)
    assert "equity-vol-spike" in joined or "limit-exceeded" in joined

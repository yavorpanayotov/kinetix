"""Unit tests for :class:`ClaudeAgentInsightClient`.

These tests never touch the real ``claude-agent-sdk``. A lightweight fake
``query`` callable is injected via the constructor so we can capture the
prompt the client sends and control the stream of messages it receives.
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from dataclasses import dataclass
from typing import Any

import pytest

from kinetix_insights.claude_agent_client import (
    ClaudeAgentInsightClient,
    InsightClientUnavailable,
)
from kinetix_insights.models import InsightRequest, InsightResponse

pytestmark = pytest.mark.unit


@dataclass
class _FakeTextBlock:
    """Mimics the SDK's ``TextBlock`` shape — a single ``text`` field."""

    text: str


@dataclass
class _FakeAssistantMessage:
    """Mimics ``AssistantMessage(content=[TextBlock(...)])``."""

    content: list[_FakeTextBlock]


class _FakeSdk:
    """Captures the prompt sent and yields scripted assistant messages.

    Optionally raises ``raise_exc`` when ``query`` is iterated, so tests can
    simulate SDK failures.
    """

    def __init__(
        self,
        response_text: str = "",
        raise_exc: Exception | None = None,
    ) -> None:
        self.response_text = response_text
        self.raise_exc = raise_exc
        self.captured_prompt: str | None = None
        self.captured_kwargs: dict[str, Any] | None = None

    def query(self, *, prompt: str, **kwargs: Any) -> AsyncIterator[Any]:
        self.captured_prompt = prompt
        self.captured_kwargs = kwargs
        return self._stream()

    async def _stream(self) -> AsyncIterator[Any]:
        if self.raise_exc is not None:
            raise self.raise_exc
        yield _FakeAssistantMessage(content=[_FakeTextBlock(text=self.response_text)])


@pytest.mark.asyncio
async def test_var_prompt_constructed_correctly() -> None:
    sdk = _FakeSdk(response_text='{"narrative": "n", "bullets": []}')
    client = ClaudeAgentInsightClient(sdk=sdk)
    request = InsightRequest(
        kind="var",
        payload={
            "method": "historical",
            "confidence": 0.99,
            "horizon_days": 1,
            "value_usd": 1_250_000,
            "regime": "risk-on",
            "top_contributors": [
                {"instrument": "AAPL", "contribution_pct": 0.42},
                {"instrument": "MSFT", "contribution_pct": 0.18},
            ],
        },
    )

    await client.explain(request)

    assert sdk.captured_prompt is not None
    prompt = sdk.captured_prompt
    assert "historical" in prompt
    assert "0.99" in prompt
    assert "1250000" in prompt or "1,250,000" in prompt or "1250000.0" in prompt
    assert "AAPL" in prompt
    assert "JSON" in prompt
    assert "narrative" in prompt


@pytest.mark.asyncio
async def test_report_prompt_constructed_correctly() -> None:
    sdk = _FakeSdk(response_text='{"narrative": "n", "bullets": []}')
    client = ClaudeAgentInsightClient(sdk=sdk)
    request = InsightRequest(
        kind="report",
        payload={
            "template_id": "daily-risk-v3",
            "report_date": "2026-05-18",
            "summary_metrics": {"pnl_usd": 125_000.0, "var_usd": 1_250_000.0},
            "top_drivers": [
                {"name": "equity-vol-spike", "contribution_usd": 80_000},
                {"name": "credit-spread-widening", "contribution_usd": 45_000},
            ],
            "breaches": ["rates-dv01-limit"],
        },
    )

    await client.explain(request)

    assert sdk.captured_prompt is not None
    prompt = sdk.captured_prompt
    assert "daily-risk-v3" in prompt
    assert "2026-05-18" in prompt
    assert "equity-vol-spike" in prompt
    assert "rates-dv01-limit" in prompt
    assert "JSON" in prompt
    assert "narrative" in prompt


@pytest.mark.asyncio
async def test_response_parsed_into_insight_response_live_mode() -> None:
    sdk = _FakeSdk(
        response_text='{"narrative": "x", "bullets": ["a", "b"]}',
    )
    client = ClaudeAgentInsightClient(model="claude-sonnet-test", sdk=sdk)
    request = InsightRequest(kind="var", payload={"confidence": 0.99})

    response = await client.explain(request)

    assert isinstance(response, InsightResponse)
    assert response.narrative == "x"
    assert response.bullets == ["a", "b"]
    assert response.mode == "live"
    assert response.model == "claude-sonnet-test"


@pytest.mark.asyncio
async def test_sdk_exception_raises_insight_client_unavailable() -> None:
    sdk = _FakeSdk(raise_exc=RuntimeError("boom"))
    client = ClaudeAgentInsightClient(sdk=sdk)
    request = InsightRequest(kind="var", payload={"confidence": 0.99})

    with pytest.raises(InsightClientUnavailable):
        await client.explain(request)


@pytest.mark.asyncio
async def test_invalid_json_raises_insight_client_unavailable() -> None:
    sdk = _FakeSdk(response_text="this is not json")
    client = ClaudeAgentInsightClient(sdk=sdk)
    request = InsightRequest(kind="var", payload={"confidence": 0.99})

    with pytest.raises(InsightClientUnavailable):
        await client.explain(request)


@pytest.mark.asyncio
async def test_response_missing_narrative_raises_insight_client_unavailable() -> None:
    sdk = _FakeSdk(response_text='{"bullets": ["a"]}')
    client = ClaudeAgentInsightClient(sdk=sdk)
    request = InsightRequest(kind="var", payload={"confidence": 0.99})

    with pytest.raises(InsightClientUnavailable):
        await client.explain(request)


@pytest.mark.asyncio
async def test_response_bullets_must_be_strings() -> None:
    sdk = _FakeSdk(response_text='{"narrative": "n", "bullets": [1, 2, 3]}')
    client = ClaudeAgentInsightClient(sdk=sdk)
    request = InsightRequest(kind="var", payload={"confidence": 0.99})

    with pytest.raises(InsightClientUnavailable):
        await client.explain(request)

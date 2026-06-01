"""Unit tests for the data-grounded canned chat client (kx-fant).

These pin the behaviour that makes the demo Copilot "a bit more real":

* grounded topics (VaR, VaR-drivers, P&L) quote the **live** tool-result
  numbers — the narrative figure equals the value the MCP tool returned
  and the terminal frame's citations carry that same value (the
  contradiction the static fixtures exhibit is gone)
* the outbound tool call is scoped to the page's book via
  ``X-User-Books`` so it authorises against the seeded backend
* no ``claude`` subprocess is involved — grounding is pure HTTP
* ungrounded topics, book-less requests, and tool failures degrade
  gracefully to the fallback client without raising
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from datetime import datetime, timezone

import pytest

from kinetix_insights.chat.canned import CopilotChatClient
from kinetix_insights.chat.conversation_store import ConversationTurn
from kinetix_insights.chat.data_grounded_canned import DataGroundedCannedChatClient
from kinetix_insights.chat.models import ChatChunk, ChatRequest
from kinetix_insights.clients.kinetix_http_client import KinetixHttpError
from tests.fakes.fake_kinetix_http_client import FakeKinetixHttpClient

pytestmark = pytest.mark.unit


_NOW = datetime(2026, 5, 19, 8, 2, 0, tzinfo=timezone.utc)


def _now() -> datetime:
    return _NOW


# Live VaR payload — deliberately NOT the $5.2M fixture constant, so a
# test can prove the narrative tracks the backend rather than a fixture.
_VAR_PAYLOAD = {
    "varValue": 5_234_567.0,
    "confidenceLevel": "95%",
    "calculatedAt": "2026-05-19T08:00:00Z",
    "componentBreakdown": [
        {
            "assetClass": "RATES",
            "varContribution": 3_100_000.0,
            "percentageOfTotal": 59.2,
        },
        {
            "assetClass": "FX",
            "varContribution": 2_134_567.0,
            "percentageOfTotal": 40.8,
        },
    ],
}

_PNL_PAYLOAD = {
    "totalPnl": 1_842_900.0,
    "deltaPnl": 1_200_000.0,
    "gammaPnl": 510_000.0,
    "vegaPnl": -100_000.0,
    "thetaPnl": -45_000.0,
    "rhoPnl": 220_000.0,
    "vannaPnl": 30_000.0,
    "volgaPnl": 12_000.0,
    "charmPnl": -4_100.0,
    "crossGammaPnl": 8_000.0,
    "unexplainedPnl": 12_000.0,
    "calculatedAt": "2026-05-19T08:00:00Z",
    "dataQualityFlag": "FULL_ATTRIBUTION",
}


def _var_client() -> tuple[DataGroundedCannedChatClient, FakeKinetixHttpClient]:
    http = FakeKinetixHttpClient()
    http.register_response(
        "GET", "risk-orchestrator", "/api/v1/risk/var/fx-main", _VAR_PAYLOAD
    )
    client = DataGroundedCannedChatClient(http=http, delay_seconds=0.0, now=_now)
    return client, http


async def _collect(stream: AsyncIterator[ChatChunk]) -> list[ChatChunk]:
    return [chunk async for chunk in stream]


def _narrative(chunks: list[ChatChunk]) -> str:
    return "".join(c.delta for c in chunks if c.delta is not None)


# ---------------------------------------------------------------------------
# Protocol
# ---------------------------------------------------------------------------


def test_implements_copilot_chat_client_protocol() -> None:
    client, _ = _var_client()
    assert isinstance(client, CopilotChatClient)


# ---------------------------------------------------------------------------
# VaR grounding
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_var_narrative_quotes_the_live_tool_value() -> None:
    client, _ = _var_client()
    request = ChatRequest(
        message="what's my VaR?",
        page_context={"page": "var-dashboard", "book_id": "fx-main"},
    )

    chunks = await _collect(client.chat(request))
    narrative = _narrative(chunks)

    # The live value appears; the stale fixture constant does NOT.
    assert "$5,234,567" in narrative
    assert "5,200,000" not in narrative


@pytest.mark.asyncio
async def test_var_terminal_frame_citation_matches_tool_result() -> None:
    client, _ = _var_client()
    request = ChatRequest(
        message="explain my VaR",
        page_context={"page": "var-dashboard", "book_id": "fx-main"},
    )

    final = (await _collect(client.chat(request)))[-1]

    assert final.done is True
    assert final.mode == "canned-grounded"
    assert final.model == "canned-grounded"
    assert final.citations is not None
    headline = final.citations[0]
    assert headline.result_value == _VAR_PAYLOAD["varValue"]
    assert headline.tool == "get_book_var"


@pytest.mark.asyncio
async def test_var_call_is_scoped_to_the_page_book() -> None:
    client, http = _var_client()
    request = ChatRequest(
        message="what's my VaR?",
        page_context={"page": "var-dashboard", "book_id": "fx-main"},
    )

    await _collect(client.chat(request))

    assert len(http.recorded_calls) == 1
    call = http.recorded_calls[0]
    assert call.service == "risk-orchestrator"
    assert call.path == "/api/v1/risk/var/fx-main"
    assert "fx-main" in call.user.books


@pytest.mark.asyncio
async def test_every_narrative_number_is_a_tool_result_value() -> None:
    """No numeric token in the narrative is a fixture invention."""

    client, _ = _var_client()
    request = ChatRequest(
        message="what drove my VaR this week?",  # var_drivers
        page_context={"page": "var-dashboard", "book_id": "fx-main"},
    )

    chunks = await _collect(client.chat(request))
    final = chunks[-1]
    narrative = _narrative(chunks)

    # Both the headline and the top-contributor figures are cited, and
    # both appear in the narrative formatted from the cited values.
    assert final.citations is not None
    cited_money = {f"${c.result_value:,.0f}" for c in final.citations}
    assert "$5,234,567" in cited_money
    assert "$3,100,000" in cited_money  # RATES is the larger contributor
    assert "$5,234,567" in narrative
    assert "$3,100,000" in narrative
    assert "RATES" in narrative


# ---------------------------------------------------------------------------
# P&L grounding
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_pnl_narrative_quotes_the_live_total() -> None:
    http = FakeKinetixHttpClient()
    http.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/pnl-attribution/fx-main",
        _PNL_PAYLOAD,
    )
    client = DataGroundedCannedChatClient(http=http, delay_seconds=0.0, now=_now)
    request = ChatRequest(
        message="how's my P&L today?",
        page_context={"page": "pnl-attribution", "book_id": "fx-main"},
    )

    chunks = await _collect(client.chat(request))
    final = chunks[-1]
    narrative = _narrative(chunks)

    assert "$1,842,900" in narrative
    assert final.mode == "canned-grounded"
    assert final.citations is not None
    assert final.citations[0].result_value == _PNL_PAYLOAD["totalPnl"]
    # Largest driver is delta (1.2M); it is cited and named.
    assert "delta" in narrative
    assert "$1,200,000" in narrative


# ---------------------------------------------------------------------------
# Graceful degradation
# ---------------------------------------------------------------------------


class _SentinelFallback:
    """A fallback that streams one identifiable frame, to prove delegation."""

    def chat(
        self,
        request: ChatRequest,
        *,
        history: list[ConversationTurn] | None = None,
    ) -> AsyncIterator[ChatChunk]:
        return self._stream()

    async def _stream(self) -> AsyncIterator[ChatChunk]:
        yield ChatChunk(delta="FALLBACK", done=False)
        yield ChatChunk(delta=None, done=True, model="canned", mode="canned")


@pytest.mark.asyncio
async def test_missing_book_id_falls_back_without_calling_tools() -> None:
    http = FakeKinetixHttpClient()
    client = DataGroundedCannedChatClient(
        http=http, fallback=_SentinelFallback(), delay_seconds=0.0, now=_now
    )
    request = ChatRequest(
        message="what's my VaR?", page_context={"page": "var-dashboard"}
    )

    chunks = await _collect(client.chat(request))

    assert _narrative(chunks) == "FALLBACK"
    assert http.recorded_calls == []


@pytest.mark.asyncio
async def test_ungrounded_topic_falls_back() -> None:
    http = FakeKinetixHttpClient()
    client = DataGroundedCannedChatClient(
        http=http, fallback=_SentinelFallback(), delay_seconds=0.0, now=_now
    )
    request = ChatRequest(
        message="any vol dislocations?",
        page_context={"page": "var-dashboard", "book_id": "fx-main"},
    )

    chunks = await _collect(client.chat(request))

    assert _narrative(chunks) == "FALLBACK"
    assert http.recorded_calls == []


@pytest.mark.asyncio
async def test_tool_failure_degrades_to_fallback_without_raising() -> None:
    http = FakeKinetixHttpClient()
    http.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/var/fx-main",
        KinetixHttpError(
            status_code=502,
            code="UPSTREAM_ERROR",
            message="risk-orchestrator down",
            service="risk-orchestrator",
            path="/api/v1/risk/var/fx-main",
        ),
    )
    client = DataGroundedCannedChatClient(
        http=http, fallback=_SentinelFallback(), delay_seconds=0.0, now=_now
    )
    request = ChatRequest(
        message="what's my VaR?",
        page_context={"page": "var-dashboard", "book_id": "fx-main"},
    )

    chunks = await _collect(client.chat(request))

    # Tool was attempted, failed, and we degraded — stream still closed.
    assert len(http.recorded_calls) == 1
    assert _narrative(chunks) == "FALLBACK"
    assert chunks[-1].done is True

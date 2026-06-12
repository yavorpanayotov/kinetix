"""Unit tests for the data-grounded canned chat client (kx-fant).

These pin the behaviour that makes the demo Copilot "a bit more real":

* grounded topics (VaR, VaR-drivers, P&L, positions, limits, vol) quote
  the **live** tool-result numbers — the narrative figure equals the
  value the MCP tool returned and the terminal frame's citations carry
  that same value (the contradiction the static fixtures exhibit is
  gone)
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
    """A message routing to UNMATCHED delegates to the fallback client.

    The message must carry no topic keywords and the page must not map
    to a topic — every routable topic is now grounded, so UNMATCHED is
    the only ungrounded route left.
    """

    http = FakeKinetixHttpClient()
    client = DataGroundedCannedChatClient(
        http=http, fallback=_SentinelFallback(), delay_seconds=0.0, now=_now
    )
    request = ChatRequest(
        message="what is the meaning of life?",
        page_context={"page": "settings", "book_id": "fx-main"},
    )

    chunks = await _collect(client.chat(request))

    assert _narrative(chunks) == "FALLBACK"
    assert http.recorded_calls == []


@pytest.mark.asyncio
async def test_unlabelled_pnl_component_logs_warning_and_renders_raw_key(
    caplog: pytest.LogCaptureFixture,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """An unknown P&L component key logs a warning and still renders via the raw key.

    Simulates a future risk engine adding a new Greek (``delta_pnl`` is removed
    from ``_PNL_COMPONENT_LABELS`` via monkeypatch so it acts as an unlabelled
    key) to drive the unlabelled-label code path without changing the real label
    map permanently.
    """
    import logging

    import kinetix_insights.chat.data_grounded_canned as _module

    # Remove 'delta_pnl' from the label map so it becomes an unlabelled key.
    patched_labels = {
        k: v
        for k, v in _module._PNL_COMPONENT_LABELS.items()
        if k != "delta_pnl"
    }
    monkeypatch.setattr(_module, "_PNL_COMPONENT_LABELS", patched_labels)

    http = FakeKinetixHttpClient()
    http.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/pnl-attribution/fx-main",
        _PNL_PAYLOAD,  # delta_pnl=1.2M is the dominant component
    )
    client = DataGroundedCannedChatClient(http=http, delay_seconds=0.0, now=_now)
    request = ChatRequest(
        message="how's my P&L today?",
        page_context={"page": "pnl-attribution", "book_id": "fx-main"},
    )

    with caplog.at_level(logging.WARNING, logger="kinetix_insights.chat"):
        chunks = await _collect(client.chat(request))

    narrative = _narrative(chunks)

    # Warning must be emitted mentioning the unlabelled key.
    assert any(
        "delta_pnl" in record.message for record in caplog.records
    ), f"Expected a warning about 'delta_pnl'; got: {[r.message for r in caplog.records]}"

    # Narrative still renders with the raw key as the label (graceful, never raises).
    assert "delta_pnl" in narrative


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


# ---------------------------------------------------------------------------
# Positions / limits / vol grounding (kx-vsp2)
# ---------------------------------------------------------------------------

# The new grounded topics use a book that is deliberately NOT fx-main so
# the tests can prove no hardcoded fx-main fixture prose leaks through.
_BOOK = "em-rates"


def _position_row(
    *,
    instrument_id: str,
    asset_class: str = "FX",
    market_value: str,
    unrealized_pnl: str,
    instrument_type: str | None = "FX_SPOT",
) -> dict:
    """Build a representative upstream ``PositionResponse`` row."""

    return {
        "bookId": _BOOK,
        "instrumentId": instrument_id,
        "assetClass": asset_class,
        "quantity": "1000000.00",
        "averageCost": {"amount": "1.0850", "currency": "USD"},
        "marketPrice": {"amount": "1.0900", "currency": "USD"},
        "marketValue": {"amount": market_value, "currency": "USD"},
        "unrealizedPnl": {"amount": unrealized_pnl, "currency": "USD"},
        "realizedPnl": {"amount": "0.00", "currency": "USD"},
        "instrumentType": instrument_type,
        "strategyId": None,
        "strategyType": None,
        "strategyName": None,
    }


# Three positions: EURUSD spot is the largest by |mtm|; GBPUSD is the
# largest DERIVATIVE (FX_OPTION) — the vol template must prefer it.
_POSITIONS_PAYLOAD = [
    _position_row(
        instrument_id="EURUSD",
        market_value="12500000.00",
        unrealized_pnl="340000.00",
        instrument_type="FX_SPOT",
    ),
    _position_row(
        instrument_id="USDJPY",
        market_value="7800000.00",
        unrealized_pnl="125000.00",
        instrument_type="FX_SPOT",
    ),
    _position_row(
        instrument_id="GBPUSD",
        market_value="4200000.00",
        unrealized_pnl="-75000.00",
        instrument_type="FX_OPTION",
    ),
]

_LIMITS_PAYLOAD = [
    {
        "level": "BOOK",
        "entityId": _BOOK,
        "limitType": "VAR",
        "limitValue": "8000000.00",
        "intradayLimit": None,
        "overnightLimit": None,
        "active": True,
    },
    {
        "level": "BOOK",
        "entityId": _BOOK,
        "limitType": "NOTIONAL",
        "limitValue": "25000000.00",
        "intradayLimit": None,
        "overnightLimit": None,
        "active": True,
    },
    # FIRM-level row must be ignored by the BOOK-scoped tool.
    {
        "level": "FIRM",
        "entityId": "kinetix",
        "limitType": "VAR",
        "limitValue": "900000000.00",
        "intradayLimit": None,
        "overnightLimit": None,
        "active": True,
    },
]

_BREACHES_PAYLOAD = [
    {
        "id": "breach-1",
        "entityId": _BOOK,
        "limitType": "VAR",
        "severity": "CRITICAL",
        "currentValue": "9100000.00",
        "limitValue": "8000000.00",
        "breachedAt": "2026-05-18T14:00:00Z",  # within 7 days of _NOW
        "resolvedAt": None,
    },
]

_VOL_SURFACE_GBPUSD = {
    "underlier": "GBPUSD",
    "asOfDate": "2026-05-19T07:30:00Z",
    "source": "demo",
    "points": [
        {"strike": 1.25, "maturityDays": 30, "impliedVol": 14.5},
        {"strike": 1.25, "maturityDays": 90, "impliedVol": 11.0},
    ],
}

_VOL_SURFACE_EURUSD = {
    "underlier": "EURUSD",
    "asOfDate": "2026-05-19T07:30:00Z",
    "source": "demo",
    "points": [
        {"strike": 1.09, "maturityDays": 30, "impliedVol": 8.0},
        {"strike": 1.09, "maturityDays": 90, "impliedVol": 8.5},
    ],
}


def _positions_http() -> FakeKinetixHttpClient:
    http = FakeKinetixHttpClient()
    http.register_response(
        "GET", "position", f"/api/v1/books/{_BOOK}/positions", _POSITIONS_PAYLOAD
    )
    return http


# -- positions ---------------------------------------------------------------


@pytest.mark.asyncio
async def test_positions_narrative_quotes_live_tool_values() -> None:
    http = _positions_http()
    client = DataGroundedCannedChatClient(http=http, delay_seconds=0.0, now=_now)
    request = ChatRequest(
        message="what are my largest positions?",
        page_context={"page": "positions", "book_id": _BOOK},
    )

    chunks = await _collect(client.chat(request))
    final = chunks[-1]
    narrative = _narrative(chunks)

    # Total MTM of all rows, then the top positions by |mtm| with P&L.
    assert "$24,500,000" in narrative
    assert "EURUSD" in narrative
    assert "$12,500,000" in narrative
    assert "$340,000" in narrative
    assert "fx-main" not in narrative
    assert final.done is True
    assert final.mode == "canned-grounded"
    assert final.citations is not None
    headline = final.citations[0]
    assert headline.tool == "get_positions"
    assert headline.result_value == 24_500_000.0
    assert final.tool_calls is not None
    assert [t.name for t in final.tool_calls] == ["get_positions"]


@pytest.mark.asyncio
async def test_positions_call_is_scoped_to_the_page_book() -> None:
    http = _positions_http()
    client = DataGroundedCannedChatClient(http=http, delay_seconds=0.0, now=_now)
    request = ChatRequest(
        message="show my top 3 positions",
        page_context={"page": "positions", "book_id": _BOOK},
    )

    await _collect(client.chat(request))

    assert len(http.recorded_calls) == 1
    call = http.recorded_calls[0]
    assert call.service == "position"
    assert call.path == f"/api/v1/books/{_BOOK}/positions"
    assert call.user.books == (_BOOK,)


@pytest.mark.asyncio
async def test_positions_tool_failure_degrades_to_fallback() -> None:
    http = FakeKinetixHttpClient()
    http.register_response(
        "GET",
        "position",
        f"/api/v1/books/{_BOOK}/positions",
        KinetixHttpError(
            status_code=502,
            code="UPSTREAM_ERROR",
            message="position-service down",
            service="position",
            path=f"/api/v1/books/{_BOOK}/positions",
        ),
    )
    client = DataGroundedCannedChatClient(
        http=http, fallback=_SentinelFallback(), delay_seconds=0.0, now=_now
    )
    request = ChatRequest(
        message="what are my largest positions?",
        page_context={"page": "positions", "book_id": _BOOK},
    )

    chunks = await _collect(client.chat(request))

    assert _narrative(chunks) == "FALLBACK"
    assert chunks[-1].done is True


# -- limits -------------------------------------------------------------------


def _limits_http() -> FakeKinetixHttpClient:
    http = FakeKinetixHttpClient()
    http.register_response("GET", "position", "/api/v1/limits", _LIMITS_PAYLOAD)
    http.register_response(
        "GET",
        "position",
        f"/api/v1/books/{_BOOK}/limit-breaches",
        _BREACHES_PAYLOAD,
    )
    return http


@pytest.mark.asyncio
async def test_limits_narrative_quotes_live_caps_and_breaches() -> None:
    http = _limits_http()
    client = DataGroundedCannedChatClient(http=http, delay_seconds=0.0, now=_now)
    request = ChatRequest(
        message="any limit breaches?",
        page_context={"page": "alerts", "book_id": _BOOK},
    )

    chunks = await _collect(client.chat(request))
    final = chunks[-1]
    narrative = _narrative(chunks)

    # Aggregate cap across the two BOOK-level rows; FIRM row ignored.
    assert "$33,000,000" in narrative
    assert "$900,000,000" not in narrative
    # The recent breach: live current value vs its limit.
    assert "$9,100,000" in narrative
    assert "$8,000,000" in narrative
    assert "VAR" in narrative
    assert "fx-main" not in narrative
    assert final.done is True
    assert final.mode == "canned-grounded"
    assert final.citations is not None
    cited = {(c.tool, c.result_value) for c in final.citations}
    assert ("get_limit_utilisation", 33_000_000.0) in cited
    assert ("get_recent_breaches", 1.0) in cited
    assert final.tool_calls is not None
    assert [t.name for t in final.tool_calls] == [
        "get_limit_utilisation",
        "get_recent_breaches",
    ]


@pytest.mark.asyncio
async def test_limits_narrative_reports_no_recent_breaches() -> None:
    http = FakeKinetixHttpClient()
    http.register_response("GET", "position", "/api/v1/limits", _LIMITS_PAYLOAD)
    http.register_response(
        "GET", "position", f"/api/v1/books/{_BOOK}/limit-breaches", []
    )
    client = DataGroundedCannedChatClient(http=http, delay_seconds=0.0, now=_now)
    request = ChatRequest(
        message="how is my limit utilisation?",
        page_context={"page": "alerts", "book_id": _BOOK},
    )

    chunks = await _collect(client.chat(request))
    narrative = _narrative(chunks)

    assert "$33,000,000" in narrative
    assert "No limit breaches in the last 7 days" in narrative
    assert "fx-main" not in narrative


@pytest.mark.asyncio
async def test_limits_tool_failure_degrades_to_fallback() -> None:
    http = FakeKinetixHttpClient()
    http.register_response(
        "GET",
        "position",
        "/api/v1/limits",
        KinetixHttpError(
            status_code=502,
            code="UPSTREAM_ERROR",
            message="position-service down",
            service="position",
            path="/api/v1/limits",
        ),
    )
    client = DataGroundedCannedChatClient(
        http=http, fallback=_SentinelFallback(), delay_seconds=0.0, now=_now
    )
    request = ChatRequest(
        message="any limit breaches?",
        page_context={"page": "alerts", "book_id": _BOOK},
    )

    chunks = await _collect(client.chat(request))

    assert _narrative(chunks) == "FALLBACK"
    assert chunks[-1].done is True


# -- vol ----------------------------------------------------------------------


def _vol_http() -> FakeKinetixHttpClient:
    """Positions plus surfaces for BOTH the spot and the option underliers.

    Registering a surface for EURUSD (the largest position overall) as
    well as GBPUSD (the largest derivative) pins the preference: the
    template must pick the derivative's surface, not just the first
    underlier that happens to resolve.
    """

    http = _positions_http()
    http.register_response(
        "GET",
        "volatility",
        "/api/v1/volatility/GBPUSD/surface/latest",
        _VOL_SURFACE_GBPUSD,
    )
    http.register_response(
        "GET",
        "volatility",
        "/api/v1/volatility/EURUSD/surface/latest",
        _VOL_SURFACE_EURUSD,
    )
    return http


@pytest.mark.asyncio
async def test_vol_narrative_quotes_surface_of_largest_derivative() -> None:
    http = _vol_http()
    client = DataGroundedCannedChatClient(http=http, delay_seconds=0.0, now=_now)
    request = ChatRequest(
        message="any vol dislocations?",
        page_context={"page": "var-dashboard", "book_id": _BOOK},
    )

    chunks = await _collect(client.chat(request))
    final = chunks[-1]
    narrative = _narrative(chunks)

    # GBPUSD (FX_OPTION) wins over the larger EURUSD spot position.
    assert "GBPUSD" in narrative
    assert "14.5%" in narrative
    # 30d ATM 14.5 vs 90d ATM 11.0 -> 3.5 vol-point inversion.
    assert "3.5" in narrative
    assert "fx-main" not in narrative
    assert final.done is True
    assert final.mode == "canned-grounded"
    assert final.citations is not None
    headline = final.citations[0]
    assert headline.tool == "get_vol_surface"
    assert headline.result_value == 14.5
    assert final.tool_calls is not None
    assert [t.name for t in final.tool_calls] == [
        "get_positions",
        "get_vol_surface",
    ]


@pytest.mark.asyncio
async def test_vol_falls_back_to_largest_position_when_no_derivative() -> None:
    http = FakeKinetixHttpClient()
    spot_only = [row for row in _POSITIONS_PAYLOAD if row["instrumentType"] == "FX_SPOT"]
    http.register_response(
        "GET", "position", f"/api/v1/books/{_BOOK}/positions", spot_only
    )
    http.register_response(
        "GET",
        "volatility",
        "/api/v1/volatility/EURUSD/surface/latest",
        _VOL_SURFACE_EURUSD,
    )
    client = DataGroundedCannedChatClient(http=http, delay_seconds=0.0, now=_now)
    request = ChatRequest(
        message="how is implied volatility looking?",
        page_context={"page": "var-dashboard", "book_id": _BOOK},
    )

    chunks = await _collect(client.chat(request))
    narrative = _narrative(chunks)

    assert "EURUSD" in narrative
    assert "8.0%" in narrative
    assert "fx-main" not in narrative


@pytest.mark.asyncio
async def test_vol_falls_back_to_fixtures_when_no_surface_exists() -> None:
    # Positions resolve, but NO surface is registered for any underlier:
    # the fake raises NOT_FOUND for every candidate, so the client must
    # degrade to the fallback fixtures exactly like other failure paths.
    http = _positions_http()
    client = DataGroundedCannedChatClient(
        http=http, fallback=_SentinelFallback(), delay_seconds=0.0, now=_now
    )
    request = ChatRequest(
        message="any vol dislocations?",
        page_context={"page": "var-dashboard", "book_id": _BOOK},
    )

    chunks = await _collect(client.chat(request))

    assert _narrative(chunks) == "FALLBACK"
    assert chunks[-1].done is True


@pytest.mark.asyncio
async def test_vol_falls_back_when_book_has_no_positions() -> None:
    http = FakeKinetixHttpClient()
    http.register_response(
        "GET", "position", f"/api/v1/books/{_BOOK}/positions", []
    )
    client = DataGroundedCannedChatClient(
        http=http, fallback=_SentinelFallback(), delay_seconds=0.0, now=_now
    )
    request = ChatRequest(
        message="any vol dislocations?",
        page_context={"page": "var-dashboard", "book_id": _BOOK},
    )

    chunks = await _collect(client.chat(request))

    assert _narrative(chunks) == "FALLBACK"
    assert chunks[-1].done is True


# -- missing book_id falls back for all three new topics -----------------------


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "message",
    [
        "what are my largest positions?",
        "any limit breaches?",
        "any vol dislocations?",
    ],
)
async def test_new_topics_missing_book_id_falls_back_without_calling_tools(
    message: str,
) -> None:
    http = FakeKinetixHttpClient()
    client = DataGroundedCannedChatClient(
        http=http, fallback=_SentinelFallback(), delay_seconds=0.0, now=_now
    )
    request = ChatRequest(message=message, page_context={"page": "positions"})

    chunks = await _collect(client.chat(request))

    assert _narrative(chunks) == "FALLBACK"
    assert http.recorded_calls == []

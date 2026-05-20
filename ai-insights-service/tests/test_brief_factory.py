"""Unit tests for the morning-brief client factory (checkbox 6.6).

The factory chooses between the offline :class:`CannedBriefClient` —
a deterministic fixture replay used for the 90-second demo and CI —
and the live :class:`ClaudeAgentBriefClient` which wraps the
:class:`MorningBriefGenerator`. Selection follows the same
``DEMO_MODE`` rule as :func:`kinetix_insights.factory.build_client`
and :func:`kinetix_insights.chat.factory.build_chat_client`.

These tests cover:

* ``DEMO_MODE`` resolution (true / case-insensitive / unset),
* fallback to the canned client when the live client cannot be built,
* the canned client parsing its fixture into ``MorningBrief`` objects,
* both clients satisfying the ``BriefClient`` protocol,
* the live client wrapping the generator without an SDK.

No network and no real ``claude_agent_sdk`` — the live client is
exercised through :class:`tests.fakes.fake_kinetix_http_client.FakeKinetixHttpClient`.
"""

from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path

import pytest

from kinetix_insights.brief import factory as brief_factory
from kinetix_insights.brief.canned import BriefClient, CannedBriefClient
from kinetix_insights.brief.claude_agent_brief_client import ClaudeAgentBriefClient
from kinetix_insights.brief.factory import build_brief_client
from kinetix_insights.brief.models import MorningBrief
from kinetix_insights.citations.models import Citation
from kinetix_insights.clients.user_context import UserContext
from tests.fakes.fake_kinetix_http_client import FakeKinetixHttpClient

pytestmark = pytest.mark.unit


# ---------------------------------------------------------------------------
# Shared fixtures — endpoints the five tools fan out to for the live client
# ---------------------------------------------------------------------------

_BOOK = "fx-main"
_USER = UserContext(user_id="trader-1", books=(_BOOK,))


def _fixed_now() -> datetime:
    return datetime(2026, 5, 20, 16, 0, 0, tzinfo=timezone.utc)


def _var_response(book: str = _BOOK) -> dict[str, object]:
    return {
        "bookId": book,
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
        ],
        "calculatedAt": "2026-05-20T08:00:00Z",
        "marketDataComplete": True,
        "greeks": {
            "theta": "-12000.0",
            "rho": "5000.0",
            "assetClassGreeks": [
                {"delta": "250000.0", "gamma": "1200.0", "vega": "8000.0"},
            ],
        },
        "positionGreeks": [
            {
                "instrumentId": "EURUSD_CALL_20260601_1.09",
                "delta": "250000.0",
                "gamma": "1200.0",
                "vega": "8000.0",
                "theta": "-12000.0",
                "rho": "5000.0",
            }
        ],
    }


def _pnl_response(book: str = _BOOK) -> dict[str, object]:
    return {
        "bookId": book,
        "date": "2026-05-20",
        "totalPnl": "85000.0",
        "deltaPnl": "60000.0",
        "gammaPnl": "5000.0",
        "vegaPnl": "10000.0",
        "thetaPnl": "-3000.0",
        "rhoPnl": "1000.0",
        "vannaPnl": "500.0",
        "volgaPnl": "300.0",
        "charmPnl": "200.0",
        "crossGammaPnl": "1000.0",
        "unexplainedPnl": "10000.0",
        "calculatedAt": "2026-05-20T08:00:00Z",
        "dataQualityFlag": "FULL_ATTRIBUTION",
    }


def _breaches_response() -> list[dict[str, object]]:
    return [
        {
            "id": "resolved-1",
            "entityId": _BOOK,
            "limitType": "NOTIONAL",
            "severity": "CRITICAL",
            "currentValue": "12000000.0",
            "limitValue": "10000000.0",
            "breachedAt": "2026-05-20T07:30:00Z",
            "resolvedAt": "2026-05-20T09:00:00Z",
        }
    ]


def _limits_response(book: str = _BOOK) -> list[dict[str, object]]:
    return [
        {
            "level": "BOOK",
            "entityId": book,
            "limitType": "NOTIONAL",
            "limitValue": "10000000.00",
            "intradayLimit": "8000000.00",
            "overnightLimit": None,
            "active": True,
        },
    ]


def _register_happy_path(fake: FakeKinetixHttpClient, book: str = _BOOK) -> None:
    """Seed all five tools' endpoints for one in-scope book."""

    fake.register_response(
        "GET", "risk-orchestrator", f"/api/v1/risk/var/{book}", _var_response(book)
    )
    fake.register_response(
        "GET",
        "risk-orchestrator",
        f"/api/v1/risk/pnl-attribution/{book}",
        _pnl_response(book),
    )
    fake.register_response(
        "GET", "position", f"/api/v1/books/{book}/limit-breaches", _breaches_response()
    )
    fake.register_response(
        "GET", "position", "/api/v1/limits", _limits_response(book)
    )


# ---------------------------------------------------------------------------
# Factory: DEMO_MODE resolution
# ---------------------------------------------------------------------------


def test_build_brief_client_returns_canned_when_demo_mode_true(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("DEMO_MODE", "true")

    client = build_brief_client(http=FakeKinetixHttpClient())

    assert isinstance(client, CannedBriefClient)


def test_demo_mode_is_case_insensitive(monkeypatch: pytest.MonkeyPatch) -> None:
    for value in ("TRUE", "True", "tRuE"):
        monkeypatch.setenv("DEMO_MODE", value)

        client = build_brief_client(http=FakeKinetixHttpClient())

        assert isinstance(client, CannedBriefClient)


def test_build_brief_client_returns_live_when_demo_mode_unset(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("DEMO_MODE", raising=False)

    client = build_brief_client(http=FakeKinetixHttpClient())

    assert isinstance(client, ClaudeAgentBriefClient)


def test_build_brief_client_falls_back_to_canned_on_live_construction_error(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("DEMO_MODE", raising=False)

    def _raise(*_args: object, **_kwargs: object) -> None:
        raise RuntimeError("boom")

    monkeypatch.setattr(brief_factory, "ClaudeAgentBriefClient", _raise)

    client = build_brief_client(http=FakeKinetixHttpClient())

    assert isinstance(client, CannedBriefClient)


# ---------------------------------------------------------------------------
# CannedBriefClient
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_canned_brief_client_returns_briefs_from_fixture() -> None:
    client = CannedBriefClient()

    briefs = await client.generate_brief(user=_USER)

    assert isinstance(briefs, list)
    assert briefs, "canned fixture must carry at least one brief"
    first = briefs[0]
    assert isinstance(first, MorningBrief)
    assert first.mode == "canned"
    assert first.sections, "the brief must carry sections"
    for section in first.sections:
        assert section.title
        assert section.sources, f"section {section.title!r} must carry a citation"


@pytest.mark.asyncio
async def test_canned_brief_client_fixture_round_trips_through_models() -> None:
    client = CannedBriefClient()

    briefs = await client.generate_brief(user=_USER)

    for brief in briefs:
        assert isinstance(brief, MorningBrief)
    first_section = briefs[0].sections[0]
    assert isinstance(first_section.sources[0], Citation)


def test_canned_brief_client_raises_on_missing_fixture() -> None:
    with pytest.raises((FileNotFoundError, OSError)):
        CannedBriefClient(fixture_path=Path("/nonexistent-demo-brief.json"))


@pytest.mark.asyncio
async def test_canned_brief_client_ignores_user() -> None:
    client = CannedBriefClient()
    user_a = UserContext(user_id="trader-a", books=("book-a",))
    user_b = UserContext(user_id="trader-b", books=("book-b", "book-c"))

    briefs_a = await client.generate_brief(user=user_a)
    briefs_b = await client.generate_brief(user=user_b)

    assert briefs_a == briefs_b


# ---------------------------------------------------------------------------
# Protocol conformance
# ---------------------------------------------------------------------------


def test_both_clients_satisfy_BriefClient_protocol() -> None:
    assert isinstance(CannedBriefClient(), BriefClient)
    assert isinstance(
        ClaudeAgentBriefClient(http=FakeKinetixHttpClient()), BriefClient
    )


# ---------------------------------------------------------------------------
# Live client wraps the generator
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_live_brief_client_generate_brief_returns_briefs() -> None:
    fake = FakeKinetixHttpClient()
    _register_happy_path(fake)
    client = ClaudeAgentBriefClient(http=fake, now=_fixed_now)

    briefs = await client.generate_brief(user=_USER)

    assert isinstance(briefs, list)
    assert [b.book_id for b in briefs] == [_BOOK]
    brief = briefs[0]
    assert isinstance(brief, MorningBrief)
    assert brief.mode == "live"
    assert len(brief.sections) == 5

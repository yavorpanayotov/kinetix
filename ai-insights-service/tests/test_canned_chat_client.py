"""Unit tests for the canned ``CopilotChatClient`` implementation.

These tests pin the protocol contract and the behaviour the chat
route handler will rely on:

* eager fixture load with fail-fast semantics
* deterministic transcript selection by SHA-256 of
  ``(message + "::" + page_context.page)``
* one ``ChatChunk`` per delta with the configured artificial delay,
  followed by exactly one terminal frame carrying ``done=True``,
  ``model``, ``mode``, and the parsed ``Citation`` list
* injectable delay (set to zero in tests so the suite stays fast)
* injectable fixtures directory so tests can own their corpus

All tests are deterministic and self-contained; each writes the
transcript JSON it needs into ``tmp_path``.
"""

from __future__ import annotations

import json
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import pytest

from kinetix_insights.chat import canned as canned_module
from kinetix_insights.chat.canned import (
    CannedCopilotChatClient,
    CopilotChatClient,
)
from kinetix_insights.chat.conversation_store import ConversationTurn
from kinetix_insights.chat.models import ChatRequest
from kinetix_insights.citations.models import Citation

pytestmark = pytest.mark.unit


# ---------------------------------------------------------------------------
# Fixture builders
# ---------------------------------------------------------------------------


_DEFAULT_CITATION: dict[str, Any] = {
    "tool": "get_book_var",
    "params": {"book_id": "fx-main"},
    "result_field": "total_var",
    "result_value": 5_200_000.0,
    "result_currency": "USD",
    "as_of_timestamp": "2026-05-19T08:00:00Z",
    "data_source": "risk-orchestrator",
    "freshness_seconds": 120,
    "quality_flags": [],
}

_SAMPLE_TOOL_CALLS: list[dict[str, Any]] = [
    {
        "name": "get_book_var",
        "params": {"book_id": "fx-main", "horizon_days": 1},
        "status": "ok",
        "started_at": "2026-05-19T08:00:00Z",
        "completed_at": "2026-05-19T08:00:00.250000Z",
    }
]


def _write_transcript(
    directory: Path,
    transcript_id: str,
    deltas: list[str],
    citations: list[dict[str, Any]] | None = None,
    tool_calls: list[dict[str, Any]] | None = None,
) -> Path:
    """Write a single transcript JSON file and return its path."""

    path = directory / f"{transcript_id}.json"
    payload: dict[str, Any] = {
        "id": transcript_id,
        "deltas": [{"text": text} for text in deltas],
        "citations": citations if citations is not None else [_DEFAULT_CITATION],
    }
    if tool_calls is not None:
        payload["tool_calls"] = tool_calls
    path.write_text(json.dumps(payload))
    return path


def _two_transcripts(directory: Path) -> tuple[Path, Path]:
    """Write two distinct transcripts. Returns ``(alpha, beta)`` paths."""

    alpha = _write_transcript(
        directory,
        "alpha",
        ["Alpha part one. ", "Alpha part two. ", "Alpha part three."],
    )
    beta = _write_transcript(
        directory,
        "beta",
        ["Beta opener. ", "Beta closer."],
    )
    return alpha, beta


# ---------------------------------------------------------------------------
# Protocol + loading
# ---------------------------------------------------------------------------


def test_implements_copilot_chat_client_protocol(tmp_path: Path) -> None:
    """The canned class is structurally a ``CopilotChatClient``."""

    _write_transcript(tmp_path, "only", ["hi"])
    client = CannedCopilotChatClient(fixtures_dir=tmp_path, delay_seconds=0.0)
    assert isinstance(client, CopilotChatClient)


def test_loads_transcripts_from_fixtures_dir(tmp_path: Path) -> None:
    """Constructing against a dir with valid transcripts does not raise."""

    _two_transcripts(tmp_path)
    CannedCopilotChatClient(fixtures_dir=tmp_path, delay_seconds=0.0)


def test_raises_value_error_when_no_transcripts(tmp_path: Path) -> None:
    """An empty fixtures directory is a fail-fast misconfiguration."""

    with pytest.raises(ValueError):
        CannedCopilotChatClient(fixtures_dir=tmp_path, delay_seconds=0.0)


# ---------------------------------------------------------------------------
# Streaming behaviour
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_chat_streams_one_chunk_per_delta_plus_final(tmp_path: Path) -> None:
    """A 3-delta transcript yields 4 chunks: 3 deltas + 1 terminal frame."""

    _write_transcript(tmp_path, "solo", ["a", "b", "c"])
    client = CannedCopilotChatClient(fixtures_dir=tmp_path, delay_seconds=0.0)
    request = ChatRequest(message="hello", page_context={"page": "dashboard"})

    chunks = [chunk async for chunk in client.chat(request)]

    assert len(chunks) == 4
    assert [c.done for c in chunks] == [False, False, False, True]


@pytest.mark.asyncio
async def test_chat_chunks_have_correct_delta_order(tmp_path: Path) -> None:
    """Deltas are streamed in the order they appear in the transcript."""

    _write_transcript(tmp_path, "solo", ["first ", "second ", "third"])
    client = CannedCopilotChatClient(fixtures_dir=tmp_path, delay_seconds=0.0)
    request = ChatRequest(message="hi", page_context={"page": "dashboard"})

    chunks = [chunk async for chunk in client.chat(request)]

    assert [c.delta for c in chunks[:-1]] == ["first ", "second ", "third"]


@pytest.mark.asyncio
async def test_chat_final_chunk_carries_citations_model_and_mode(
    tmp_path: Path,
) -> None:
    """The terminal frame stamps ``model``, ``mode``, and parsed citations."""

    _write_transcript(tmp_path, "solo", ["x"])
    client = CannedCopilotChatClient(fixtures_dir=tmp_path, delay_seconds=0.0)
    request = ChatRequest(message="hi", page_context={"page": "dashboard"})

    chunks = [chunk async for chunk in client.chat(request)]
    final = chunks[-1]

    assert final.done is True
    assert final.mode == "canned"
    assert final.model == "canned-chat"
    assert final.citations is not None
    assert len(final.citations) == 1
    assert isinstance(final.citations[0], Citation)
    assert final.citations[0].tool == "get_book_var"
    assert final.citations[0].result_value == 5_200_000.0
    assert final.citations[0].as_of_timestamp == datetime(
        2026, 5, 19, 8, 0, 0, tzinfo=timezone.utc
    )


@pytest.mark.asyncio
async def test_chat_final_chunk_has_no_delta(tmp_path: Path) -> None:
    """The terminal frame is metadata-only — ``delta`` is ``None``."""

    _write_transcript(tmp_path, "solo", ["hi"])
    client = CannedCopilotChatClient(fixtures_dir=tmp_path, delay_seconds=0.0)
    request = ChatRequest(message="hi", page_context={"page": "dashboard"})

    chunks = [chunk async for chunk in client.chat(request)]
    assert chunks[-1].delta is None


# ---------------------------------------------------------------------------
# Transcript selection
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_transcript_selection_is_deterministic_for_same_message_and_page(
    tmp_path: Path,
) -> None:
    """Calling ``chat`` twice with the same request streams identical deltas."""

    _two_transcripts(tmp_path)
    client = CannedCopilotChatClient(fixtures_dir=tmp_path, delay_seconds=0.0)
    request = ChatRequest(
        message="explain var", page_context={"page": "dashboard"}
    )

    first = [c.delta for c in [chunk async for chunk in client.chat(request)]]
    second = [c.delta for c in [chunk async for chunk in client.chat(request)]]

    assert first == second


@pytest.mark.asyncio
async def test_transcript_selection_varies_with_message(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Different messages can route to different transcripts.

    We force the bucketing helper to read straight off the message so
    the variance is independent of SHA-256 collisions at small N.
    """

    _two_transcripts(tmp_path)
    client = CannedCopilotChatClient(fixtures_dir=tmp_path, delay_seconds=0.0)

    def _by_message(message: str, _page: str, _modulus: int) -> int:
        return 0 if message.startswith("alpha") else 1

    monkeypatch.setattr(canned_module, "_select_transcript_index", _by_message)

    a_chunks = [
        chunk
        async for chunk in client.chat(
            ChatRequest(message="alpha please", page_context={"page": "p"})
        )
    ]
    b_chunks = [
        chunk
        async for chunk in client.chat(
            ChatRequest(message="beta please", page_context={"page": "p"})
        )
    ]

    a_deltas = [c.delta for c in a_chunks if c.delta is not None]
    b_deltas = [c.delta for c in b_chunks if c.delta is not None]
    assert a_deltas != b_deltas


@pytest.mark.asyncio
async def test_transcript_selection_varies_with_page_context_page(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Different ``page_context.page`` values can route to different transcripts."""

    _two_transcripts(tmp_path)
    client = CannedCopilotChatClient(fixtures_dir=tmp_path, delay_seconds=0.0)

    def _by_page(_message: str, page: str, _modulus: int) -> int:
        return 0 if page == "dashboard" else 1

    monkeypatch.setattr(canned_module, "_select_transcript_index", _by_page)

    dash = [
        chunk
        async for chunk in client.chat(
            ChatRequest(message="same", page_context={"page": "dashboard"})
        )
    ]
    book = [
        chunk
        async for chunk in client.chat(
            ChatRequest(message="same", page_context={"page": "book-detail"})
        )
    ]

    dash_deltas = [c.delta for c in dash if c.delta is not None]
    book_deltas = [c.delta for c in book if c.delta is not None]
    assert dash_deltas != book_deltas


@pytest.mark.asyncio
async def test_missing_page_context_page_treated_as_empty_string(
    tmp_path: Path,
) -> None:
    """A request with no ``page`` key in ``page_context`` is well-formed."""

    _two_transcripts(tmp_path)
    client = CannedCopilotChatClient(fixtures_dir=tmp_path, delay_seconds=0.0)
    request = ChatRequest(message="hello", page_context={})

    chunks = [chunk async for chunk in client.chat(request)]
    assert chunks[-1].done is True


# ---------------------------------------------------------------------------
# Delay injection
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_delay_seconds_is_zero_skips_sleep(tmp_path: Path) -> None:
    """``delay_seconds=0.0`` makes iteration effectively instantaneous."""

    _write_transcript(tmp_path, "solo", ["a", "b", "c", "d", "e"])
    client = CannedCopilotChatClient(fixtures_dir=tmp_path, delay_seconds=0.0)
    request = ChatRequest(message="hi", page_context={"page": "p"})

    started = time.perf_counter()
    [chunk async for chunk in client.chat(request)]
    elapsed = time.perf_counter() - started

    assert elapsed < 0.05


@pytest.mark.asyncio
async def test_delay_seconds_is_applied_between_deltas(tmp_path: Path) -> None:
    """A non-zero ``delay_seconds`` is paid once per delta (lower bound)."""

    _write_transcript(tmp_path, "solo", ["a", "b", "c"])
    client = CannedCopilotChatClient(fixtures_dir=tmp_path, delay_seconds=0.01)
    request = ChatRequest(message="hi", page_context={"page": "p"})

    started = time.perf_counter()
    [chunk async for chunk in client.chat(request)]
    elapsed = time.perf_counter() - started

    assert elapsed >= 0.03


# ---------------------------------------------------------------------------
# History argument
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_history_argument_is_accepted_but_ignored_by_canned(
    tmp_path: Path,
) -> None:
    """Passing ``history`` does not change streaming and does not raise."""

    _write_transcript(tmp_path, "solo", ["a", "b", "c"])
    client = CannedCopilotChatClient(fixtures_dir=tmp_path, delay_seconds=0.0)
    request = ChatRequest(message="hi", page_context={"page": "p"})

    without_history = [c.delta for c in [chunk async for chunk in client.chat(request)]]
    history = [
        ConversationTurn(
            role="user",
            content="prior turn",
            timestamp=datetime(2026, 5, 19, 7, 0, 0, tzinfo=timezone.utc),
        )
    ]
    with_history = [
        c.delta
        for c in [chunk async for chunk in client.chat(request, history=history)]
    ]

    assert without_history == with_history


# ---------------------------------------------------------------------------
# tool_calls field
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_chat_final_chunk_carries_tool_calls_when_present(
    tmp_path: Path,
) -> None:
    """The terminal frame includes tool_calls parsed from the transcript."""

    _write_transcript(tmp_path, "solo", ["x"], tool_calls=_SAMPLE_TOOL_CALLS)
    client = CannedCopilotChatClient(fixtures_dir=tmp_path, delay_seconds=0.0)
    request = ChatRequest(message="hi", page_context={"page": "dashboard"})

    chunks = [chunk async for chunk in client.chat(request)]
    final = chunks[-1]

    assert final.done is True
    assert final.tool_calls is not None
    assert len(final.tool_calls) == 1
    tc = final.tool_calls[0]
    assert tc.name == "get_book_var"
    assert tc.params == {"book_id": "fx-main", "horizon_days": 1}
    assert tc.status == "ok"


@pytest.mark.asyncio
async def test_chat_final_chunk_tool_calls_none_when_omitted(
    tmp_path: Path,
) -> None:
    """When the transcript has no tool_calls block, tool_calls is None on the terminal frame."""

    _write_transcript(tmp_path, "solo", ["x"])  # no tool_calls kwarg
    client = CannedCopilotChatClient(fixtures_dir=tmp_path, delay_seconds=0.0)
    request = ChatRequest(message="hi", page_context={"page": "dashboard"})

    chunks = [chunk async for chunk in client.chat(request)]
    final = chunks[-1]

    assert final.tool_calls is None


@pytest.mark.asyncio
async def test_chat_final_chunk_tool_calls_none_for_empty_list(
    tmp_path: Path,
) -> None:
    """An explicit empty tool_calls list in the transcript yields None on the frame."""

    _write_transcript(tmp_path, "solo", ["x"], tool_calls=[])
    client = CannedCopilotChatClient(fixtures_dir=tmp_path, delay_seconds=0.0)
    request = ChatRequest(message="hi", page_context={"page": "dashboard"})

    chunks = [chunk async for chunk in client.chat(request)]
    final = chunks[-1]

    assert final.tool_calls is None


@pytest.mark.asyncio
async def test_chat_final_chunk_tool_calls_multiple_entries(
    tmp_path: Path,
) -> None:
    """Multiple tool_call entries in the fixture are all present on the terminal frame."""

    multi_tool_calls: list[dict[str, Any]] = [
        {
            "name": "get_book_var",
            "params": {"book_id": "fx-main"},
            "status": "ok",
            "started_at": "2026-05-19T08:00:00Z",
            "completed_at": "2026-05-19T08:00:00.200000Z",
        },
        {
            "name": "get_greeks_summary",
            "params": {"book_id": "fx-main"},
            "status": "ok",
            "started_at": "2026-05-19T08:00:00.200000Z",
            "completed_at": "2026-05-19T08:00:00.450000Z",
        },
    ]
    _write_transcript(tmp_path, "solo", ["x"], tool_calls=multi_tool_calls)
    client = CannedCopilotChatClient(fixtures_dir=tmp_path, delay_seconds=0.0)
    request = ChatRequest(message="hi", page_context={"page": "dashboard"})

    chunks = [chunk async for chunk in client.chat(request)]
    final = chunks[-1]

    assert final.tool_calls is not None
    assert len(final.tool_calls) == 2
    assert [tc.name for tc in final.tool_calls] == ["get_book_var", "get_greeks_summary"]


# ---------------------------------------------------------------------------
# Saved-query fixture coverage
#
# These tests use the real production fixtures directory so they prove that
# demo viewers who click any of the 5 built-in saved-query chips get a
# believable response — non-empty tool_calls and at least one citation with
# freshness_seconds < 60 (so the UI urgency badge shows green or amber, not
# the stale-red that would undermine trust in the demo).
#
# Each test renders the query's prompt_template with canonical demo params
# (book_id="fx-main", top_n=5 where required), builds a
# CannedCopilotChatClient against the production fixtures directory, and
# streams the chat response.  The production fixtures dir is deterministic
# and the hash-based routing is stable, so the transcript selection is
# repeatable across machines.
# ---------------------------------------------------------------------------

_PRODUCTION_FIXTURES_DIR = (
    Path(__file__).resolve().parent.parent
    / "src"
    / "kinetix_insights"
    / "fixtures"
    / "chat_transcripts"
)

# Rendered prompts for the 5 built-in saved queries.  The hash of
# (prompt + "::" + page) determines which transcript is selected; page is
# always "" for saved-query runs because page_context carries only
# {"source": "saved-query", "query_id": ...} with no "page" key.
_LIMIT_BREACHES_PROMPT = (
    "List every risk limit on book fx-main that is in breach today. "
    "For each breach, state the limit name, the current utilisation, the limit "
    "threshold, and the time the breach was first recorded. "
    "Report only the figures from the book's own limit data."
)
_PNL_VS_YESTERDAY_PROMPT = (
    "State the current-day P&L for book fx-main and compare it to the prior "
    "session's closing P&L. Report the absolute change and the percentage change, "
    "and break the move down by the largest contributing instruments. "
    "Quote only the figures from the book's own P&L attribution data."
)
_VAR_WEEK_DRIVERS_PROMPT = (
    "State the current 95% Value-at-Risk for book fx-main and how it has changed "
    "over the past week. Identify the risk factors and positions that drove the "
    "change, quoting the contribution figures for each. "
    "Report only the numbers from the book's own VaR results."
)
_TOP_POSITIONS_PROMPT = (
    "List the top 5 positions on book fx-main ranked by their contribution to "
    "total portfolio risk. For each position, state the instrument, the notional "
    "or market value, and its risk contribution figure. "
    "Report only the figures from the book's own risk results."
)
_VOL_DISLOCATIONS_PROMPT = (
    "Identify the instruments held on book fx-main where the implied volatility "
    "has moved most against its recent range. For each, state the instrument, the "
    "current implied volatility, and the size of the move. "
    "Report only the figures from the book's own positions and volatility surface data."
)


async def _terminal_chunk_for_prompt(prompt: str) -> "ChatChunk":  # type: ignore[name-defined]
    """Stream the production canned client with ``prompt`` and return the terminal chunk."""
    from kinetix_insights.chat.models import ChatChunk as _ChatChunk

    client = CannedCopilotChatClient(
        fixtures_dir=_PRODUCTION_FIXTURES_DIR, delay_seconds=0.0
    )
    request = ChatRequest(message=prompt, page_context={})
    chunks: list[_ChatChunk] = [chunk async for chunk in client.chat(request)]
    return chunks[-1]


@pytest.mark.asyncio
async def test_limit_breaches_saved_query_routes_to_transcript_with_tool_calls() -> None:
    """The limit-breaches saved-query prompt maps to a transcript with non-empty tool_calls."""

    final = await _terminal_chunk_for_prompt(_LIMIT_BREACHES_PROMPT)

    assert final.done is True
    assert final.tool_calls is not None, (
        "limit-breaches transcript must carry tool_calls for the reasoning panel"
    )
    assert len(final.tool_calls) >= 1


@pytest.mark.asyncio
async def test_limit_breaches_saved_query_has_at_least_one_fresh_citation() -> None:
    """The limit-breaches transcript has at least one citation with freshness_seconds < 60."""

    final = await _terminal_chunk_for_prompt(_LIMIT_BREACHES_PROMPT)

    assert final.citations is not None
    fresh = [c for c in final.citations if c.freshness_seconds < 60]
    assert fresh, (
        f"limit-breaches transcript must have at least one fresh citation "
        f"(freshness_seconds < 60); got {[c.freshness_seconds for c in final.citations]}"
    )


@pytest.mark.asyncio
async def test_pnl_vs_yesterday_saved_query_routes_to_transcript_with_tool_calls() -> None:
    """The pnl-vs-yesterday saved-query prompt maps to a transcript with non-empty tool_calls."""

    final = await _terminal_chunk_for_prompt(_PNL_VS_YESTERDAY_PROMPT)

    assert final.done is True
    assert final.tool_calls is not None, (
        "pnl-vs-yesterday transcript must carry tool_calls for the reasoning panel"
    )
    assert len(final.tool_calls) >= 1


@pytest.mark.asyncio
async def test_pnl_vs_yesterday_saved_query_has_at_least_one_fresh_citation() -> None:
    """The pnl-vs-yesterday transcript has at least one citation with freshness_seconds < 60."""

    final = await _terminal_chunk_for_prompt(_PNL_VS_YESTERDAY_PROMPT)

    assert final.citations is not None
    fresh = [c for c in final.citations if c.freshness_seconds < 60]
    assert fresh, (
        f"pnl-vs-yesterday transcript must have at least one fresh citation "
        f"(freshness_seconds < 60); got {[c.freshness_seconds for c in final.citations]}"
    )


@pytest.mark.asyncio
async def test_var_week_drivers_saved_query_routes_to_transcript_with_tool_calls() -> None:
    """The var-week-drivers saved-query prompt maps to a transcript with non-empty tool_calls."""

    final = await _terminal_chunk_for_prompt(_VAR_WEEK_DRIVERS_PROMPT)

    assert final.done is True
    assert final.tool_calls is not None, (
        "var-week-drivers transcript must carry tool_calls for the reasoning panel"
    )
    assert len(final.tool_calls) >= 1


@pytest.mark.asyncio
async def test_var_week_drivers_saved_query_has_at_least_one_fresh_citation() -> None:
    """The var-week-drivers transcript has at least one citation with freshness_seconds < 60."""

    final = await _terminal_chunk_for_prompt(_VAR_WEEK_DRIVERS_PROMPT)

    assert final.citations is not None
    fresh = [c for c in final.citations if c.freshness_seconds < 60]
    assert fresh, (
        f"var-week-drivers transcript must have at least one fresh citation "
        f"(freshness_seconds < 60); got {[c.freshness_seconds for c in final.citations]}"
    )


@pytest.mark.asyncio
async def test_top_positions_risk_contribution_saved_query_routes_to_transcript_with_tool_calls() -> None:
    """The top-positions-risk-contribution query maps to a transcript with non-empty tool_calls."""

    final = await _terminal_chunk_for_prompt(_TOP_POSITIONS_PROMPT)

    assert final.done is True
    assert final.tool_calls is not None, (
        "top-positions-risk-contribution transcript must carry tool_calls for the reasoning panel"
    )
    assert len(final.tool_calls) >= 1


@pytest.mark.asyncio
async def test_top_positions_risk_contribution_saved_query_has_at_least_one_fresh_citation() -> None:
    """The top-positions transcript has at least one citation with freshness_seconds < 60."""

    final = await _terminal_chunk_for_prompt(_TOP_POSITIONS_PROMPT)

    assert final.citations is not None
    fresh = [c for c in final.citations if c.freshness_seconds < 60]
    assert fresh, (
        f"top-positions-risk-contribution transcript must have at least one fresh citation "
        f"(freshness_seconds < 60); got {[c.freshness_seconds for c in final.citations]}"
    )


@pytest.mark.asyncio
async def test_vol_dislocations_saved_query_routes_to_transcript_with_tool_calls() -> None:
    """The vol-dislocations saved-query prompt maps to a transcript with non-empty tool_calls."""

    final = await _terminal_chunk_for_prompt(_VOL_DISLOCATIONS_PROMPT)

    assert final.done is True
    assert final.tool_calls is not None, (
        "vol-dislocations transcript must carry tool_calls for the reasoning panel"
    )
    assert len(final.tool_calls) >= 1


@pytest.mark.asyncio
async def test_vol_dislocations_saved_query_has_at_least_one_fresh_citation() -> None:
    """The vol-dislocations transcript has at least one citation with freshness_seconds < 60."""

    final = await _terminal_chunk_for_prompt(_VOL_DISLOCATIONS_PROMPT)

    assert final.citations is not None
    fresh = [c for c in final.citations if c.freshness_seconds < 60]
    assert fresh, (
        f"vol-dislocations transcript must have at least one fresh citation "
        f"(freshness_seconds < 60); got {[c.freshness_seconds for c in final.citations]}"
    )

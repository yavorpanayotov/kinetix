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


def _write_transcript(
    directory: Path,
    transcript_id: str,
    deltas: list[str],
    citations: list[dict[str, Any]] | None = None,
) -> Path:
    """Write a single transcript JSON file and return its path."""

    path = directory / f"{transcript_id}.json"
    payload = {
        "id": transcript_id,
        "deltas": [{"text": text} for text in deltas],
        "citations": citations if citations is not None else [_DEFAULT_CITATION],
    }
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

    def _by_message(message: str, page: str, modulus: int) -> int:
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

    def _by_page(message: str, page: str, modulus: int) -> int:
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

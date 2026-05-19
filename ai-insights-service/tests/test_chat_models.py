"""Unit tests for the ChatRequest / ChatChunk pydantic models.

These tests pin down the JSON contract for the conversational chat
endpoint: `ChatRequest` is the inbound POST body and `ChatChunk` is
the per-frame outbound payload streamed back over Server-Sent Events.
The tests guarantee round-trip stability, default values, required
fields, and the invariant that error frames are always terminal.
"""

from datetime import datetime, timezone

import pytest
from pydantic import ValidationError

from kinetix_insights.chat.models import ChatChunk, ChatRequest
from kinetix_insights.citations.models import Citation

pytestmark = pytest.mark.unit


def _make_citation(**overrides: object) -> Citation:
    """Build a Citation with sensible defaults for chat-frame tests."""
    base: dict[str, object] = {
        "tool": "get_book_var",
        "params": {"book_id": "MAIN", "horizon_days": 1},
        "result_field": "total_var",
        "result_value": 5_200_000.0,
        "result_currency": "USD",
        "as_of_timestamp": datetime(2026, 5, 19, 12, 0, 0, tzinfo=timezone.utc),
        "data_source": "risk-orchestrator",
        "freshness_seconds": 42,
    }
    base.update(overrides)
    return Citation(**base)  # type: ignore[arg-type]


# ---------------------------------------------------------------------------
# ChatRequest
# ---------------------------------------------------------------------------


def test_chat_request_round_trip() -> None:
    """A fully populated ChatRequest survives a JSON dump/parse cycle."""
    original = ChatRequest(
        message="What drove VaR up today?",
        page_context={"page": "var-dashboard", "book_id": "fx-main"},
        session_id="sess-123",
        conversation_id="conv-456",
    )
    parsed = ChatRequest.model_validate_json(original.model_dump_json())
    assert parsed == original


def test_chat_request_minimum_required_message_only() -> None:
    """With only `message` supplied the other fields take their defaults."""
    request = ChatRequest(message="Hello")
    assert request.message == "Hello"
    assert request.page_context == {}
    assert request.session_id is None
    assert request.conversation_id is None


def test_chat_request_rejects_empty_message() -> None:
    """An empty string for `message` raises ValidationError."""
    with pytest.raises(ValidationError):
        ChatRequest(message="")


def test_chat_request_rejects_whitespace_only_message() -> None:
    """A whitespace-only string for `message` raises ValidationError."""
    with pytest.raises(ValidationError):
        ChatRequest(message="   ")


def test_chat_request_rejects_missing_message() -> None:
    """Omitting `message` raises ValidationError."""
    with pytest.raises(ValidationError):
        ChatRequest()  # type: ignore[call-arg]


def test_chat_request_allows_arbitrary_page_context_shape() -> None:
    """`page_context` is free-form and survives nested/mixed-type round trips."""
    original = ChatRequest(
        message="Why did limit utilisation spike?",
        page_context={
            "page": "var",
            "selections": {"book": "fx-main", "as_of": "2026-05-19"},
            "limit": 5,
        },
    )
    parsed = ChatRequest.model_validate_json(original.model_dump_json())
    assert parsed == original
    assert parsed.page_context == {
        "page": "var",
        "selections": {"book": "fx-main", "as_of": "2026-05-19"},
        "limit": 5,
    }


# ---------------------------------------------------------------------------
# ChatChunk
# ---------------------------------------------------------------------------


def test_chat_chunk_minimum_done_only() -> None:
    """`ChatChunk(done=False)` is valid; all optional fields default to None."""
    chunk = ChatChunk(done=False)
    assert chunk.done is False
    assert chunk.delta is None
    assert chunk.citations is None
    assert chunk.model is None
    assert chunk.mode is None
    assert chunk.error_code is None


def test_chat_chunk_rejects_missing_done() -> None:
    """Omitting `done` raises ValidationError."""
    with pytest.raises(ValidationError):
        ChatChunk()  # type: ignore[call-arg]


def test_chat_chunk_with_delta() -> None:
    """A streaming text chunk round-trips through JSON unchanged."""
    original = ChatChunk(delta="hello", done=False)
    parsed = ChatChunk.model_validate_json(original.model_dump_json())
    assert parsed == original
    assert parsed.delta == "hello"
    assert parsed.done is False


def test_chat_chunk_with_citations_round_trip() -> None:
    """A chunk carrying a Citation round-trips with every citation field preserved."""
    citation = _make_citation(quality_flags=["STALE_PRICE"])
    original = ChatChunk(done=True, citations=[citation])
    parsed = ChatChunk.model_validate_json(original.model_dump_json())
    assert parsed == original
    assert parsed.citations is not None
    assert len(parsed.citations) == 1
    assert parsed.citations[0] == citation


def test_chat_chunk_terminal_frame_with_model_and_mode() -> None:
    """The final chunk stamps `model` and `mode` and round-trips intact."""
    original = ChatChunk(done=True, model="claude-opus-4-7", mode="canned")
    parsed = ChatChunk.model_validate_json(original.model_dump_json())
    assert parsed == original
    assert parsed.done is True
    assert parsed.model == "claude-opus-4-7"
    assert parsed.mode == "canned"


def test_chat_chunk_error_frame_requires_done_true() -> None:
    """An error frame must be terminal — `done` must be True when `error_code` is set."""
    with pytest.raises(ValidationError):
        ChatChunk(done=False, error_code="POLICY_VIOLATION")
    chunk = ChatChunk(done=True, error_code="POLICY_VIOLATION")
    assert chunk.done is True
    assert chunk.error_code == "POLICY_VIOLATION"

"""Unit tests for :class:`ClaudeAgentCopilotChatClient`.

These tests pin the contract that the live SDK-backed chat client
must satisfy (see plan ``ai-v2.md`` §3.5):

* streams ``ChatChunk`` deltas as the SDK yields text messages,
* runs ``check_narrative`` (policy guard) and ``find_uncited_tokens``
  (citation verifier) as terminal safety nets, with policy taking
  precedence over the citation check,
* threads conversation history into the SDK prompt, sourcing it from
  the explicit ``history`` argument when provided and otherwise from
  the :class:`ConversationStore`,
* persists user/assistant turns ONLY on a clean terminal frame, and
* never re-raises from the async generator — SDK failures surface as
  a single terminal ``UPSTREAM_ERROR`` frame so callers can rely on
  the iterator closing cleanly.

The shared ``_FakeStreamingSdk`` is used for most paths. Citation
attachment is exercised by passing :class:`FakeMessage` instances
with a ``citations`` tuple — the shared fake propagates that tuple
onto the yielded ``_FakeMessage`` so the client's extraction helper
can read it.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

import pytest

from kinetix_insights.chat.canned import CopilotChatClient
from kinetix_insights.chat.claude_agent_chat_client import (
    ClaudeAgentCopilotChatClient,
)
from kinetix_insights.chat.conversation_store import (
    ConversationTurn,
    InMemoryConversationStore,
)
from kinetix_insights.chat.models import ChatRequest
from kinetix_insights.citations.models import Citation
from tests.fakes.streaming_sdk import (
    FakeMessage,
    FakeSdkError,
    _FakeStreamingSdk,
)

pytestmark = pytest.mark.unit


# ---------------------------------------------------------------------------
# Test fixtures
# ---------------------------------------------------------------------------


def _matching_citation(value: float) -> Citation:
    """Build a Citation whose ``result_value`` matches a narrative token."""

    return Citation(
        tool="get_book_var",
        params={"book_id": "fx-main"},
        result_field="total_var",
        result_value=value,
        result_currency="USD",
        as_of_timestamp=datetime(2026, 5, 19, 8, 0, 0, tzinfo=timezone.utc),
        data_source="risk-orchestrator",
        freshness_seconds=120,
        quality_flags=[],
    )


def _request(message: str = "explain var", **overrides: Any) -> ChatRequest:
    """Build a ChatRequest with sensible defaults; overrides win."""

    kwargs: dict[str, Any] = {
        "message": message,
        "page_context": {"page": "dashboard"},
    }
    kwargs.update(overrides)
    return ChatRequest(**kwargs)


# ---------------------------------------------------------------------------
# Streaming behaviour
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_chat_yields_delta_per_sdk_message() -> None:
    """One ChatChunk per SDK text message, plus exactly one terminal frame."""

    sdk = _FakeStreamingSdk(
        messages=[
            FakeMessage(content="alpha "),
            FakeMessage(content="beta "),
            FakeMessage(content="gamma"),
        ]
    )
    store = InMemoryConversationStore()
    client = ClaudeAgentCopilotChatClient(conversation_store=store, sdk=sdk)

    chunks = [chunk async for chunk in client.chat(_request())]

    deltas = [c.delta for c in chunks if not c.done]
    assert deltas == ["alpha ", "beta ", "gamma"]
    assert sum(1 for c in chunks if c.done) == 1


@pytest.mark.asyncio
async def test_chat_yields_terminal_frame_with_model_and_mode() -> None:
    """The terminal frame stamps the configured model and ``mode='live'``."""

    sdk = _FakeStreamingSdk(messages=[FakeMessage(content="hi")])
    store = InMemoryConversationStore()
    client = ClaudeAgentCopilotChatClient(conversation_store=store, sdk=sdk)

    chunks = [chunk async for chunk in client.chat(_request())]
    final = chunks[-1]

    assert final.done is True
    assert final.model == "claude-opus-4-7"
    assert final.mode == "live"


@pytest.mark.asyncio
async def test_chat_clean_narrative_emits_no_error_code() -> None:
    """A citation matching every numeric token yields a clean terminal frame."""

    # The verifier compares numeric tokens against citation values at face
    # value (no unit suffix interpretation), so the citation must mirror
    # the literal token the narrative emits.
    citation = _matching_citation(5.2)
    sdk = _FakeStreamingSdk(
        messages=[FakeMessage(content="VaR is $5.2M", citations=(citation,))]
    )
    store = InMemoryConversationStore()
    client = ClaudeAgentCopilotChatClient(conversation_store=store, sdk=sdk)

    chunks = [chunk async for chunk in client.chat(_request())]
    final = chunks[-1]

    assert final.done is True
    assert final.error_code is None


@pytest.mark.asyncio
async def test_chat_uncited_token_surfaces_citation_unverifiable() -> None:
    """A numeric token without a matching citation surfaces the error code."""

    citation = _matching_citation(5_200_000.0)
    sdk = _FakeStreamingSdk(
        messages=[FakeMessage(content="VaR is $9.99M", citations=(citation,))]
    )
    store = InMemoryConversationStore()
    client = ClaudeAgentCopilotChatClient(conversation_store=store, sdk=sdk)

    chunks = [chunk async for chunk in client.chat(_request())]
    final = chunks[-1]

    assert final.done is True
    assert final.error_code == "CITATION_UNVERIFIABLE"
    assert final.citations is not None
    assert final.citations == [citation]


@pytest.mark.asyncio
async def test_chat_policy_violation_short_circuits() -> None:
    """A banned phrase emits POLICY_VIOLATION; citations are NOT attached."""

    citation = _matching_citation(5_200_000.0)
    sdk = _FakeStreamingSdk(
        messages=[
            FakeMessage(
                content="you should hedge the $5.2M exposure",
                citations=(citation,),
            )
        ]
    )
    store = InMemoryConversationStore()
    client = ClaudeAgentCopilotChatClient(conversation_store=store, sdk=sdk)

    chunks = [chunk async for chunk in client.chat(_request())]
    final = chunks[-1]

    assert final.done is True
    assert final.error_code == "POLICY_VIOLATION"
    assert final.citations is None


@pytest.mark.asyncio
async def test_chat_policy_violation_prevents_persistence() -> None:
    """Policy-violating turns are NOT stored, so the next call starts clean."""

    sdk = _FakeStreamingSdk(
        messages=[FakeMessage(content="you should hedge now")]
    )
    store = InMemoryConversationStore()
    client = ClaudeAgentCopilotChatClient(conversation_store=store, sdk=sdk)

    [chunk async for chunk in client.chat(_request(conversation_id="conv-1"))]

    assert await store.get("conv-1") == []


@pytest.mark.asyncio
async def test_chat_uncited_prevents_persistence() -> None:
    """Citation-unverifiable turns are NOT stored either."""

    citation = _matching_citation(5_200_000.0)
    sdk = _FakeStreamingSdk(
        messages=[FakeMessage(content="VaR is $9.99M", citations=(citation,))]
    )
    store = InMemoryConversationStore()
    client = ClaudeAgentCopilotChatClient(conversation_store=store, sdk=sdk)

    [chunk async for chunk in client.chat(_request(conversation_id="conv-1"))]

    assert await store.get("conv-1") == []


@pytest.mark.asyncio
async def test_chat_clean_response_persists_user_and_assistant_turns() -> None:
    """Clean terminal frames persist exactly two turns: user, then assistant."""

    citation = _matching_citation(5.2)
    sdk = _FakeStreamingSdk(
        messages=[FakeMessage(content="VaR is $5.2M", citations=(citation,))]
    )
    store = InMemoryConversationStore()
    client = ClaudeAgentCopilotChatClient(conversation_store=store, sdk=sdk)

    [
        chunk
        async for chunk in client.chat(
            _request(message="what is var?", conversation_id="conv-1")
        )
    ]

    turns = await store.get("conv-1")
    assert len(turns) == 2
    assert turns[0].role == "user"
    assert turns[0].content == "what is var?"
    assert turns[1].role == "assistant"
    assert turns[1].content == "VaR is $5.2M"


@pytest.mark.asyncio
async def test_chat_omits_persistence_when_no_conversation_id() -> None:
    """No conversation_id means no persistence, regardless of cleanliness."""

    citation = _matching_citation(5.2)
    sdk = _FakeStreamingSdk(
        messages=[FakeMessage(content="VaR is $5.2M", citations=(citation,))]
    )
    store = InMemoryConversationStore()
    client = ClaudeAgentCopilotChatClient(conversation_store=store, sdk=sdk)

    [chunk async for chunk in client.chat(_request())]

    # Empty store across any potential keying — no turns persisted.
    assert await store.get("") == []
    assert await store.get("conv-1") == []


@pytest.mark.asyncio
async def test_chat_uses_history_argument_when_supplied() -> None:
    """Explicit ``history=[...]`` becomes a prefix on the SDK prompt."""

    sdk = _FakeStreamingSdk(messages=[FakeMessage(content="ok")])
    store = InMemoryConversationStore()
    client = ClaudeAgentCopilotChatClient(conversation_store=store, sdk=sdk)
    history = [
        ConversationTurn(
            role="user",
            content="prior question",
            timestamp=datetime(2026, 5, 19, 7, 0, 0, tzinfo=timezone.utc),
        )
    ]

    [
        chunk
        async for chunk in client.chat(
            _request(message="follow-up"), history=history
        )
    ]

    assert sdk.recorded_prompts, "SDK was never invoked"
    prompt = sdk.recorded_prompts[0]
    assert "prior question" in prompt
    assert prompt.index("prior question") < prompt.index("follow-up")


@pytest.mark.asyncio
async def test_chat_uses_conversation_store_history_when_argument_absent() -> None:
    """When ``history`` is omitted, the store's contents thread into the prompt."""

    sdk = _FakeStreamingSdk(messages=[FakeMessage(content="ok")])
    store = InMemoryConversationStore()
    await store.append(
        "conv-1",
        ConversationTurn(
            role="user",
            content="earlier user turn",
            timestamp=datetime(2026, 5, 19, 7, 0, 0, tzinfo=timezone.utc),
        ),
    )
    await store.append(
        "conv-1",
        ConversationTurn(
            role="assistant",
            content="earlier assistant turn",
            timestamp=datetime(2026, 5, 19, 7, 0, 1, tzinfo=timezone.utc),
        ),
    )
    client = ClaudeAgentCopilotChatClient(conversation_store=store, sdk=sdk)

    [
        chunk
        async for chunk in client.chat(
            _request(message="next question", conversation_id="conv-1")
        )
    ]

    prompt = sdk.recorded_prompts[0]
    assert "earlier user turn" in prompt
    assert "earlier assistant turn" in prompt
    assert prompt.index("earlier user turn") < prompt.index("earlier assistant turn")


@pytest.mark.asyncio
async def test_chat_history_argument_overrides_conversation_store() -> None:
    """When both are supplied, the argument wins and the store is ignored."""

    sdk = _FakeStreamingSdk(messages=[FakeMessage(content="ok")])
    store = InMemoryConversationStore()
    await store.append(
        "conv-1",
        ConversationTurn(
            role="user",
            content="store-only-turn",
            timestamp=datetime(2026, 5, 19, 7, 0, 0, tzinfo=timezone.utc),
        ),
    )
    client = ClaudeAgentCopilotChatClient(conversation_store=store, sdk=sdk)
    history = [
        ConversationTurn(
            role="user",
            content="argument-only-turn",
            timestamp=datetime(2026, 5, 19, 7, 30, 0, tzinfo=timezone.utc),
        )
    ]

    [
        chunk
        async for chunk in client.chat(
            _request(message="q", conversation_id="conv-1"), history=history
        )
    ]

    prompt = sdk.recorded_prompts[0]
    assert "argument-only-turn" in prompt
    assert "store-only-turn" not in prompt


@pytest.mark.asyncio
async def test_chat_yields_upstream_error_when_sdk_raises_on_iteration() -> None:
    """A transport-style SDK failure becomes one UPSTREAM_ERROR terminal frame."""

    sdk = _FakeStreamingSdk(messages=[FakeMessage(content="never")])
    sdk.raise_on_next_call(FakeSdkError("boom"))
    store = InMemoryConversationStore()
    client = ClaudeAgentCopilotChatClient(conversation_store=store, sdk=sdk)

    chunks = [chunk async for chunk in client.chat(_request())]

    assert len(chunks) == 1
    final = chunks[0]
    assert final.done is True
    assert final.error_code == "UPSTREAM_ERROR"
    assert final.mode == "live"
    assert final.model == "claude-opus-4-7"


@pytest.mark.asyncio
async def test_chat_yields_upstream_error_when_sdk_import_fails(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """An ImportError during lazy SDK resolution becomes UPSTREAM_ERROR."""

    import builtins

    real_import = builtins.__import__

    def _failing_import(name: str, *args: Any, **kwargs: Any) -> Any:
        if name == "claude_agent_sdk":
            raise ImportError("simulated missing SDK")
        return real_import(name, *args, **kwargs)

    monkeypatch.setattr(builtins, "__import__", _failing_import)
    store = InMemoryConversationStore()
    client = ClaudeAgentCopilotChatClient(conversation_store=store, sdk=None)

    chunks = [chunk async for chunk in client.chat(_request())]

    assert len(chunks) == 1
    final = chunks[0]
    assert final.done is True
    assert final.error_code == "UPSTREAM_ERROR"


@pytest.mark.asyncio
async def test_chat_extracts_citations_from_message_attribute() -> None:
    """Citations attached directly to messages are forwarded on the terminal frame."""

    citation = _matching_citation(5.2)
    sdk = _FakeStreamingSdk(
        messages=[FakeMessage(content="VaR is $5.2M", citations=(citation,))]
    )
    store = InMemoryConversationStore()
    client = ClaudeAgentCopilotChatClient(conversation_store=store, sdk=sdk)

    chunks = [chunk async for chunk in client.chat(_request())]
    final = chunks[-1]

    assert final.citations == [citation]


def test_chat_implements_copilot_chat_client_protocol() -> None:
    """The live client structurally satisfies the chat protocol."""

    store = InMemoryConversationStore()
    sdk = _FakeStreamingSdk(messages=[])
    client = ClaudeAgentCopilotChatClient(conversation_store=store, sdk=sdk)
    assert isinstance(client, CopilotChatClient)


# ---------------------------------------------------------------------------
# Citation extraction via content blocks (defensive fallback path)
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class _BlockWithCitations:
    text: str
    citations: list[Citation]


@dataclass(frozen=True)
class _MessageWithBlockCitations:
    """A custom message whose citations live on a content block, not the top level.

    Mirrors a shape some SDK builds emit; ensures the client falls back to
    scanning ``content`` blocks when the top-level ``.citations`` attribute
    is absent.
    """

    text: str

    @property
    def content(self) -> list[Any]:
        return [
            _BlockWithCitations(text=self.text, citations=[_matching_citation(5.2)])
        ]


class _BlockCitationSdk:
    """One-off SDK fake that yields a message with block-level citations."""

    def __init__(self, message_text: str) -> None:
        self._message_text = message_text
        self.recorded_prompts: list[str] = []

    def query(self, *, prompt: str, **kwargs: Any) -> Any:  # noqa: ARG002
        self.recorded_prompts.append(prompt)
        return self._stream()

    async def _stream(self) -> Any:
        yield _MessageWithBlockCitations(text=self._message_text)


@pytest.mark.asyncio
async def test_chat_extracts_citations_from_content_blocks() -> None:
    """Citations on content blocks are picked up via the fallback path."""

    sdk = _BlockCitationSdk(message_text="VaR is $5.2M")
    store = InMemoryConversationStore()
    client = ClaudeAgentCopilotChatClient(conversation_store=store, sdk=sdk)

    chunks = [chunk async for chunk in client.chat(_request())]
    final = chunks[-1]

    assert final.error_code is None
    assert final.citations is not None
    assert len(final.citations) == 1
    assert final.citations[0].result_value == 5.2

"""The canned :class:`CopilotChatClient` — fixture-driven SSE replay.

This client backs the chat endpoint in canned mode (v2 default until
the live Claude SDK variant lands in plan §3.5). It loads a directory
of transcript JSON files at construction time, deterministically picks
one per request based on the SHA-256 of ``(message + "::" + page)``,
and replays its deltas as :class:`ChatChunk` frames separated by a
configurable artificial delay. The final frame stamps ``done=True``,
``mode="canned"``, ``model="canned-chat"``, and the citations parsed
from the transcript.

The protocol :class:`CopilotChatClient` is intentionally narrow: a
single ``chat`` method returning an :class:`AsyncIterator`. Both this
canned implementation and the upcoming SDK-backed live variant satisfy
it, so the route handler depends on the protocol and swaps the
concrete client per environment.

Design notes:

* **Eager loading, fail fast.** All transcripts are parsed at init
  time; a malformed JSON file surfaces immediately as a stack trace
  rather than poisoning a single request later. An empty fixtures
  directory raises ``ValueError`` — there is no narrative fallback,
  because silent degradation is worse than a startup failure.
* **Deterministic selection.** Transcripts are sorted by ``id`` after
  load so the modulus bucketing is stable across runs and machines.
  The bucketing helper is module-level so tests can monkeypatch it to
  exercise variance without depending on hash collisions at small N.
* **Injectable delay and fixtures dir.** Tests pass
  ``delay_seconds=0.0`` and a ``tmp_path`` so they own the transcript
  corpus and never sleep. Production callers get the canonical
  fixtures dir and the 20 ms default that gives the UI a streamed
  feel.
"""

from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator
from pathlib import Path
from typing import Any, Protocol, runtime_checkable

from kinetix_insights.chat.conversation_store import ConversationTurn
from kinetix_insights.chat.intent_router import UNMATCHED, route_intent
from kinetix_insights.chat.models import ChatChunk, ChatRequest, ToolCall
from kinetix_insights.citations.models import Citation


_DEFAULT_DELAY_SECONDS = 0.020
_DEFAULT_FIXTURES_DIR = Path(__file__).resolve().parent.parent / "fixtures" / "chat_transcripts"


@runtime_checkable
class CopilotChatClient(Protocol):
    """Streams chat responses as ``ChatChunk`` frames.

    Implementations may be canned (fixture-driven) or live (Claude
    SDK). The route handler iterates the async generator and forwards
    each chunk to the client as an SSE frame.

    ``chat`` is *not* itself an async function — it returns an
    :class:`AsyncIterator` so callers consume it with
    ``async for chunk in client.chat(req)``. That keeps the contract
    identical whether the implementation backs the iterator with a
    generator (canned) or with a network stream (live).
    """

    def chat(
        self,
        request: ChatRequest,
        *,
        history: list[ConversationTurn] | None = None,
    ) -> AsyncIterator[ChatChunk]:
        ...  # pragma: no cover - structural only


class _Transcript:
    """In-memory representation of one fixture file.

    Holds the parsed deltas (a list of strings, in stream order), the
    citations that will be attached to the terminal frame, and the
    optional ``tool_calls`` list that captures the MCP tool invocations
    the scripted response executed. Loaded once at client init; treated
    as immutable.
    """

    __slots__ = ("id", "topic", "deltas", "citations", "tool_calls")

    def __init__(
        self,
        transcript_id: str,
        topic: str,
        deltas: list[str],
        citations: list[Citation],
        tool_calls: list[ToolCall] | None,
    ) -> None:
        self.id = transcript_id
        self.topic = topic
        self.deltas = deltas
        self.citations = citations
        self.tool_calls = tool_calls


def _parse_transcript(path: Path) -> _Transcript:
    """Load one JSON transcript file. Raises on malformed input.

    A transcript may declare a ``topic`` (one of the
    :mod:`kinetix_insights.chat.intent_router` constants) so the client
    can route a routed intent to it. Untagged transcripts default to
    :data:`~kinetix_insights.chat.intent_router.UNMATCHED`, which keeps
    legacy single-fixture corpora (and tests) working — an untagged
    fixture simply becomes the graceful fallback.
    """

    raw: dict[str, Any] = json.loads(path.read_text())
    transcript_id = str(raw["id"])
    topic = str(raw.get("topic", UNMATCHED))
    deltas = [str(entry["text"]) for entry in raw["deltas"]]
    citations = [Citation.model_validate(entry) for entry in raw.get("citations", [])]
    raw_tool_calls = raw.get("tool_calls", None)
    tool_calls: list[ToolCall] | None = None
    if raw_tool_calls:
        tool_calls = [ToolCall.model_validate(entry) for entry in raw_tool_calls]
    return _Transcript(transcript_id, topic, deltas, citations, tool_calls)


def _load_transcripts(fixtures_dir: Path) -> list[_Transcript]:
    """Parse every ``*.json`` under ``fixtures_dir``, sorted by id."""

    files = sorted(fixtures_dir.glob("*.json"))
    transcripts = [_parse_transcript(path) for path in files]
    transcripts.sort(key=lambda t: t.id)
    return transcripts


class CannedCopilotChatClient:
    """Fixture-driven :class:`CopilotChatClient`.

    Loads all transcripts under ``fixtures_dir`` eagerly. Selects one
    per ``chat`` call by **intent routing**: the user message (informed
    by ``page_context.page``) is routed to a topic by
    :func:`~kinetix_insights.chat.intent_router.route_intent`, and the
    transcript tagged with that topic answers it. A question no topic
    covers falls back to the ``UNMATCHED`` transcript; if the corpus
    has neither the routed topic nor an ``UNMATCHED`` transcript, the
    first transcript (by sorted id) is used so a single-fixture corpus
    always resolves. The chosen transcript's deltas stream as
    :class:`ChatChunk` frames with ``done=False``, each separated by
    ``delay_seconds`` of artificial latency. A final frame closes the
    stream with ``done=True``, the parsed citations, and the
    ``canned-chat`` / ``canned`` model+mode stamps.

    The ``history`` argument exists on the protocol so the live SDK
    client (plan §3.5) can consume conversation history; the canned
    client accepts and ignores it.
    """

    def __init__(
        self,
        *,
        fixtures_dir: Path | None = None,
        delay_seconds: float = _DEFAULT_DELAY_SECONDS,
    ) -> None:
        directory = fixtures_dir if fixtures_dir is not None else _DEFAULT_FIXTURES_DIR
        transcripts = _load_transcripts(directory)
        if not transcripts:
            raise ValueError(
                f"no chat transcripts found under {directory!s} — "
                "canned chat client requires at least one fixture"
            )
        self._transcripts = transcripts
        # First transcript wins on a topic collision; ``transcripts`` is
        # already sorted by id so the winner is deterministic.
        by_topic: dict[str, _Transcript] = {}
        for transcript in transcripts:
            by_topic.setdefault(transcript.topic, transcript)
        self._by_topic = by_topic
        self._delay_seconds = delay_seconds

    def _select(self, request: ChatRequest) -> _Transcript:
        """Route ``request`` to the transcript that should answer it.

        Resolution order: routed topic → ``UNMATCHED`` transcript →
        first transcript. The final fallback guarantees a single-fixture
        corpus (and legacy untagged fixtures) always resolve.
        """

        page = str(request.page_context.get("page", "") or "") or None
        topic = route_intent(request.message, page)
        transcript = self._by_topic.get(topic)
        if transcript is None:
            transcript = self._by_topic.get(UNMATCHED)
        if transcript is None:
            transcript = self._transcripts[0]
        return transcript

    def chat(
        self,
        request: ChatRequest,
        *,
        history: list[ConversationTurn] | None = None,
    ) -> AsyncIterator[ChatChunk]:
        """Return an async iterator that streams the chosen transcript.

        ``history`` is part of the protocol contract for the live
        variant and is intentionally unused here. Selection happens
        synchronously up-front so the caller can rely on the iterator
        being fully wired before the first ``__anext__``.
        """

        del history  # unused on the canned path; documented on the protocol
        transcript = self._select(request)
        return self._stream(transcript)

    async def _stream(self, transcript: _Transcript) -> AsyncIterator[ChatChunk]:
        """Yield one ``ChatChunk`` per delta, then a terminal metadata frame."""

        for text in transcript.deltas:
            if self._delay_seconds > 0:
                await asyncio.sleep(self._delay_seconds)
            yield ChatChunk(delta=text, done=False)
        yield ChatChunk(
            delta=None,
            done=True,
            citations=list(transcript.citations),
            tool_calls=transcript.tool_calls,
            model="canned-chat",
            mode="canned",
        )

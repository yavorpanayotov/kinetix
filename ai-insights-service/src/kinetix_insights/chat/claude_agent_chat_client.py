"""Live ``CopilotChatClient`` backed by the Claude Agent SDK.

This client wraps ``claude_agent_sdk.query()`` (or an injected fake)
and streams ``ChatChunk`` frames as the model emits text. Before the
terminal frame it runs four server-side safety nets:

* ``sanitise_message`` from
  :mod:`kinetix_insights.chat.sanitiser` — the inbound user message
  is filtered for prompt-injection patterns BEFORE the SDK is called
  so the literal injection phrase never reaches the model.
* ``check_narrative`` from
  :mod:`kinetix_insights.policy.banned_phrases` — any banned-phrase
  match surfaces as ``error_code="POLICY_VIOLATION"``.
* ``find_uncited_tokens`` from
  :mod:`kinetix_insights.citations.verifier` — any numeric token in
  the accumulated narrative that lacks a matching citation surfaces
  as ``error_code="CITATION_UNVERIFIABLE"``.
* ``find_uncited_symbols`` from
  :mod:`kinetix_insights.citations.symbol_verifier` — any
  ticker-shaped token (uppercase 3-6 chars) not backed by a citation
  params value ALSO surfaces as ``error_code="CITATION_UNVERIFIABLE"``.
  Numeric and symbol checks are OR'd: either firing aborts the stream.

Policy beats citation: a narrative that is both off-policy and
uncited surfaces as ``POLICY_VIOLATION`` (and carries no citations on
the terminal frame). Both error paths skip persistence — only a
clean response is written back to the :class:`ConversationStore`.

Per-message timeout — if ``per_message_timeout_seconds`` is set, the
client wraps each pull from the SDK's async iterator in
``asyncio.wait_for``. When that fires, the loop exits cleanly,
appends a synthetic :class:`Citation` with ``quality_flags=["TIMEOUT"]``
and ``result_value="timeout"``, and emits the terminal frame —
ensuring the stream NEVER hangs on a stalled upstream tool. Timed-out
turns are NOT persisted (same treatment as policy/citation errors).

Conversation history (the prior user/assistant turns) is fetched
from a :class:`ConversationStore` and threaded into the SDK prompt;
the final accumulated user message and assistant response are
persisted back at the end of the stream so the next turn picks up
where this one left off. An explicit ``history=`` argument overrides
the store-sourced history for the duration of the call.

Resilience contract — the returned async iterator **always closes
cleanly**:

* If the lazy ``claude_agent_sdk`` import fails, the iterator yields
  one terminal frame with ``error_code="UPSTREAM_ERROR"`` and stops.
* If the SDK's async iterator raises during ``__anext__`` (transport
  blip, internal error), the client swallows the exception and
  yields one terminal ``UPSTREAM_ERROR`` frame. Callers never see a
  raised exception while iterating the chat stream.

Tests inject ``_FakeStreamingSdk`` via the ``sdk`` constructor
argument; the live ``claude_agent_sdk`` import path is exercised by
the factory wiring and never by these unit tests.
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import AsyncIterator
from datetime import datetime, timezone
from typing import Any, ClassVar

from kinetix_insights.chat.conversation_store import (
    ConversationStore,
    ConversationTurn,
)
from kinetix_insights.chat.models import ChatChunk, ChatRequest
from kinetix_insights.chat.sanitiser import sanitise_message
from kinetix_insights.citations.models import TIMEOUT_FLAG, Citation
from kinetix_insights.citations.symbol_verifier import find_uncited_symbols
from kinetix_insights.citations.verifier import find_uncited_tokens
from kinetix_insights.claude_agent_client import (
    InsightClientUnavailable,
    _extract_text,
)
from kinetix_insights.metrics.copilot_metrics import COPILOT_SDK_ERROR_TOTAL
from kinetix_insights.policy.banned_phrases import (
    POLICY_VIOLATION,
    check_narrative,
)

_UPSTREAM_ERROR = "UPSTREAM_ERROR"
_CITATION_UNVERIFIABLE = "CITATION_UNVERIFIABLE"

_LOGGER = logging.getLogger("kinetix_insights.chat")


def _extract_citations(message: Any) -> list[Citation]:
    """Pull provenance citations out of an SDK message.

    Two extraction paths are tried, in order:

    1. ``message.citations`` — the direct, top-level attribute used by
       the in-tree fake and by SDK message shapes that surface
       citations alongside the text.
    2. ``message.content`` block scan — for SDK builds that attach
       citations to individual content blocks rather than the message
       as a whole, every block whose ``.citations`` is a list of
       :class:`Citation` is concatenated in order.

    Returns an empty list when neither path yields anything; the
    caller still emits a terminal frame, it just won't carry citations.
    """

    direct = getattr(message, "citations", None)
    if isinstance(direct, list) and all(isinstance(c, Citation) for c in direct):
        return list(direct)

    collected: list[Citation] = []
    content = getattr(message, "content", None)
    if isinstance(content, list):
        for block in content:
            block_citations = getattr(block, "citations", None)
            if isinstance(block_citations, list) and all(
                isinstance(c, Citation) for c in block_citations
            ):
                collected.extend(block_citations)
    return collected


def _format_history(history: list[ConversationTurn]) -> str:
    """Serialise prior turns as a textual prefix for the SDK prompt.

    The shape is intentionally simple — ``f"{role}: {content}\\n"``
    per turn, concatenated in order. The next-turn user message is
    appended by the caller after this prefix. A simple format keeps
    the prompt human-debuggable in logs and avoids depending on any
    SDK-specific message envelope shape.
    """

    return "".join(f"{turn.role}: {turn.content}\n" for turn in history)


class ClaudeAgentCopilotChatClient:
    """``CopilotChatClient`` backed by ``claude_agent_sdk.query``.

    Construction is cheap and side-effect free; the SDK is resolved
    on the first ``chat`` call. The async iterator returned by
    :meth:`chat` always closes cleanly — see the module docstring
    for the resilience contract.
    """

    DEFAULT_MODEL: ClassVar[str] = "claude-opus-4-7"

    def __init__(
        self,
        *,
        conversation_store: ConversationStore,
        sdk: Any | None = None,
        model: str = DEFAULT_MODEL,
        per_message_timeout_seconds: float | None = None,
    ) -> None:
        self._conversation_store = conversation_store
        self._sdk = sdk
        self.model = model
        self._per_message_timeout = per_message_timeout_seconds

    def chat(
        self,
        request: ChatRequest,
        *,
        history: list[ConversationTurn] | None = None,
    ) -> AsyncIterator[ChatChunk]:
        """Stream a chat response as ``ChatChunk`` frames.

        Returns an async iterator. The method is intentionally NOT
        ``async def`` — it constructs and returns the generator
        directly so callers can iterate it with ``async for`` exactly
        like the canned implementation.
        """

        return self._chat(request, history)

    def _resolve_query(self) -> Any:
        """Return the SDK ``query`` callable, importing it lazily if needed.

        Mirrors the v1 client: an injected ``sdk`` value can be either
        the ``query`` callable directly or a module-like object with a
        ``.query`` attribute. ImportError is wrapped in
        :class:`InsightClientUnavailable` so the async generator can
        translate it into an ``UPSTREAM_ERROR`` frame.
        """

        if self._sdk is not None:
            return getattr(self._sdk, "query", self._sdk)
        try:
            import claude_agent_sdk  # type: ignore[import-not-found]
        except Exception as exc:
            raise InsightClientUnavailable(
                f"claude-agent-sdk import failed: {exc}"
            ) from exc
        return claude_agent_sdk.query

    async def _chat(
        self,
        request: ChatRequest,
        history_arg: list[ConversationTurn] | None,
    ) -> AsyncIterator[ChatChunk]:
        """Drive the SDK stream and apply policy/citation safety nets.

        Layout:

        1. Resolve history (argument > store > empty).
        2. Resolve the SDK ``query`` callable. ImportError →
           ``UPSTREAM_ERROR`` terminal frame and return.
        3. Iterate the SDK stream, yielding a ``ChatChunk`` per
           extracted text fragment and accumulating both the narrative
           and any attached citations. Any exception raised mid-stream
           → ``UPSTREAM_ERROR`` terminal frame and return.
        4. Run policy guard first, then citation verifier. Either
           triggering emits a terminal error frame and aborts
           persistence — only a clean terminal frame triggers a write
           back to the :class:`ConversationStore`.
        """

        history = await self._resolve_history(request, history_arg)
        sanitised_message, detected_patterns = sanitise_message(request.message)
        if detected_patterns:
            # Structured WARNING so attempted injections are observable in
            # logs without leaking the raw payload (the sanitiser already
            # stripped the offending span). The chat continues with the
            # redacted message — sanitisation is a filter, not a gate.
            _LOGGER.warning(
                "prompt_injection_redacted",
                extra={
                    "conversation_id": request.conversation_id,
                    "session_id": request.session_id,
                    "patterns": detected_patterns,
                },
            )
        prompt = _format_history(history) + sanitised_message

        try:
            query = self._resolve_query()
        except InsightClientUnavailable:
            yield self._upstream_error_frame()
            return

        accumulated: list[str] = []
        citations: list[Citation] = []
        timed_out = False

        stream = query(prompt=prompt)
        iterator = stream.__aiter__() if hasattr(stream, "__aiter__") else stream

        try:
            while True:
                try:
                    message = await self._next_message(iterator)
                except StopAsyncIteration:
                    break
                except asyncio.TimeoutError:
                    timed_out = True
                    break
                text = _extract_text(message)
                citations.extend(_extract_citations(message))
                if text:
                    accumulated.append(text)
                    yield ChatChunk(delta=text, done=False)
        except Exception:
            yield self._upstream_error_frame()
            return

        if timed_out:
            # A per-message timeout is a structured signal, not an error
            # in the upstream sense — the stream completes with whatever
            # citations have been gathered, plus one synthetic TIMEOUT
            # citation marking the stalled tool. Persistence is skipped
            # so a partial / aborted turn never leaks into history.
            citations.append(self._timeout_citation())
            yield ChatChunk(
                done=True,
                citations=citations,
                mode="live",
                model=self.model,
            )
            return

        narrative = "".join(accumulated)

        policy_violation = check_narrative(narrative)
        if policy_violation:
            # Policy beats citations: do not attach citations to a
            # policy-blocked frame, and do not persist the turn.
            yield ChatChunk(
                done=True,
                error_code=POLICY_VIOLATION,
                mode="live",
                model=self.model,
            )
            return

        # Two-pass citation check: numeric tokens AND ticker-shaped
        # symbols. Either firing surfaces ``CITATION_UNVERIFIABLE``;
        # both run unconditionally so logs can attribute the cause.
        uncited_numbers = find_uncited_tokens(narrative, citations)
        uncited_symbols = find_uncited_symbols(narrative, citations)
        if uncited_numbers or uncited_symbols:
            yield ChatChunk(
                done=True,
                error_code=_CITATION_UNVERIFIABLE,
                citations=citations or None,
                mode="live",
                model=self.model,
            )
            return

        yield ChatChunk(
            done=True,
            citations=citations or None,
            mode="live",
            model=self.model,
        )

        # Persistence is conditional on a conversation_id being supplied
        # so anonymous calls don't accidentally leak turns into the store.
        if request.conversation_id is not None:
            await self._persist_turn(
                conversation_id=request.conversation_id,
                user_message=request.message,
                assistant_message=narrative,
            )

    async def _next_message(self, iterator: Any) -> Any:
        """Pull the next SDK message, applying the per-message timeout if set.

        ``StopAsyncIteration`` is re-raised so the caller exits the loop
        naturally; ``asyncio.TimeoutError`` is re-raised so the caller
        can branch into the timeout-citation path. Any other exception
        propagates to the surrounding ``except Exception`` so it becomes
        an ``UPSTREAM_ERROR`` terminal frame.
        """

        if self._per_message_timeout is None:
            return await iterator.__anext__()
        return await asyncio.wait_for(
            iterator.__anext__(), timeout=self._per_message_timeout
        )

    def _timeout_citation(self) -> Citation:
        """Synthesise a sentinel citation for a per-message timeout.

        Uses the existing :class:`Citation` schema (no field additions)
        — the ``quality_flags=["TIMEOUT"]`` entry combined with
        ``result_value="timeout"`` is the wire signal a tool call did
        not complete within its budget. ``data_source="chat-stream"``
        identifies the synthesis site so downstream consumers know it
        was minted server-side rather than emitted by a tool.
        """

        return Citation(
            tool="<unknown>",
            params={},
            result_field="status",
            result_value="timeout",
            result_currency=None,
            as_of_timestamp=datetime.now(timezone.utc),
            data_source="chat-stream",
            freshness_seconds=0,
            quality_flags=[TIMEOUT_FLAG],
        )

    async def _resolve_history(
        self,
        request: ChatRequest,
        history_arg: list[ConversationTurn] | None,
    ) -> list[ConversationTurn]:
        """Pick the history source: argument wins, then store, then empty."""

        if history_arg is not None:
            return history_arg
        if request.conversation_id is not None:
            return await self._conversation_store.get(request.conversation_id)
        return []

    async def _persist_turn(
        self,
        *,
        conversation_id: str,
        user_message: str,
        assistant_message: str,
    ) -> None:
        """Write the user prompt and assistant response back to the store.

        Both turns share a single timestamp captured here — the store
        is the authority for ordering (insertion order is preserved)
        so the timestamps are diagnostic rather than load-bearing.
        """

        now_ts = datetime.now(timezone.utc)
        await self._conversation_store.append(
            conversation_id,
            ConversationTurn(role="user", content=user_message, timestamp=now_ts),
        )
        await self._conversation_store.append(
            conversation_id,
            ConversationTurn(
                role="assistant", content=assistant_message, timestamp=now_ts
            ),
        )

    def _upstream_error_frame(self) -> ChatChunk:
        """Single terminal frame for any SDK-side failure path.

        This method is the one chokepoint every SDK failure path
        (unavailable ``query`` callable, mid-stream exception) routes
        through, so :data:`COPILOT_SDK_ERROR_TOTAL` is incremented here
        exactly once per failure.
        """

        COPILOT_SDK_ERROR_TOTAL.inc()
        return ChatChunk(
            done=True,
            error_code=_UPSTREAM_ERROR,
            mode="live",
            model=self.model,
        )

"""Live ``CopilotChatClient`` backed by the Claude Agent SDK.

This client wraps ``claude_agent_sdk.query()`` (or an injected fake)
and streams ``ChatChunk`` frames as the model emits text. Before the
terminal frame it runs two safety nets:

* ``find_uncited_tokens`` from
  :mod:`kinetix_insights.citations.verifier` — any numeric token in
  the accumulated narrative that lacks a matching citation surfaces
  as ``error_code="CITATION_UNVERIFIABLE"``.
* ``check_narrative`` from
  :mod:`kinetix_insights.policy.banned_phrases` — any banned-phrase
  match surfaces as ``error_code="POLICY_VIOLATION"``.

Policy beats citation: a narrative that is both off-policy and
uncited surfaces as ``POLICY_VIOLATION`` (and carries no citations on
the terminal frame). Both error paths skip persistence — only a
clean response is written back to the :class:`ConversationStore`.

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

from collections.abc import AsyncIterator
from datetime import datetime, timezone
from typing import Any, ClassVar

from kinetix_insights.chat.conversation_store import (
    ConversationStore,
    ConversationTurn,
)
from kinetix_insights.chat.models import ChatChunk, ChatRequest
from kinetix_insights.citations.models import Citation
from kinetix_insights.citations.verifier import find_uncited_tokens
from kinetix_insights.claude_agent_client import (
    InsightClientUnavailable,
    _extract_text,
)
from kinetix_insights.policy.banned_phrases import (
    POLICY_VIOLATION,
    check_narrative,
)

_UPSTREAM_ERROR = "UPSTREAM_ERROR"
_CITATION_UNVERIFIABLE = "CITATION_UNVERIFIABLE"


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
    ) -> None:
        self._conversation_store = conversation_store
        self._sdk = sdk
        self.model = model

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
        prompt = _format_history(history) + request.message

        try:
            query = self._resolve_query()
        except InsightClientUnavailable:
            yield self._upstream_error_frame()
            return

        accumulated: list[str] = []
        citations: list[Citation] = []

        try:
            async for message in query(prompt=prompt):
                text = _extract_text(message)
                citations.extend(_extract_citations(message))
                if text:
                    accumulated.append(text)
                    yield ChatChunk(delta=text, done=False)
        except Exception:
            yield self._upstream_error_frame()
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

        uncited = find_uncited_tokens(narrative, citations)
        if uncited:
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
        """Single terminal frame for any SDK-side failure path."""

        return ChatChunk(
            done=True,
            error_code=_UPSTREAM_ERROR,
            mode="live",
            model=self.model,
        )

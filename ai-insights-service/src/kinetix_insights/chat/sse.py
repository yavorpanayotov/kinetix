"""Shared SSE framing for chat-style streaming routes.

Both ``POST /api/v1/insights/chat`` and ``POST
/api/v1/insights/queries/{id}/run`` stream a ``CopilotChatClient``
response as Server-Sent Events. The wire contract is identical for both,
so the framing lives here once rather than being duplicated per route:

* Incremental text arrives as ``data: <ChatChunk-json>\\n\\n`` frames.
* A chunk carrying citations is preceded by an ``event: source\\n``
  frame whose ``data:`` line is a JSON list of citation objects, so a
  client parsing in order can attach the citations to the chunk that
  carried them.
* The terminal frame (``done=true``) merges the run's ``session_id`` and
  ``conversation_id`` into the JSON payload alongside the client's
  ``model`` / ``mode`` stamps. Those two ids are an SSE-wire concern —
  they are NOT fields on :class:`ChatChunk` — so they are merged in here
  just before the bytes leave the process.

The framing is request-model agnostic: it takes the resolved session and
conversation ids directly rather than a :class:`ChatRequest`, so any
route that already owns a chat request can reuse it.

Audit logging
-------------
Both streaming routes are audited (checkbox 10.3). When the caller
passes an :class:`~kinetix_insights.audit.audit_context.AuditContext`,
the streamer measures wall-clock latency, accumulates the tool calls
seen in chunk citations, captures the terminal ``mode`` stamp, estimates
the token count from the prompt plus the streamed text, and emits
exactly ONE :class:`~kinetix_insights.audit.audit_record.AuditRecord`
once the stream is fully drained. The audit emission lives here — once —
so chat and the saved-query run get an identical, single audit line
without each route duplicating the bookkeeping.
"""

from __future__ import annotations

import json
import time
import uuid
from collections.abc import AsyncIterator
from datetime import datetime, timezone

from starlette.responses import StreamingResponse

from kinetix_insights.audit.audit_context import AuditContext
from kinetix_insights.audit.audit_logger import AuditLogger
from kinetix_insights.audit.audit_record import AuditRecord
from kinetix_insights.audit.token_estimate import estimate_tokens
from kinetix_insights.chat.canned import CopilotChatClient
from kinetix_insights.chat.models import ChatChunk, ChatRequest
from kinetix_insights.metrics.copilot_metrics import (
    COPILOT_CHAT_SESSION_TOTAL,
    COPILOT_FIRST_BYTE_LATENCY_SECONDS,
)

# Shared audit logger for the streaming routes. The logger holds no
# per-request state, so a module-level singleton is safe.
_AUDIT_LOGGER = AuditLogger()

# Headers that keep nginx / Cloudflare from buffering the stream; the
# generator yields each frame as soon as it is built.
_SSE_HEADERS = {
    "Cache-Control": "no-cache",
    "X-Accel-Buffering": "no",
}


def new_id() -> str:
    """Return a fresh UUID hex string for session / conversation ids."""

    return uuid.uuid4().hex


def ensure_ids(body: ChatRequest) -> ChatRequest:
    """Return ``body`` with any missing session / conversation id stamped in.

    Missing ids are generated as UUID hex so the terminal frame can echo
    back something the UI can reuse on its next turn. Caller-supplied ids
    pass through unchanged.
    """

    if body.conversation_id is None:
        body = body.model_copy(update={"conversation_id": new_id()})
    if body.session_id is None:
        body = body.model_copy(update={"session_id": new_id()})
    return body


def _serialise_terminal(
    chunk: ChatChunk, *, session_id: str | None, conversation_id: str | None
) -> str:
    """Serialise a terminal chunk merging session_id + conversation_id.

    The two ids live at the SSE-wire level rather than on the
    :class:`ChatChunk` model, so we merge them in just before the bytes
    leave the process. ``exclude_none=True`` keeps empty fields out of
    the payload so the UI doesn't render dangling ``null`` keys.
    """

    data = chunk.model_dump(mode="json", exclude_none=True)
    data["session_id"] = session_id
    data["conversation_id"] = conversation_id
    return json.dumps(data)


def _serialise_citations(chunk: ChatChunk) -> str:
    """Serialise the citation list attached to a chunk for an ``event: source`` frame."""

    citations = chunk.citations or []
    return json.dumps([c.model_dump(mode="json") for c in citations])


def _tool_calls_from_chunk(chunk: ChatChunk) -> list[str]:
    """Return the distinct MCP tool names cited in a chunk, in order.

    Each :class:`~kinetix_insights.citations.models.Citation` names the
    tool that produced its value; the audit trail records the set of
    tools a call touched. Duplicates within one chunk are collapsed.
    """

    seen: list[str] = []
    for citation in chunk.citations or []:
        if citation.tool not in seen:
            seen.append(citation.tool)
    return seen


def stream_chat_response(
    chat_client: CopilotChatClient,
    body: ChatRequest,
    *,
    audit: AuditContext | None = None,
    audit_logger: AuditLogger | None = None,
) -> StreamingResponse:
    """Stream a ``CopilotChatClient`` response as an SSE ``StreamingResponse``.

    ``body`` must already have its session / conversation ids resolved
    (call :func:`ensure_ids` first). Each chunk from the client becomes a
    ``data:`` frame; a chunk with citations is preceded by an
    ``event: source`` frame; the terminal chunk's payload is merged with
    the resolved ids.

    When ``audit`` is supplied, exactly one
    :class:`~kinetix_insights.audit.audit_record.AuditRecord` is emitted
    once the stream is fully drained — capturing the tool calls seen, the
    terminal ``mode``, the estimated tokens, and the wall-clock latency.
    ``audit_logger`` is injectable for tests; production uses the shared
    module-level logger.
    """

    logger = audit_logger or _AUDIT_LOGGER

    async def event_stream() -> AsyncIterator[bytes]:
        started = time.monotonic()
        # One streaming response == one Copilot chat session.
        COPILOT_CHAT_SESSION_TOTAL.inc()
        first_frame = True
        tool_calls: list[str] = []
        deltas: list[str] = []
        mode: str | None = None
        async for chunk in chat_client.chat(body):
            for tool in _tool_calls_from_chunk(chunk):
                if tool not in tool_calls:
                    tool_calls.append(tool)
            if chunk.delta:
                deltas.append(chunk.delta)
            if chunk.mode is not None:
                mode = chunk.mode
            if chunk.citations:
                if first_frame:
                    COPILOT_FIRST_BYTE_LATENCY_SECONDS.observe(
                        time.monotonic() - started
                    )
                    first_frame = False
                src_data = _serialise_citations(chunk)
                yield f"event: source\ndata: {src_data}\n\n".encode("utf-8")
            if chunk.done:
                data = _serialise_terminal(
                    chunk,
                    session_id=body.session_id,
                    conversation_id=body.conversation_id,
                )
            else:
                data = chunk.model_dump_json(exclude_none=True)
            if first_frame:
                COPILOT_FIRST_BYTE_LATENCY_SECONDS.observe(
                    time.monotonic() - started
                )
                first_frame = False
            yield f"data: {data}\n\n".encode("utf-8")

        if audit is not None:
            latency_ms = (time.monotonic() - started) * 1000.0
            logger.emit(
                AuditRecord(
                    user_id=audit.user_id,
                    endpoint=audit.endpoint,
                    prompt_hash=AuditRecord.hash_prompt(audit.prompt),
                    tool_calls=tool_calls,
                    tokens_estimated=estimate_tokens(
                        audit.prompt, *deltas
                    ),
                    mode=mode or "unknown",
                    latency_ms=latency_ms,
                    timestamp=datetime.now(timezone.utc),
                )
            )

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers=dict(_SSE_HEADERS),
    )

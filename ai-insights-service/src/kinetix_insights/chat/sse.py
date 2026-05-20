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
"""

from __future__ import annotations

import json
import uuid
from collections.abc import AsyncIterator

from starlette.responses import StreamingResponse

from kinetix_insights.chat.canned import CopilotChatClient
from kinetix_insights.chat.models import ChatChunk, ChatRequest

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


def stream_chat_response(
    chat_client: CopilotChatClient, body: ChatRequest
) -> StreamingResponse:
    """Stream a ``CopilotChatClient`` response as an SSE ``StreamingResponse``.

    ``body`` must already have its session / conversation ids resolved
    (call :func:`ensure_ids` first). Each chunk from the client becomes a
    ``data:`` frame; a chunk with citations is preceded by an
    ``event: source`` frame; the terminal chunk's payload is merged with
    the resolved ids.
    """

    async def event_stream() -> AsyncIterator[bytes]:
        async for chunk in chat_client.chat(body):
            if chunk.citations:
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
            yield f"data: {data}\n\n".encode("utf-8")

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers=dict(_SSE_HEADERS),
    )

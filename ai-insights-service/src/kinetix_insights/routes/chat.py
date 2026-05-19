"""``POST /api/v1/insights/chat`` — SSE streaming chat endpoint.

Streams ``data: {ChatChunk-json}\\n\\n`` per the SSE protocol. When a
chunk carries citations the route prefixes a frame with
``event: source\\n`` followed by a JSON list of citation objects, so
the UI can render provenance footnotes independently of the narrative
text frames. The terminal frame (``done=true``) merges the request's
``session_id`` and ``conversation_id`` into the JSON payload alongside
the canned/live client's ``model`` and ``mode`` stamps.

Design notes:

* **Session/conversation ids are an SSE-wire concern.** They are NOT
  fields on :class:`ChatChunk` (the chunk is the model's contribution).
  The route generates missing ids via ``uuid.uuid4().hex`` so a follow-up
  turn can reuse them.
* **Source frames precede the data frame that introduced them.** This
  matches the v2 plan: ``event: source\\n`` then ``data: <list>\\n\\n``
  is emitted before the corresponding ``data: <ChatChunk>\\n\\n`` so a
  client parsing in order can attach the citations to the chunk that
  carried them.
* **No buffering.** ``X-Accel-Buffering: no`` plus ``Cache-Control:
  no-cache`` keep nginx / Cloudflare from holding the stream; the
  generator yields each frame as bytes as soon as it is built.
"""

from __future__ import annotations

import json
import uuid
from collections.abc import AsyncIterator

from fastapi import APIRouter, Request
from starlette.responses import StreamingResponse

from kinetix_insights.chat.canned import CopilotChatClient
from kinetix_insights.chat.models import ChatChunk, ChatRequest

router = APIRouter(prefix="/api/v1/insights", tags=["chat"])


def _new_id() -> str:
    """Return a fresh UUID hex string for session / conversation ids."""

    return uuid.uuid4().hex


def _serialise_terminal(chunk: ChatChunk, body: ChatRequest) -> str:
    """Serialise a terminal chunk merging session_id + conversation_id.

    The two ids live at the SSE-wire level rather than on the
    :class:`ChatChunk` model, so we merge them in just before the bytes
    leave the process. ``exclude_none=True`` keeps empty fields out of
    the payload so the UI doesn't render dangling ``null`` keys.
    """

    data = chunk.model_dump(mode="json", exclude_none=True)
    data["session_id"] = body.session_id
    data["conversation_id"] = body.conversation_id
    return json.dumps(data)


def _serialise_citations(chunk: ChatChunk) -> str:
    """Serialise the citation list attached to a chunk for an ``event: source`` frame."""

    citations = chunk.citations or []
    return json.dumps([c.model_dump(mode="json") for c in citations])


@router.post("/chat")
async def chat(request: Request, body: ChatRequest) -> StreamingResponse:
    """Stream a conversational chat response as SSE frames."""

    chat_client: CopilotChatClient = request.app.state.chat_client

    # Stamp in fresh ids if the caller didn't supply any, so the terminal
    # frame can echo back something the UI can reuse on its next turn.
    if body.conversation_id is None:
        body = body.model_copy(update={"conversation_id": _new_id()})
    if body.session_id is None:
        body = body.model_copy(update={"session_id": _new_id()})

    async def event_stream() -> AsyncIterator[bytes]:
        async for chunk in chat_client.chat(body):
            if chunk.citations:
                src_data = _serialise_citations(chunk)
                yield f"event: source\ndata: {src_data}\n\n".encode("utf-8")
            if chunk.done:
                data = _serialise_terminal(chunk, body)
            else:
                data = chunk.model_dump_json(exclude_none=True)
            yield f"data: {data}\n\n".encode("utf-8")

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
        },
    )

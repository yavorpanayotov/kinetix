"""``POST /api/v1/insights/chat`` — SSE streaming chat endpoint.

Streams ``data: {ChatChunk-json}\\n\\n`` per the SSE protocol. When a
chunk carries citations the route prefixes a frame with
``event: source\\n`` followed by a JSON list of citation objects, so
the UI can render provenance footnotes independently of the narrative
text frames. The terminal frame (``done=true``) merges the request's
``session_id`` and ``conversation_id`` into the JSON payload alongside
the canned/live client's ``model`` and ``mode`` stamps.

The SSE framing itself lives in :mod:`kinetix_insights.chat.sse` so the
saved-query run route (``POST /api/v1/insights/queries/{id}/run``) emits
byte-identical frames rather than duplicating the wire logic. This route
is left as the thin HTTP adapter: pull the chat client off ``app.state``,
resolve ids, hand the request to the shared streamer.
"""

from __future__ import annotations

from fastapi import APIRouter, Request
from starlette.responses import StreamingResponse

from kinetix_insights.audit.audit_context import AuditContext
from kinetix_insights.chat.canned import CopilotChatClient
from kinetix_insights.chat.models import ChatRequest
from kinetix_insights.chat.sse import ensure_ids, stream_chat_response

router = APIRouter(prefix="/api/v1/insights", tags=["chat"])


def _user_id_from_request(request: Request) -> str:
    """Resolve the calling trader id from the ``X-User-Id`` header.

    The gateway forwards the JWT ``sub`` as ``X-User-Id``. A missing
    header yields ``"anonymous"`` — the unauthenticated demo path — so
    the audit line always carries a ``user_id``.
    """

    return request.headers.get("X-User-Id") or "anonymous"


@router.post("/chat")
async def chat(request: Request, body: ChatRequest) -> StreamingResponse:
    """Stream a conversational chat response as SSE frames.

    Emits exactly one structured audit log line (checkbox 10.3) once the
    stream completes — see :func:`kinetix_insights.chat.sse.
    stream_chat_response`.
    """

    chat_client: CopilotChatClient = request.app.state.chat_client
    body = ensure_ids(body)
    audit = AuditContext(
        user_id=_user_id_from_request(request),
        endpoint="chat",
        prompt=body.message,
    )
    return stream_chat_response(chat_client, body, audit=audit)

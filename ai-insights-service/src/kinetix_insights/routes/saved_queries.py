"""``POST /api/v1/insights/queries/{id}/run`` — run a saved query.

A saved query is a named, parameterised prompt shipped as a built-in
JSON template (see :mod:`kinetix_insights.queries`). This route:

1. Loads the template named by the ``{id}`` path parameter. An unknown
   id is a ``404``.
2. Interpolates the request body's ``params`` into the template's
   ``prompt_template``. A missing required param is a ``422`` — the same
   client-error class FastAPI uses for body-validation failures, so the
   UI handles both uniformly.
3. Builds the *same* :class:`ChatRequest` the ``/chat`` route would (the
   interpolated prompt as the message) and streams it through the *same*
   ``CopilotChatClient`` held on ``app.state`` — there is no parallel
   execution path. The page context records that the turn originated
   from a saved query so downstream logging / the live client can tell.

The SSE response is byte-identical to ``/chat``'s because both routes
emit through :func:`kinetix_insights.chat.sse.stream_chat_response`.
"""

from __future__ import annotations

from fastapi import APIRouter, HTTPException, Request
from starlette.responses import StreamingResponse

from kinetix_insights.audit.audit_context import AuditContext
from kinetix_insights.chat.canned import CopilotChatClient
from kinetix_insights.chat.models import ChatRequest
from kinetix_insights.chat.sse import ensure_ids, stream_chat_response
from kinetix_insights.queries.loader import (
    SavedQueryTemplateNotFoundError,
    load_saved_query_template,
)
from kinetix_insights.queries.saved_query_run_request import SavedQueryRunRequest
from kinetix_insights.queries.saved_query_template import MissingRequiredParamsError

router = APIRouter(prefix="/api/v1/insights", tags=["saved-queries"])


@router.post("/queries/{template_id}/run")
async def run_saved_query(
    template_id: str, request: Request, body: SavedQueryRunRequest
) -> StreamingResponse:
    """Run the built-in saved query ``template_id`` and stream the result.

    Returns ``404`` for an unknown template id and ``422`` when the
    request omits a param the template requires.
    """

    try:
        template = load_saved_query_template(template_id)
    except SavedQueryTemplateNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc

    try:
        prompt = template.render(body.params)
    except MissingRequiredParamsError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc

    chat_client: CopilotChatClient = request.app.state.chat_client
    chat_request = ensure_ids(
        ChatRequest(
            message=prompt,
            page_context={"source": "saved-query", "query_id": template.id},
            session_id=body.session_id,
            conversation_id=body.conversation_id,
        )
    )
    # Audited as the "query" endpoint (checkbox 10.3) — distinct from a
    # free-form "chat" call even though both stream through the same
    # client. The interpolated prompt's hash, not the raw text, is logged.
    audit = AuditContext(
        user_id=request.headers.get("X-User-Id") or "anonymous",
        endpoint="query",
        prompt=prompt,
    )
    return stream_chat_response(chat_client, chat_request, audit=audit)

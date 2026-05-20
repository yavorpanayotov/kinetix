"""FastAPI application entrypoint for the Kinetix AI Insights service.

This module exposes the bare scaffold of the service: an application
instance with health and readiness probes. The startup lifespan wires
an :class:`InsightClient` (live Claude Agent SDK or canned fallback) onto
``app.state`` so request handlers added in subsequent steps can use it.
"""

import asyncio
import contextlib
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from .brief.brief_store import BriefStore
from .brief.factory import build_brief_client
from .brief.scheduler import run_brief_scheduler
from .chat.conversation_store import InMemoryConversationStore
from .chat.factory import build_chat_client
from .clients.kinetix_http_client import HttpxKinetixHttpClient
from .clients.user_context import UserContext
from .factory import build_client
from .mcp.health import router as mcp_health_router
from .mcp.server import build_mcp_server
from .routes.brief import router as brief_router
from .routes.chat import router as chat_router
from .routes.report_commentary import router as report_router
from .routes.var_explainer import router as var_router

# Demo single-tenant user the 06:30 scheduler pre-generates a brief for.
# Multi-tenant brief pre-generation (one entry per signed-in trader) is
# out of scope for v2 — the on-demand path covers any other user.
_DEMO_BRIEF_USERS = [UserContext(user_id="demo-trader", books=("fx-main",))]


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """Build the insight + chat clients and MCP scaffold onto ``app.state``.

    The MCP server is held ready on ``app.state.mcp_server`` for the
    ``/mcp/health`` probe and for tool registration in PR 2. Actual
    serving on the internal MCP port (see
    ``kinetix_insights.mcp.server.MCP_PORT``) is deferred until a
    consumer exists. The chat client and its conversation store back
    the SSE ``POST /api/v1/insights/chat`` route.

    The brief store and brief client back ``GET
    /api/v1/insights/brief/today``. A background task
    (:func:`run_brief_scheduler`) pre-generates briefs at 06:30 local;
    it is cancelled and awaited on shutdown so it never leaks into
    another app instance's event loop.
    """
    app.state.insight_client = build_client()
    app.state.mcp_server = build_mcp_server()
    app.state.conversation_store = InMemoryConversationStore()
    app.state.chat_client = build_chat_client(
        conversation_store=app.state.conversation_store,
    )
    app.state.brief_store = BriefStore()
    app.state.brief_client = build_brief_client(http=HttpxKinetixHttpClient())
    app.state._brief_task = asyncio.create_task(
        run_brief_scheduler(
            brief_store=app.state.brief_store,
            brief_client=app.state.brief_client,
            user_provider=lambda: list(_DEMO_BRIEF_USERS),
        )
    )
    try:
        yield
    finally:
        app.state._brief_task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await app.state._brief_task


app = FastAPI(title="Kinetix Insights", lifespan=lifespan)
app.include_router(var_router)
app.include_router(report_router)
app.include_router(mcp_health_router)
app.include_router(chat_router)
app.include_router(brief_router)


@app.get("/health")
def health() -> dict[str, str]:
    """Liveness probe — returns OK as soon as the process is up."""
    return {"status": "ok"}


@app.get("/ready")
def ready() -> dict[str, str]:
    """Readiness probe — returns ready when the service can accept traffic."""
    return {"status": "ready"}

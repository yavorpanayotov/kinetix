"""FastAPI application entrypoint for the Kinetix AI Insights service.

This module exposes the bare scaffold of the service: an application
instance with health and readiness probes. The startup lifespan wires
an :class:`InsightClient` (live Claude Agent SDK or canned fallback) onto
``app.state`` so request handlers added in subsequent steps can use it.
"""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from .chat.conversation_store import InMemoryConversationStore
from .chat.factory import build_chat_client
from .factory import build_client
from .mcp.health import router as mcp_health_router
from .mcp.server import build_mcp_server
from .routes.chat import router as chat_router
from .routes.report_commentary import router as report_router
from .routes.var_explainer import router as var_router


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """Build the insight + chat clients and MCP scaffold onto ``app.state``.

    The MCP server is held ready on ``app.state.mcp_server`` for the
    ``/mcp/health`` probe and for tool registration in PR 2. Actual
    serving on the internal MCP port (see
    ``kinetix_insights.mcp.server.MCP_PORT``) is deferred until a
    consumer exists. The chat client and its conversation store back
    the SSE ``POST /api/v1/insights/chat`` route.
    """
    app.state.insight_client = build_client()
    app.state.mcp_server = build_mcp_server()
    app.state.conversation_store = InMemoryConversationStore()
    app.state.chat_client = build_chat_client(
        conversation_store=app.state.conversation_store,
    )
    yield


app = FastAPI(title="Kinetix Insights", lifespan=lifespan)
app.include_router(var_router)
app.include_router(report_router)
app.include_router(mcp_health_router)
app.include_router(chat_router)


@app.get("/health")
def health() -> dict[str, str]:
    """Liveness probe — returns OK as soon as the process is up."""
    return {"status": "ok"}


@app.get("/ready")
def ready() -> dict[str, str]:
    """Readiness probe — returns ready when the service can accept traffic."""
    return {"status": "ready"}

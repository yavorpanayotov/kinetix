"""FastAPI application entrypoint for the Kinetix AI Insights service.

This module exposes the bare scaffold of the service: an application
instance with health and readiness probes. The startup lifespan wires
an :class:`InsightClient` (live Claude Agent SDK or canned fallback) onto
``app.state`` so request handlers added in subsequent steps can use it.
"""

import asyncio
import contextlib
import logging
import os
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from .brief.brief_store import BriefStore
from .brief.factory import build_brief_client
from .brief.scheduler import run_brief_scheduler
from .chat.conversation_store_factory import build_conversation_store
from .chat.factory import build_chat_client
from .clients.gateway_push_client import HttpxGatewayPushClient
from .clients.kinetix_http_client import HttpxKinetixHttpClient
from .clients.user_context import UserContext
from .factory import build_client
from .mcp.health import router as mcp_health_router
from .mcp.server import build_mcp_server
from .push.factory import build_intraday_push_generator
from .push.kafka_consumer import IntradayKafkaConsumer
from .push.threshold_evaluator import IntradayThresholdEvaluator
from .routes.brief import router as brief_router
from .routes.chat import router as chat_router
from .routes.report_commentary import router as report_router
from .routes.saved_queries import router as saved_queries_router
from .routes.var_explainer import router as var_router

_logger = logging.getLogger("kinetix_insights.push")

# Demo single-tenant user the 06:30 scheduler pre-generates a brief for.
# Multi-tenant brief pre-generation (one entry per signed-in trader) is
# out of scope for v2 — the on-demand path covers any other user.
_DEMO_BRIEF_USERS = [UserContext(user_id="demo-trader", books=("fx-main",))]

# Demo user the intraday threshold evaluator stamps on its downstream
# calls. Mirrors the single-tenant demo user the brief scheduler picks.
_DEMO_INTRADAY_USER = UserContext(user_id="demo-trader", books=("fx-main",))


def _kafka_enabled() -> bool:
    """Return whether the intraday Kafka consumer should actually start.

    Gated behind ``KINETIX_KAFKA_ENABLED`` so DEMO_MODE / CI / the app
    acceptance tests (which enter the lifespan) never attempt a real
    broker connection. The consumer object is still built and attached
    to ``app.state`` for inspection; only ``.start()`` is gated.
    """

    return os.environ.get("KINETIX_KAFKA_ENABLED", "").lower() == "true"


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

    An :class:`IntradayKafkaConsumer` is built onto
    ``app.state.kafka_consumer`` for the intraday-push pipeline. It is
    only *started* when ``KINETIX_KAFKA_ENABLED=true`` — DEMO_MODE, CI,
    and the app acceptance tests leave the flag unset, so no broker
    connection is attempted there. ``stop()`` is idempotent and always
    safe to call on shutdown. The consumer is wired with the
    ``IntradayPushGenerator`` selected by
    :func:`build_intraday_push_generator` (canned in DEMO_MODE, live
    otherwise).

    When a firing alert is composed, the live generator forwards the
    push to the gateway via an injected ``sink``: an
    :class:`HttpxGatewayPushClient` POSTing to ``/internal/copilot/push``
    (checkbox 7.7). The client is built only when both
    ``GATEWAY_INTERNAL_URL`` and ``COPILOT_INTERNAL_TOKEN`` are set —
    DEMO_MODE / CI leave them unset, so :meth:`HttpxGatewayPushClient.
    from_env` returns ``None`` and no live HTTP call is ever attempted.
    """
    app.state.insight_client = build_client()
    app.state.mcp_server = build_mcp_server()
    app.state.conversation_store = build_conversation_store()
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

    # Live gateway dispatch sink — None unless GATEWAY_INTERNAL_URL and
    # COPILOT_INTERNAL_TOKEN are both set, so DEMO_MODE / CI make no HTTP
    # call. Ignored on the canned generator path (see push.factory).
    gateway_push_client = HttpxGatewayPushClient.from_env()
    push_sink = gateway_push_client.as_sink() if gateway_push_client else None
    app.state.gateway_push_client = gateway_push_client
    app.state.kafka_consumer = IntradayKafkaConsumer(
        evaluator=IntradayThresholdEvaluator(
            http=HttpxKinetixHttpClient(),
            user=_DEMO_INTRADAY_USER,
        ),
        push_generator=build_intraday_push_generator(sink=push_sink),
    )
    if _kafka_enabled():
        await app.state.kafka_consumer.start()
    else:
        _logger.info(
            "intraday Kafka consumer not started "
            "(set KINETIX_KAFKA_ENABLED=true to enable)"
        )

    try:
        yield
    finally:
        app.state._brief_task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await app.state._brief_task
        # stop() is idempotent — safe even when the consumer never started.
        await app.state.kafka_consumer.stop()


app = FastAPI(title="Kinetix Insights", lifespan=lifespan)
app.include_router(var_router)
app.include_router(report_router)
app.include_router(mcp_health_router)
app.include_router(chat_router)
app.include_router(brief_router)
app.include_router(saved_queries_router)


@app.get("/health")
def health() -> dict[str, str]:
    """Liveness probe — returns OK as soon as the process is up."""
    return {"status": "ok"}


@app.get("/ready")
def ready() -> dict[str, str]:
    """Readiness probe — returns ready when the service can accept traffic."""
    return {"status": "ready"}

"""FastAPI application entrypoint for the Kinetix AI Insights service.

This module exposes the bare scaffold of the service: an application
instance with health and readiness probes. The startup lifespan wires
an :class:`InsightClient` (live Claude Agent SDK or canned fallback) onto
``app.state`` so request handlers added in subsequent steps can use it.
"""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from .factory import build_client


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """Build the insight client at startup and attach it to ``app.state``."""
    app.state.insight_client = build_client()
    yield


app = FastAPI(title="Kinetix Insights", lifespan=lifespan)


@app.get("/health")
def health() -> dict[str, str]:
    """Liveness probe — returns OK as soon as the process is up."""
    return {"status": "ok"}


@app.get("/ready")
def ready() -> dict[str, str]:
    """Readiness probe — returns ready when the service can accept traffic."""
    return {"status": "ready"}

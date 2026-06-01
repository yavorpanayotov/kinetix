"""Acceptance test: the grounded canned client over the real chat route.

Exercises ``POST /api/v1/insights/chat`` end-to-end (route + SSE framing
+ data-grounded client) with the MCP HTTP boundary faked, proving that a
demo viewer running ``COPILOT_GROUNDED_DEMO`` sees the **live** book
figure on the wire — not a fixture constant — and that the frame is
stamped ``mode="canned-grounded"``. No ``claude`` subprocess is spawned.
"""

from __future__ import annotations

import json
import os
from collections.abc import AsyncIterator
from datetime import datetime, timezone
from typing import Any

import httpx
import pytest
import pytest_asyncio
from httpx import ASGITransport

from kinetix_insights.chat.data_grounded_canned import DataGroundedCannedChatClient
from tests.fakes.fake_kinetix_http_client import FakeKinetixHttpClient

pytestmark = pytest.mark.unit


_NOW = datetime(2026, 5, 19, 8, 2, 0, tzinfo=timezone.utc)


def _now() -> datetime:
    return _NOW


_VAR_PAYLOAD = {
    "varValue": 7_777_777.0,
    "confidenceLevel": "95%",
    "calculatedAt": "2026-05-19T08:00:00Z",
    "componentBreakdown": [],
}


def _split_sse_frames(raw: bytes) -> list[str]:
    text = raw.decode("utf-8")
    return [frame for frame in text.split("\n\n") if frame.strip()]


def _data_payloads(frames: list[str]) -> list[Any]:
    payloads: list[Any] = []
    for frame in frames:
        for line in frame.splitlines():
            if line.startswith("data: "):
                payloads.append(json.loads(line[len("data: ") :]))
    return payloads


@pytest_asyncio.fixture()
async def grounded_app() -> AsyncIterator[Any]:
    """Demo app whose chat client is the grounded client over a fake HTTP."""

    os.environ["DEMO_MODE"] = "true"
    from kinetix_insights.app import app

    http = FakeKinetixHttpClient()
    http.register_response(
        "GET", "risk-orchestrator", "/api/v1/risk/var/fx-main", _VAR_PAYLOAD
    )
    async with app.router.lifespan_context(app):
        app.state.chat_client = DataGroundedCannedChatClient(
            http=http, delay_seconds=0.0, now=_now
        )
        yield app


@pytest.mark.asyncio
async def test_grounded_chat_streams_live_book_figure(grounded_app: Any) -> None:
    transport = ASGITransport(app=grounded_app)
    body = {
        "message": "what's my VaR?",
        "page_context": {"page": "var-dashboard", "book_id": "fx-main"},
    }
    async with httpx.AsyncClient(
        transport=transport, base_url="http://test"
    ) as client:
        response = await client.post("/api/v1/insights/chat", json=body)

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")

    frames = _split_sse_frames(response.content)
    narrative = "".join(
        p["delta"]
        for p in _data_payloads(frames)
        if isinstance(p, dict) and p.get("delta")
    )
    terminal = next(
        p for p in _data_payloads(frames) if isinstance(p, dict) and p.get("done")
    )

    # The live value is on the wire; the $5.2M fixture constant is not.
    assert "$7,777,777" in narrative
    assert "5,200,000" not in narrative
    assert terminal["mode"] == "canned-grounded"
    assert terminal["model"] == "canned-grounded"

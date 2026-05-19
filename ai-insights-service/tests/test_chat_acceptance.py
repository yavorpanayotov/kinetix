"""Acceptance tests for the SSE chat route.

These tests exercise the FastAPI app's chat endpoint
(``POST /api/v1/insights/chat``) over an in-process ASGI transport.
``DEMO_MODE=true`` (set both in the acceptance command and inside
``_force_demo_mode``) forces the chat-client factory to return
:class:`CannedCopilotChatClient`, so the stream is deterministic and
no external dependency is touched.

The tests assert the SSE wire-level contract:

* Content-Type starts with ``text/event-stream``.
* The body contains ``data: <ChatChunk-json>\\n\\n`` frames.
* A terminal frame closes the stream with ``done == True`` and the
  ``session_id``, ``conversation_id``, ``model``, ``mode`` keys merged
  into the JSON payload (NOT into the ``ChatChunk`` model itself).
* When a chunk carries citations the route prefixes a frame with
  ``event: source\\n`` followed by a JSON list of citations.
* Session / conversation ids supplied by the caller are echoed back
  unchanged; missing ids are generated server-side as UUID hex.
* Empty / missing message bodies are rejected with 422.
"""

from __future__ import annotations

import json
import os
import re
from collections.abc import AsyncIterator
from typing import Any

import httpx
import pytest
import pytest_asyncio
from httpx import ASGITransport

pytestmark = pytest.mark.unit


_UUID_HEX_RE = re.compile(r"^[0-9a-f]{32}$")


def _force_demo_mode() -> Any:
    """Set ``DEMO_MODE=true`` and return the freshly imported FastAPI app.

    Mirrors :func:`tests.test_mcp_server_scaffold._build_client` — the env
    var must be set BEFORE the app module is imported so the lifespan
    constructs the canned chat client.
    """

    os.environ["DEMO_MODE"] = "true"
    from kinetix_insights.app import app  # imported after env is set

    return app


def _split_sse_frames(raw: bytes) -> list[str]:
    """Split a raw SSE byte stream into a list of frame strings.

    SSE frames are separated by blank lines (``\\n\\n``). Trailing
    empty splits are stripped so callers can iterate frames directly.
    """

    text = raw.decode("utf-8")
    return [frame for frame in text.split("\n\n") if frame.strip()]


def _frame_data(frame: str) -> dict[str, Any] | list[Any] | None:
    """Return the JSON-decoded ``data:`` payload of one SSE frame, or None."""

    for line in frame.splitlines():
        if line.startswith("data: "):
            return json.loads(line[len("data: "):])
    return None


def _frame_event(frame: str) -> str | None:
    """Return the ``event:`` name of one SSE frame, or None if missing."""

    for line in frame.splitlines():
        if line.startswith("event: "):
            return line[len("event: "):]
    return None


async def _post_chat(
    app: Any, body: dict[str, Any]
) -> tuple[httpx.Response, list[str]]:
    """POST to the chat endpoint and return (response, list-of-frames).

    Uses ``ASGITransport`` so the body is streamed through the app in
    process; the entire byte stream is then collected for inspection.
    Tests are async because httpx's ASGI transport is async-only.
    """

    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post("/api/v1/insights/chat", json=body)
    frames = _split_sse_frames(response.content)
    return response, frames


@pytest_asyncio.fixture()
async def app_in_demo() -> AsyncIterator[Any]:
    """Force DEMO_MODE on, drive the FastAPI lifespan, and yield the app.

    ``ASGITransport`` does not run the ASGI lifespan protocol on its own
    so we enter ``app.router.lifespan_context(app)`` here to populate
    ``app.state.chat_client`` etc. before any test runs the route.
    """

    app = _force_demo_mode()
    async with app.router.lifespan_context(app):
        yield app


@pytest.mark.asyncio
async def test_chat_endpoint_returns_sse_content_type(app_in_demo: Any) -> None:
    """The route must advertise ``text/event-stream`` for the SSE channel."""

    response, _ = await _post_chat(
        app_in_demo,
        {"message": "explain", "page_context": {"page": "var-dashboard"}},
    )
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")


@pytest.mark.asyncio
async def test_chat_endpoint_streams_data_frames(app_in_demo: Any) -> None:
    """At least one delta frame and a terminal ``done == true`` frame stream out."""

    _, frames = await _post_chat(
        app_in_demo,
        {"message": "explain", "page_context": {"page": "var-dashboard"}},
    )
    payloads = [_frame_data(f) for f in frames if _frame_event(f) is None]
    assert any(
        isinstance(p, dict) and p.get("delta") and not p.get("done")
        for p in payloads
    ), f"expected at least one delta frame, got {payloads!r}"
    assert payloads[-1] is not None
    assert isinstance(payloads[-1], dict)
    assert payloads[-1]["done"] is True


@pytest.mark.asyncio
async def test_chat_terminal_frame_includes_session_and_conversation_ids(
    app_in_demo: Any,
) -> None:
    """The terminal frame's JSON merges session_id + conversation_id."""

    _, frames = await _post_chat(
        app_in_demo,
        {"message": "explain", "page_context": {"page": "var-dashboard"}},
    )
    terminal = _frame_data(frames[-1])
    assert isinstance(terminal, dict)
    assert terminal["done"] is True
    assert isinstance(terminal["session_id"], str) and terminal["session_id"]
    assert isinstance(terminal["conversation_id"], str) and terminal["conversation_id"]


@pytest.mark.asyncio
async def test_chat_terminal_frame_includes_model_and_mode(app_in_demo: Any) -> None:
    """The terminal frame echoes the canned client's model / mode stamps."""

    _, frames = await _post_chat(
        app_in_demo,
        {"message": "explain", "page_context": {"page": "var-dashboard"}},
    )
    terminal = _frame_data(frames[-1])
    assert isinstance(terminal, dict)
    assert terminal["mode"] == "canned"
    assert terminal["model"] == "canned-chat"


@pytest.mark.asyncio
async def test_chat_emits_source_event_for_citations(app_in_demo: Any) -> None:
    """A frame with citations is preceded by an ``event: source`` frame."""

    _, frames = await _post_chat(
        app_in_demo,
        {"message": "explain", "page_context": {"page": "var-dashboard"}},
    )
    source_frames = [f for f in frames if _frame_event(f) == "source"]
    assert source_frames, "expected at least one event: source frame"
    citations_payload = _frame_data(source_frames[0])
    assert isinstance(citations_payload, list) and citations_payload
    # Each citation must look like a Citation: tool + result_field present.
    first = citations_payload[0]
    assert isinstance(first, dict)
    assert "tool" in first and "result_field" in first


@pytest.mark.asyncio
async def test_chat_returns_supplied_session_and_conversation_ids(
    app_in_demo: Any,
) -> None:
    """Caller-supplied ids are echoed back unchanged on the terminal frame."""

    _, frames = await _post_chat(
        app_in_demo,
        {
            "message": "explain",
            "page_context": {"page": "var-dashboard"},
            "session_id": "client-sess-1",
            "conversation_id": "client-conv-1",
        },
    )
    terminal = _frame_data(frames[-1])
    assert isinstance(terminal, dict)
    assert terminal["session_id"] == "client-sess-1"
    assert terminal["conversation_id"] == "client-conv-1"


@pytest.mark.asyncio
async def test_chat_generates_ids_when_missing(app_in_demo: Any) -> None:
    """Missing ids are generated server-side as UUID hex strings."""

    _, frames = await _post_chat(
        app_in_demo,
        {"message": "explain", "page_context": {"page": "var-dashboard"}},
    )
    terminal = _frame_data(frames[-1])
    assert isinstance(terminal, dict)
    assert _UUID_HEX_RE.match(terminal["session_id"]), terminal["session_id"]
    assert _UUID_HEX_RE.match(terminal["conversation_id"]), terminal["conversation_id"]


@pytest.mark.asyncio
async def test_chat_validates_request_body(app_in_demo: Any) -> None:
    """Missing ``message`` triggers FastAPI's automatic 422."""

    transport = ASGITransport(app=app_in_demo)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post("/api/v1/insights/chat", json={})
    assert response.status_code == 422


@pytest.mark.asyncio
async def test_chat_validates_empty_message(app_in_demo: Any) -> None:
    """The model validator rejects whitespace-only / empty messages."""

    transport = ASGITransport(app=app_in_demo)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post("/api/v1/insights/chat", json={"message": ""})
    assert response.status_code == 422


@pytest.mark.asyncio
async def test_chat_endpoint_is_registered_under_correct_prefix(
    app_in_demo: Any,
) -> None:
    """Only ``/api/v1/insights/chat`` accepts the chat POST; aliases 404."""

    transport = ASGITransport(app=app_in_demo)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        wrong = await client.post(
            "/insights/chat", json={"message": "x"}
        )
        right = await client.post(
            "/api/v1/insights/chat",
            json={"message": "x", "page_context": {"page": "var-dashboard"}},
        )
    assert wrong.status_code == 404
    assert right.status_code == 200

"""Acceptance tests for the saved-query run route.

These tests exercise the FastAPI app's saved-query run endpoint
(``POST /api/v1/insights/queries/{id}/run``) over an in-process ASGI
transport. ``DEMO_MODE=true`` (set both in the acceptance command and
inside ``_force_demo_mode``) forces the chat-client factory to return
:class:`CannedCopilotChatClient`, so the stream is deterministic and no
external dependency is touched.

The run route loads a built-in saved-query template by id, interpolates
the param values from the request body into the template's
``prompt_template``, and streams the result through the *same*
``CopilotChatClient`` the ``/chat`` route uses. The SSE wire contract is
therefore identical to ``/chat``'s:

* Content-Type starts with ``text/event-stream``.
* The body contains ``data: <ChatChunk-json>\\n\\n`` frames.
* A terminal frame closes the stream with ``done == True`` and the
  ``model`` / ``mode`` stamps merged into the JSON payload.
* When a chunk carries citations the route prefixes a frame with
  ``event: source\\n`` followed by a JSON list of citations.

Run-route-specific behaviour also asserted here:

* An unknown template id returns ``404``.
* A request missing a required param returns a client error (422).
"""

from __future__ import annotations

import json
import os
from collections.abc import AsyncIterator
from typing import Any

import httpx
import pytest
import pytest_asyncio
from httpx import ASGITransport

pytestmark = pytest.mark.unit


def _force_demo_mode() -> Any:
    """Set ``DEMO_MODE=true`` and return the freshly imported FastAPI app.

    Mirrors :func:`tests.test_chat_acceptance._force_demo_mode` — the env
    var must be set BEFORE the app module is imported so the lifespan
    constructs the canned chat client.
    """

    os.environ["DEMO_MODE"] = "true"
    from kinetix_insights.app import app  # imported after env is set

    return app


def _split_sse_frames(raw: bytes) -> list[str]:
    """Split a raw SSE byte stream into a list of frame strings."""

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


async def _post_run(
    app: Any, template_id: str, body: dict[str, Any]
) -> tuple[httpx.Response, list[str]]:
    """POST to the run endpoint and return (response, list-of-frames)."""

    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post(
            f"/api/v1/insights/queries/{template_id}/run", json=body
        )
    frames = _split_sse_frames(response.content)
    return response, frames


@pytest_asyncio.fixture()
async def app_in_demo() -> AsyncIterator[Any]:
    """Force DEMO_MODE on, drive the FastAPI lifespan, and yield the app."""

    app = _force_demo_mode()
    async with app.router.lifespan_context(app):
        yield app


@pytest.mark.asyncio
async def test_run_endpoint_returns_sse_content_type(app_in_demo: Any) -> None:
    """A valid run advertises ``text/event-stream`` for the SSE channel."""

    response, _ = await _post_run(
        app_in_demo, "limit-breaches", {"params": {"book_id": "fx-main"}}
    )
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")


@pytest.mark.asyncio
async def test_run_endpoint_streams_data_frames(app_in_demo: Any) -> None:
    """At least one delta frame and a terminal ``done == true`` frame stream out."""

    _, frames = await _post_run(
        app_in_demo, "limit-breaches", {"params": {"book_id": "fx-main"}}
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
async def test_run_terminal_frame_includes_model_and_mode(app_in_demo: Any) -> None:
    """The terminal frame echoes the canned client's model / mode stamps."""

    _, frames = await _post_run(
        app_in_demo, "limit-breaches", {"params": {"book_id": "fx-main"}}
    )
    terminal = _frame_data(frames[-1])
    assert isinstance(terminal, dict)
    assert terminal["done"] is True
    assert terminal["mode"] == "canned"
    assert terminal["model"] == "canned-chat"


@pytest.mark.asyncio
async def test_run_terminal_frame_includes_session_and_conversation_ids(
    app_in_demo: Any,
) -> None:
    """The terminal frame's JSON merges session_id + conversation_id."""

    _, frames = await _post_run(
        app_in_demo, "limit-breaches", {"params": {"book_id": "fx-main"}}
    )
    terminal = _frame_data(frames[-1])
    assert isinstance(terminal, dict)
    assert isinstance(terminal["session_id"], str) and terminal["session_id"]
    assert isinstance(terminal["conversation_id"], str) and terminal["conversation_id"]


@pytest.mark.asyncio
async def test_run_emits_source_event_for_citations(app_in_demo: Any) -> None:
    """A frame with citations is preceded by an ``event: source`` frame."""

    _, frames = await _post_run(
        app_in_demo, "limit-breaches", {"params": {"book_id": "fx-main"}}
    )
    source_frames = [f for f in frames if _frame_event(f) == "source"]
    assert source_frames, "expected at least one event: source frame"
    citations_payload = _frame_data(source_frames[0])
    assert isinstance(citations_payload, list) and citations_payload
    first = citations_payload[0]
    assert isinstance(first, dict)
    assert "tool" in first and "result_field" in first


@pytest.mark.asyncio
async def test_run_accepts_multi_param_template(app_in_demo: Any) -> None:
    """A template with several required params interpolates all of them."""

    response, frames = await _post_run(
        app_in_demo,
        "top-positions-risk-contribution",
        {"params": {"book_id": "fx-main", "top_n": 5}},
    )
    assert response.status_code == 200
    terminal = _frame_data(frames[-1])
    assert isinstance(terminal, dict)
    assert terminal["done"] is True


@pytest.mark.asyncio
async def test_run_unknown_template_id_returns_404(app_in_demo: Any) -> None:
    """An id with no matching template file is rejected with 404."""

    response, _ = await _post_run(
        app_in_demo, "no-such-template", {"params": {"book_id": "fx-main"}}
    )
    assert response.status_code == 404


@pytest.mark.asyncio
async def test_run_missing_required_param_returns_client_error(
    app_in_demo: Any,
) -> None:
    """Omitting a required param is a client error (422)."""

    response, _ = await _post_run(app_in_demo, "limit-breaches", {"params": {}})
    assert response.status_code == 422


@pytest.mark.asyncio
async def test_run_missing_one_of_several_required_params_returns_client_error(
    app_in_demo: Any,
) -> None:
    """A multi-param template rejects a body missing any one required param."""

    response, _ = await _post_run(
        app_in_demo,
        "top-positions-risk-contribution",
        {"params": {"book_id": "fx-main"}},
    )
    assert response.status_code == 422


@pytest.mark.asyncio
async def test_run_endpoint_is_registered_under_correct_prefix(
    app_in_demo: Any,
) -> None:
    """Only ``/api/v1/insights/queries/{id}/run`` accepts the run POST."""

    transport = ASGITransport(app=app_in_demo)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        wrong = await client.post(
            "/insights/queries/limit-breaches/run",
            json={"params": {"book_id": "fx-main"}},
        )
        right = await client.post(
            "/api/v1/insights/queries/limit-breaches/run",
            json={"params": {"book_id": "fx-main"}},
        )
    assert wrong.status_code == 404
    assert right.status_code == 200

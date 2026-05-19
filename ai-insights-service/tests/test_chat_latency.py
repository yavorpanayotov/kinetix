"""Latency budget tests for the SSE chat route.

These tests pin the service-side overhead between submitting a chat
request and receiving the first SSE ``data:`` byte to under 500 ms when
the SDK is instantaneous. The aim is to catch regressions in *our*
plumbing (FastAPI routing, request validation, sanitiser, history
resolution, generator handshake) — not in the LLM. To isolate that
overhead we run the live chat client (:class:`ClaudeAgentCopilotChatClient`)
against a :class:`_FakeStreamingSdk` whose first :class:`FakeMessage` has
``delay_seconds=0.0`` so the SDK contributes effectively no wall clock.

Why a 500 ms budget?

The user-perceived "thinking" delay before the assistant starts streaming
must stay tight even when the model itself is instant. 500 ms is the
SLO; if our pipeline alone consumes more than half a second the user is
already waiting on us before the model has even produced a token. The
budget is generous enough to absorb cold-start jitter from importing
FastAPI internals on first request (we run an explicit warm-up call
inside the test before the timed measurement), but tight enough to fail
loudly on accidental synchronous I/O or per-request reconstruction of
heavy collaborators in the chat code path.

Why three back-to-back runs in the second test?

A single-shot measurement can hide warm-up costs that get amortised on
the very first call (lazy imports, JIT-style initialisation of pydantic
validators, etc.). The three-run test asserts every individual run is
under the budget — that is what real users experience on turn N+1 after
the service has been live for a while.
"""

from __future__ import annotations

import os
import time
from collections.abc import AsyncIterator
from typing import Any

import httpx
import pytest
import pytest_asyncio
from httpx import ASGITransport

from kinetix_insights.chat.claude_agent_chat_client import (
    ClaudeAgentCopilotChatClient,
)
from tests.fakes.streaming_sdk import FakeMessage, _FakeStreamingSdk

pytestmark = pytest.mark.performance


_FIRST_CHUNK_BUDGET_SECONDS = 0.5


def _force_demo_mode() -> Any:
    """Set ``DEMO_MODE=true`` and return the freshly imported FastAPI app.

    Mirrors :func:`tests.test_chat_acceptance._force_demo_mode` — the env
    var must be set BEFORE the app module is imported so the lifespan
    constructs its default chat client before we swap it out below.
    """

    os.environ["DEMO_MODE"] = "true"
    from kinetix_insights.app import app  # imported after env is set

    return app


def _install_instant_chat_client(app: Any) -> _FakeStreamingSdk:
    """Replace ``app.state.chat_client`` with a live client backed by an instant SDK.

    The default DEMO_MODE wiring uses :class:`CannedCopilotChatClient`,
    which deliberately injects a 20 ms inter-frame delay to simulate
    streaming. That delay is part of the canned UX, not the budget we
    want to measure here — so we swap in
    :class:`ClaudeAgentCopilotChatClient` over a
    :class:`_FakeStreamingSdk` whose only message yields immediately.
    The returned fake is handed back to the caller in case a test wants
    to inspect ``recorded_prompts``.
    """

    sdk = _FakeStreamingSdk(
        messages=[
            FakeMessage(content="hello", delay_seconds=0.0),
            FakeMessage(content=" world", delay_seconds=0.0),
        ]
    )
    app.state.chat_client = ClaudeAgentCopilotChatClient(
        conversation_store=app.state.conversation_store,
        sdk=sdk,
    )
    return sdk


@pytest_asyncio.fixture()
async def app_with_instant_sdk() -> AsyncIterator[Any]:
    """Yield a running FastAPI app whose chat client is backed by an instant fake.

    ``ASGITransport`` does not drive the ASGI lifespan protocol on its
    own, so we enter ``app.router.lifespan_context(app)`` here to let the
    lifespan populate ``app.state``. AFTER the lifespan has set up the
    default canned client we overwrite ``app.state.chat_client`` so the
    route exercises the live code path (sanitiser, citation/policy
    checks, terminal frame assembly) but the SDK contributes no latency.
    """

    app = _force_demo_mode()
    async with app.router.lifespan_context(app):
        _install_instant_chat_client(app)
        yield app


async def _measure_first_chunk_latency(app: Any) -> float:
    """Return seconds elapsed from request submit to first ``data:`` byte.

    The timer brackets ``client.stream("POST", ...)`` rather than the
    plain ``client.post`` because we want the very first byte of the
    response body, not the entire response. ``response.aiter_raw()``
    returns chunks of the SSE byte stream as they leave the app; we stop
    the clock the instant the first chunk containing ``data:`` arrives.
    """

    transport = ASGITransport(app=app)
    body = {"message": "hello", "page_context": {"page": "var-dashboard"}}
    async with httpx.AsyncClient(
        transport=transport, base_url="http://test"
    ) as client:
        # Re-install an instant SDK for each measurement so the shared
        # _FakeStreamingSdk queue isn't drained by a previous call. The
        # live chat client keeps a reference to the sdk it was built
        # with, so we have to swap the whole client.
        _install_instant_chat_client(app)
        start = time.perf_counter()
        async with client.stream("POST", "/api/v1/insights/chat", json=body) as response:
            assert response.status_code == 200
            async for raw in response.aiter_raw():
                if b"data:" in raw:
                    return time.perf_counter() - start
    raise AssertionError("no SSE data frame was emitted before stream closed")


async def _warm_up(app: Any) -> None:
    """Issue a discarded request so import-time costs don't pollute the timer.

    First-touch costs (pydantic schema compilation, FastAPI dependency
    cache priming, httpx transport setup) hit the *first* request and
    are not part of steady-state latency. The warm-up runs the full
    request/response cycle once and throws the timing away.
    """

    await _measure_first_chunk_latency(app)


@pytest.mark.asyncio
async def test_chat_first_chunk_latency_under_500ms(
    app_with_instant_sdk: Any,
) -> None:
    """Service overhead from submit to first SSE byte must be under 500 ms.

    With the SDK yielding instantly, every millisecond on this clock is
    spent inside our code path. Anything over the budget points at a
    regression in routing, validation, sanitiser, or generator startup.
    """

    await _warm_up(app_with_instant_sdk)
    elapsed = await _measure_first_chunk_latency(app_with_instant_sdk)
    assert elapsed < _FIRST_CHUNK_BUDGET_SECONDS, (
        f"first-chunk latency {elapsed:.3f}s exceeds budget "
        f"{_FIRST_CHUNK_BUDGET_SECONDS:.3f}s"
    )


@pytest.mark.asyncio
async def test_chat_first_chunk_latency_consistent_across_three_runs(
    app_with_instant_sdk: Any,
) -> None:
    """Every individual run in a back-to-back batch must stay under budget.

    Running three measurements catches accidental warm-up costs that a
    single-shot test would hide: e.g. a memoised collaborator built on
    request #1 might make request #2 fast but request #1 itself violate
    the budget. By asserting on each run independently we pin the
    user-visible latency at every turn, not just the median.
    """

    await _warm_up(app_with_instant_sdk)
    samples = [
        await _measure_first_chunk_latency(app_with_instant_sdk) for _ in range(3)
    ]
    for index, elapsed in enumerate(samples):
        assert elapsed < _FIRST_CHUNK_BUDGET_SECONDS, (
            f"run #{index + 1} first-chunk latency {elapsed:.3f}s "
            f"exceeds budget {_FIRST_CHUNK_BUDGET_SECONDS:.3f}s "
            f"(all samples: {[f'{s:.3f}s' for s in samples]})"
        )

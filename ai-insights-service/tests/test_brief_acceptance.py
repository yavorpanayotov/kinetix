"""Acceptance + unit tests for the morning-brief route, store, and scheduler.

These cover checkbox 6.7 of ``docs/plans/ai-v2.md``:

* ``GET /api/v1/insights/brief/today`` — 200 when the brief is ready,
  202 (with a ``Retry-After`` header) while a brief is mid-generation,
  and an on-demand inline generation (returning 200) when no brief
  exists yet for the day.
* :class:`BriefStore` — the per-day in-process cache keyed by
  ``(user_id, date)`` that serves re-requests without regenerating and
  treats a prior day's entry as absent.
* :func:`run_brief_scheduler` — the 06:30 background loop, driven here
  with injected ``now`` / ``sleep`` so no real wall-clock time passes.

The route tests mirror :mod:`tests.test_chat_acceptance`: ``DEMO_MODE``
is set before importing the app so the canned brief client is selected,
and the FastAPI lifespan is entered explicitly because ``ASGITransport``
does not run the ASGI lifespan protocol itself.
"""

from __future__ import annotations

import asyncio
import os
from collections.abc import AsyncIterator
from datetime import datetime, timezone
from typing import Any

import httpx
import pytest
import pytest_asyncio
from httpx import ASGITransport

pytestmark = pytest.mark.unit


def _force_demo_mode() -> Any:
    """Set ``DEMO_MODE=true`` and return the freshly imported FastAPI app.

    The env var must be set BEFORE the app module is imported so the
    lifespan's brief-client factory selects :class:`CannedBriefClient`.
    """

    os.environ["DEMO_MODE"] = "true"
    from kinetix_insights.app import app  # imported after env is set

    return app


@pytest_asyncio.fixture()
async def app_in_demo() -> AsyncIterator[Any]:
    """Force DEMO_MODE on, drive the FastAPI lifespan, and yield the app.

    Entering ``app.router.lifespan_context`` populates
    ``app.state.brief_store`` / ``app.state.brief_client`` and starts —
    then, on exit, cleanly cancels — the background brief scheduler.
    """

    app = _force_demo_mode()
    async with app.router.lifespan_context(app):
        yield app


async def _get_brief(
    app: Any, headers: dict[str, str] | None = None
) -> httpx.Response:
    """GET the brief endpoint over an in-process ASGI transport."""

    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        return await client.get("/api/v1/insights/brief/today", headers=headers)


# --------------------------------------------------------------------------
# Route tests (via the app)
# --------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_brief_today_generates_on_demand_and_returns_200(
    app_in_demo: Any,
) -> None:
    """First request with no headers generates inline and returns 200."""

    response = await _get_brief(app_in_demo)
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ready"
    assert body["briefs"], "expected at least one brief"
    assert body["mode"] == "canned"


@pytest.mark.asyncio
async def test_brief_today_second_request_is_served_from_store(
    app_in_demo: Any,
) -> None:
    """A second request is served from the store without regenerating."""

    first = await _get_brief(app_in_demo)
    assert first.status_code == 200

    # Replace the brief client with one that explodes — if the route
    # regenerates instead of reading the store, the test fails.
    class _ExplodingBriefClient:
        async def generate_brief(self, *, user: Any) -> Any:
            raise AssertionError("brief client must not be called again")

    app_in_demo.state.brief_client = _ExplodingBriefClient()

    second = await _get_brief(app_in_demo)
    assert second.status_code == 200
    assert second.json()["status"] == "ready"


@pytest.mark.asyncio
async def test_brief_today_returns_202_when_generating(app_in_demo: Any) -> None:
    """A brief mid-generation yields 202 with a Retry-After header."""

    app_in_demo.state.brief_store.mark_generating("anonymous")

    response = await _get_brief(app_in_demo)
    assert response.status_code == 202
    assert response.json() == {"status": "generating", "retry_after": 5}
    assert response.headers["retry-after"] == "5"


@pytest.mark.asyncio
async def test_brief_today_resolves_user_from_headers(app_in_demo: Any) -> None:
    """``X-User-Id`` / ``X-User-Books`` headers scope the stored brief."""

    response = await _get_brief(
        app_in_demo,
        headers={"X-User-Id": "trader-1", "X-User-Books": "fx-main"},
    )
    assert response.status_code == 200
    assert app_in_demo.state.brief_store.status_for("trader-1") == "ready"


@pytest.mark.asyncio
async def test_brief_today_response_shape(app_in_demo: Any) -> None:
    """The 200 body carries the documented top-level and per-brief keys."""

    response = await _get_brief(app_in_demo)
    assert response.status_code == 200
    body = response.json()
    assert set(body) >= {"status", "briefs", "mode", "generated_at"}
    first = body["briefs"][0]
    assert set(first) >= {"book_id", "sections", "generated_at", "mode"}


# --------------------------------------------------------------------------
# BriefStore unit tests
# --------------------------------------------------------------------------


def _make_brief(book_id: str = "fx-main") -> Any:
    """Build a minimal valid :class:`MorningBrief` for store/scheduler tests."""

    from kinetix_insights.brief.models import MorningBrief

    return MorningBrief(
        book_id=book_id,
        sections=[],
        generated_at=datetime(2026, 5, 20, 6, 30, tzinfo=timezone.utc),
        mode="canned",
    )


def test_brief_store_absent_initially() -> None:
    """A fresh store reports the brief as absent for any user."""

    from kinetix_insights.brief.brief_store import BriefStore

    store = BriefStore()
    assert store.status_for("u1") == "absent"
    assert store.get("u1") is None


def test_brief_store_mark_generating_then_status() -> None:
    """``mark_generating`` flips the status to ``generating``."""

    from kinetix_insights.brief.brief_store import BriefStore

    store = BriefStore()
    store.mark_generating("u1")
    assert store.status_for("u1") == "generating"
    assert store.get("u1") is None


def test_brief_store_put_then_get_and_ready() -> None:
    """``put`` stores the briefs and flips the status to ``ready``."""

    from kinetix_insights.brief.brief_store import BriefStore

    store = BriefStore()
    briefs = [_make_brief()]
    store.put("u1", briefs)
    assert store.status_for("u1") == "ready"
    assert store.get("u1") == briefs


def test_brief_store_prior_day_entry_treated_as_absent() -> None:
    """An entry from a prior UTC day is treated as absent the next day."""

    from kinetix_insights.brief.brief_store import BriefStore

    clock = {"now": datetime(2026, 5, 19, 8, 0, tzinfo=timezone.utc)}
    store = BriefStore(now=lambda: clock["now"])
    store.put("u1", [_make_brief()])
    assert store.status_for("u1") == "ready"

    clock["now"] = datetime(2026, 5, 20, 8, 0, tzinfo=timezone.utc)
    assert store.status_for("u1") == "absent"
    assert store.get("u1") is None


# --------------------------------------------------------------------------
# Scheduler unit tests
# --------------------------------------------------------------------------


class _FakeBriefClient:
    """A :class:`BriefClient` whose result is fixed per user_id."""

    def __init__(self, *, by_user: dict[str, list[Any]] | None = None,
                 default: list[Any] | None = None,
                 fail_for: set[str] | None = None) -> None:
        self._by_user = by_user or {}
        self._default = default or [_make_brief()]
        self._fail_for = fail_for or set()

    async def generate_brief(self, *, user: Any) -> list[Any]:
        if user.user_id in self._fail_for:
            raise RuntimeError(f"boom for {user.user_id}")
        return self._by_user.get(user.user_id, self._default)


class _StopScheduler(Exception):
    """Sentinel raised by the fake sleep to terminate the scheduler loop."""


@pytest.mark.asyncio
async def test_scheduler_generates_at_target_time() -> None:
    """One loop iteration generates and stores a brief for the user."""

    from kinetix_insights.brief.brief_store import BriefStore
    from kinetix_insights.brief.scheduler import run_brief_scheduler
    from kinetix_insights.clients.user_context import UserContext

    store = BriefStore()
    expected = [_make_brief(book_id="fx-main")]
    client = _FakeBriefClient(default=expected)
    user = UserContext(user_id="demo-trader", books=("fx-main",))

    async def fake_sleep(_seconds: float) -> None:
        raise _StopScheduler

    with pytest.raises(_StopScheduler):
        await run_brief_scheduler(
            brief_store=store,
            brief_client=client,
            user_provider=lambda: [user],
            now=lambda: datetime(2026, 5, 20, 5, 0, tzinfo=timezone.utc),
            sleep=fake_sleep,
        )


@pytest.mark.asyncio
async def test_scheduler_computes_delay_until_next_0630() -> None:
    """The first sleep waits the right number of seconds until 06:30."""

    from kinetix_insights.brief.brief_store import BriefStore
    from kinetix_insights.brief.scheduler import run_brief_scheduler
    from kinetix_insights.clients.user_context import UserContext

    delays: list[float] = []

    async def record_then_stop(seconds: float) -> None:
        delays.append(seconds)
        raise _StopScheduler

    user = UserContext(user_id="demo-trader", books=("fx-main",))

    # 05:00 -> 06:30 same day == 1h30m == 5400s.
    with pytest.raises(_StopScheduler):
        await run_brief_scheduler(
            brief_store=BriefStore(),
            brief_client=_FakeBriefClient(),
            user_provider=lambda: [user],
            now=lambda: datetime(2026, 5, 20, 5, 0, 0),
            sleep=record_then_stop,
        )
    assert delays[0] == pytest.approx(5400.0)

    # 07:00 -> next 06:30 is tomorrow == 23h30m == 84600s.
    delays.clear()
    with pytest.raises(_StopScheduler):
        await run_brief_scheduler(
            brief_store=BriefStore(),
            brief_client=_FakeBriefClient(),
            user_provider=lambda: [user],
            now=lambda: datetime(2026, 5, 20, 7, 0, 0),
            sleep=record_then_stop,
        )
    assert delays[0] == pytest.approx(84600.0)


@pytest.mark.asyncio
async def test_scheduler_generates_brief_for_provided_user() -> None:
    """After one tick the store holds the user's generated brief."""

    from kinetix_insights.brief.brief_store import BriefStore
    from kinetix_insights.brief.scheduler import run_brief_scheduler
    from kinetix_insights.clients.user_context import UserContext

    store = BriefStore()
    expected = [_make_brief(book_id="fx-main")]
    client = _FakeBriefClient(default=expected)
    user = UserContext(user_id="demo-trader", books=("fx-main",))

    calls = {"n": 0}

    async def sleep_then_stop(_seconds: float) -> None:
        # First sleep: the delay-until-06:30. Let the loop generate,
        # then the second sleep terminates it.
        calls["n"] += 1
        if calls["n"] >= 2:
            raise _StopScheduler

    with pytest.raises(_StopScheduler):
        await run_brief_scheduler(
            brief_store=store,
            brief_client=client,
            user_provider=lambda: [user],
            now=lambda: datetime(2026, 5, 20, 5, 0, tzinfo=timezone.utc),
            sleep=sleep_then_stop,
        )
    assert store.status_for("demo-trader") == "ready"
    assert store.get("demo-trader") == expected


@pytest.mark.asyncio
async def test_scheduler_one_user_failure_does_not_break_the_loop() -> None:
    """A failure generating one user's brief still lets others succeed."""

    from kinetix_insights.brief.brief_store import BriefStore
    from kinetix_insights.brief.scheduler import run_brief_scheduler
    from kinetix_insights.clients.user_context import UserContext

    store = BriefStore()
    good = [_make_brief(book_id="rates-main")]
    client = _FakeBriefClient(
        by_user={"u-bad": [], "u-good": good},
        fail_for={"u-bad"},
    )
    users = [
        UserContext(user_id="u-bad", books=("fx",)),
        UserContext(user_id="u-good", books=("rates-main",)),
    ]

    calls = {"n": 0}

    async def sleep_then_stop(_seconds: float) -> None:
        calls["n"] += 1
        if calls["n"] >= 2:
            raise _StopScheduler

    with pytest.raises(_StopScheduler):
        await run_brief_scheduler(
            brief_store=store,
            brief_client=client,
            user_provider=lambda: users,
            now=lambda: datetime(2026, 5, 20, 5, 0, tzinfo=timezone.utc),
            sleep=sleep_then_stop,
        )
    assert store.status_for("u-bad") == "absent"
    assert store.status_for("u-good") == "ready"
    assert store.get("u-good") == good


# --------------------------------------------------------------------------
# Lifespan task hygiene
# --------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_lifespan_cancels_brief_scheduler_task() -> None:
    """The background scheduler task is cancelled when the lifespan exits."""

    app = _force_demo_mode()
    async with app.router.lifespan_context(app):
        task = app.state._brief_task
        assert isinstance(task, asyncio.Task)
        assert not task.done()
    assert task.done()

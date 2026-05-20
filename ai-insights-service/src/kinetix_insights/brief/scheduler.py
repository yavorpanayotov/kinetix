"""Background loop that pre-generates morning briefs at 06:30 local.

:func:`run_brief_scheduler` is an infinite ``async`` loop: it sleeps
until the next ``target_hour:target_minute`` in the server's local
time, then generates a brief for every user the ``user_provider``
yields and stores each result in the :class:`BriefStore`. A trader who
opens the app after 06:30 therefore gets their brief straight from the
store (200) rather than waiting on an on-demand generation.

Determinism in tests
--------------------
``now`` and ``sleep`` are injected so tests can drive the loop without
any real wall-clock wait: a fake ``sleep`` that raises after N calls
terminates the loop cleanly, and a fixed ``now`` makes the
delay-until-06:30 arithmetic assertable. In production they default to
:func:`datetime.now` and :func:`asyncio.sleep`.

Resilience
----------
Per-user generation is wrapped in ``try/except`` so one user's failure
(an upstream timeout, a malformed book) never aborts the run for the
others — consistent with :class:`MorningBriefGenerator`'s
fail-soft ethos.
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Awaitable, Callable
from datetime import datetime, timedelta

from kinetix_insights.brief.brief_store import BriefStore
from kinetix_insights.brief.canned import BriefClient
from kinetix_insights.clients.user_context import UserContext

logger = logging.getLogger(__name__)


def _seconds_until_next(
    now: datetime, *, target_hour: int, target_minute: int
) -> float:
    """Return the seconds from ``now`` to the next ``HH:MM`` instant.

    If ``HH:MM`` has already passed (or is exactly now) for ``now``'s
    date, the next occurrence is the same time tomorrow.
    """

    target = now.replace(
        hour=target_hour,
        minute=target_minute,
        second=0,
        microsecond=0,
    )
    if target <= now:
        target += timedelta(days=1)
    return (target - now).total_seconds()


async def run_brief_scheduler(
    *,
    brief_store: BriefStore,
    brief_client: BriefClient,
    user_provider: Callable[[], list[UserContext]],
    now: Callable[[], datetime] | None = None,
    sleep: Callable[[float], Awaitable[None]] | None = None,
    target_hour: int = 6,
    target_minute: int = 30,
) -> None:
    """Loop forever: sleep until the next 06:30, then generate briefs.

    Each iteration sleeps until ``target_hour:target_minute``, then for
    every :class:`UserContext` from ``user_provider`` calls
    :meth:`BriefClient.generate_brief` and stores the result in
    ``brief_store``. The loop then repeats for the following day.

    The function returns only when cancelled (``asyncio.CancelledError``
    propagates) — callers run it as a background task and cancel it on
    shutdown.
    """

    clock = now if now is not None else datetime.now
    nap = sleep if sleep is not None else asyncio.sleep

    while True:
        delay = _seconds_until_next(
            clock(), target_hour=target_hour, target_minute=target_minute
        )
        await nap(delay)
        for user in user_provider():
            try:
                briefs = await brief_client.generate_brief(user=user)
                brief_store.put(user.user_id, briefs)
            except Exception:  # noqa: BLE001 — one user's failure must not abort the run
                logger.exception(
                    "scheduled brief generation failed for user %s",
                    user.user_id,
                )

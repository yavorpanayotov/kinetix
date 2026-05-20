"""In-process cache of the day's generated morning briefs.

The brief is generated once per trading day (by the 06:30 lifespan
task, or on-demand on first request). The store keys briefs by
``(user_id, date)`` so re-requests within the day are served from
memory without regenerating. A new UTC day invalidates everything.

Day boundary
------------
"Today" is the *date component* of ``now()``. The default clock is
``datetime.now(timezone.utc)``, so the store's day-key uses the UTC
date. Checkbox 6.7's "06:30 local" wording applies to the *scheduler*
(see :mod:`kinetix_insights.brief.scheduler`, which fires at 06:30 in
the server's local time); the store deliberately uses a single,
consistent UTC date for its cache key. For v2 this is intentionally
simple — the two never disagree in a way that matters because the
scheduler stores into the store immediately after firing.

An entry whose ``date`` predates today is treated exactly as if it
were absent: a stale brief is never served and a re-request triggers a
fresh generation.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import date, datetime, timezone

from kinetix_insights.brief.models import MorningBrief


def _utc_now() -> datetime:
    """Default clock — the current time in UTC."""

    return datetime.now(timezone.utc)


@dataclass
class _DayEntry:
    """One user's brief state for a single day.

    ``status`` is ``"generating"`` while a generation is in flight and
    ``"ready"`` once :meth:`BriefStore.put` has stored the result;
    ``briefs`` is populated only when ``status == "ready"``.
    """

    date: date
    status: str  # "generating" | "ready"
    briefs: list[MorningBrief]


class BriefStore:
    """Per-day, per-user cache of generated :class:`MorningBrief` lists.

    The store is a plain in-process dict — the AI insights service runs
    as a single replica for v2, so there is no cross-process sharing to
    worry about. Each :meth:`status_for` / :meth:`get` call first
    discards any entry whose ``date`` is not today, so callers never
    see a stale brief.
    """

    def __init__(self, *, now: Callable[[], datetime] | None = None) -> None:
        self._now = now if now is not None else _utc_now
        self._entries: dict[str, _DayEntry] = {}

    def _today(self) -> date:
        """Return today's date per the injected clock."""

        return self._now().date()

    def _current_entry(self, user_id: str) -> _DayEntry | None:
        """Return the user's entry if it is for today, else ``None``.

        A prior-day entry is evicted so it cannot be served and a fresh
        generation is triggered on the next request.
        """

        entry = self._entries.get(user_id)
        if entry is None:
            return None
        if entry.date != self._today():
            del self._entries[user_id]
            return None
        return entry

    def status_for(self, user_id: str) -> str:
        """Return ``'absent'`` | ``'generating'`` | ``'ready'`` for today."""

        entry = self._current_entry(user_id)
        if entry is None:
            return "absent"
        return entry.status

    def get(self, user_id: str) -> list[MorningBrief] | None:
        """Return today's briefs for the user, or ``None`` if not ready."""

        entry = self._current_entry(user_id)
        if entry is None or entry.status != "ready":
            return None
        return entry.briefs

    def mark_generating(self, user_id: str) -> None:
        """Mark the user's brief for today as mid-generation."""

        self._entries[user_id] = _DayEntry(
            date=self._today(), status="generating", briefs=[]
        )

    def put(self, user_id: str, briefs: list[MorningBrief]) -> None:
        """Store today's briefs for the user; flip the status to ``ready``."""

        self._entries[user_id] = _DayEntry(
            date=self._today(), status="ready", briefs=briefs
        )

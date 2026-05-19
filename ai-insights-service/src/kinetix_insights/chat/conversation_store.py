"""Conversation history persistence for the chat endpoint.

Chat sessions accumulate turns over the lifetime of a conversation
(see plan ai-v2.md §3.3). Each turn is a (role, content) pair; the
store keys turns by ``conversation_id``. The route handler reads the
prior turns out of the store, appends the new user message, calls
the SDK, then appends the assistant response.

This module defines:

* ``ConversationTurn`` — frozen dataclass for one role+content entry.
* ``ConversationStore`` — narrow async protocol the chat client
  depends on. Two implementations are anticipated: the in-memory one
  shipped here (v2 default) and the Redis-backed one that lands in
  the PR 10 hardening checkbox.
* ``InMemoryConversationStore`` — process-local impl. ``OrderedDict``
  preserves insertion order so the oldest entries can be expired
  cheaply at the head; per-entry ``last_touched_at`` timestamps make
  the TTL semantics deterministic for tests and the future Redis
  migration.

TTL is **24h** from last touch — appending a turn refreshes the
timestamp on the whole conversation. Expired entries are evicted
lazily on every ``get``/``append``/``clear`` call (no background
sweeper).

A note on eviction ordering: ``append`` moves the touched entry to
the END of the ``OrderedDict`` so the head is always the
least-recently-written conversation. Because reads do NOT refresh
the timestamp, the head is a faithful proxy for "oldest by last
write" — which is exactly what the TTL is keyed on. The sweep walks
the head forward while entries are expired and stops at the first
live one; the worst case is that a conversation briefly sits past
its TTL until the next operation triggers a sweep, never beyond
``2 * ttl`` in any realistic workload.
"""

from __future__ import annotations

from collections import OrderedDict
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Protocol, runtime_checkable


_DEFAULT_TTL = timedelta(hours=24)


@dataclass(frozen=True)
class ConversationTurn:
    """One role+content entry in a conversation history.

    ``role`` is one of ``"user"``, ``"assistant"``, or ``"system"`` —
    matching the SDK's message-role vocabulary. ``timestamp`` is the
    wall-clock time the turn was generated; the store does not derive
    or override it.
    """

    role: str
    content: str
    timestamp: datetime


@runtime_checkable
class ConversationStore(Protocol):
    """Async store of conversation history keyed by ``conversation_id``.

    Implementations may be process-local (see
    :class:`InMemoryConversationStore`) or backed by an external
    store (e.g. Redis, landing in the PR 10 hardening item). All
    methods are coroutines so callers can depend on a single contract
    regardless of where the data lives.

    ``get`` returns an empty list for unknown or expired ids — empty
    history is a normal state, not an error. Returned lists are
    defensive copies; callers may mutate them freely without
    affecting the store.
    """

    async def append(
        self, conversation_id: str, turn: ConversationTurn
    ) -> None: ...  # pragma: no cover - structural only

    async def get(
        self, conversation_id: str
    ) -> list[ConversationTurn]: ...  # pragma: no cover - structural only

    async def clear(
        self, conversation_id: str
    ) -> None: ...  # pragma: no cover - structural only


def _default_now() -> datetime:
    """Return the current UTC time. Indirected so tests can inject a fake."""

    return datetime.now(timezone.utc)


class InMemoryConversationStore:
    """Process-local :class:`ConversationStore` with lazy TTL eviction.

    State:

    * ``_data`` — ``OrderedDict`` of conversation_id → list of turns.
      Re-ordered to the end on every write so the head tracks the
      least-recently-written entry.
    * ``_last_touched`` — conversation_id → last-write timestamp.
      Reads do NOT update this map; only writes (``append``) do.

    Eviction is lazy: every public method first runs ``_evict_expired``,
    which walks the head of ``_data`` and pops while the head's
    timestamp is older than ``now() - ttl``. The walk stops at the
    first live entry, so cost is O(expired) per call rather than
    O(N).
    """

    def __init__(
        self,
        *,
        ttl: timedelta = _DEFAULT_TTL,
        now: Callable[[], datetime] | None = None,
    ) -> None:
        self._ttl = ttl
        self._now = now if now is not None else _default_now
        self._data: OrderedDict[str, list[ConversationTurn]] = OrderedDict()
        self._last_touched: dict[str, datetime] = {}

    def _evict_expired(self) -> None:
        """Pop expired entries from the head of ``_data``.

        Uses strict ``>`` against the TTL window: an entry at exactly
        ``ttl`` seconds old is still alive; one a tick beyond is not.
        """

        cutoff = self._now() - self._ttl
        while self._data:
            oldest_id = next(iter(self._data))
            last_touched = self._last_touched.get(oldest_id)
            if last_touched is None or last_touched < cutoff:
                self._data.pop(oldest_id, None)
                self._last_touched.pop(oldest_id, None)
                continue
            break

    async def append(
        self, conversation_id: str, turn: ConversationTurn
    ) -> None:
        """Append a turn, refresh the TTL, and re-anchor at the tail.

        Re-anchoring is what keeps the head of ``_data`` honest as a
        proxy for "least recently written" so the sweep can stop at
        the first live entry.
        """

        self._evict_expired()
        bucket = self._data.get(conversation_id)
        if bucket is None:
            bucket = []
            self._data[conversation_id] = bucket
        else:
            self._data.move_to_end(conversation_id)
        bucket.append(turn)
        self._last_touched[conversation_id] = self._now()

    async def get(self, conversation_id: str) -> list[ConversationTurn]:
        """Return a defensive copy of the conversation, or ``[]`` if unknown."""

        self._evict_expired()
        bucket = self._data.get(conversation_id)
        if bucket is None:
            return []
        return list(bucket)

    async def clear(self, conversation_id: str) -> None:
        """Drop a conversation entirely. No-op if absent."""

        self._evict_expired()
        self._data.pop(conversation_id, None)
        self._last_touched.pop(conversation_id, None)

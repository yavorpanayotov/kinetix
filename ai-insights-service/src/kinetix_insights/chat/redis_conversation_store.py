"""Redis-backed :class:`ConversationStore` for the chat endpoint.

This is the PR 10 hardening implementation of the
:class:`~kinetix_insights.chat.conversation_store.ConversationStore`
protocol (see plan ai-v2.md §10.1). It is the production-grade
counterpart to :class:`~kinetix_insights.chat.conversation_store.
InMemoryConversationStore`: process-local state is replaced by a
shared Redis key space, so conversation history survives a service
restart and is consistent across horizontally-scaled replicas.

Behaviour is identical to the in-memory store from the caller's point
of view:

* ``get`` returns an empty list for unknown or expired ids, and the
  returned list is a fresh value the caller may mutate freely.
* ``append`` adds a turn and refreshes the TTL on the whole
  conversation — a write at t=23h keeps the conversation alive a
  further 24h.
* ``clear`` removes the conversation entirely.

TTL is **24h**, enforced by Redis-native key expiry rather than a
lazy sweep: every ``append`` re-writes the key with ``SET … PX`` so
its remaining TTL is reset to the full window on each write.
Millisecond precision (``PX``) is used so sub-second TTLs — handy for
proving expiry in tests without a 24h wait — survive instead of
rounding down to a zero expiry. Reads do *not* touch the TTL — they
only ``GET`` — so the in-memory store's "writes refresh, reads do
not" semantics are preserved.

Storage shape: each conversation is a single Redis string holding a
JSON array of turn objects (``{"role", "content", "timestamp"}``).
``timestamp`` is serialised as an ISO-8601 string and parsed back
into a timezone-aware :class:`datetime` on read. Keys are namespaced
under ``ai-insights:conversation:`` so the conversation key space is
isolated from anything else sharing the Redis instance.

The client is the async ``redis.asyncio`` client (``redis[hiredis]``
is already a service dependency). The store owns the connection
pool and exposes :meth:`aclose` so the FastAPI lifespan can release
it on shutdown.
"""

from __future__ import annotations

import json
from datetime import datetime, timedelta

import redis.asyncio as aioredis

from kinetix_insights.chat.conversation_store import ConversationTurn

_DEFAULT_TTL = timedelta(hours=24)
_KEY_PREFIX = "ai-insights:conversation:"


class RedisConversationStore:
    """Redis-backed :class:`ConversationStore` with native key-expiry TTL.

    A conversation is stored as one JSON-encoded string keyed by
    ``ai-insights:conversation:<conversation_id>``. ``append`` reads
    the current array, appends the new turn, writes it back and
    re-arms the 24h TTL in a single ``SET … EX`` so the whole
    conversation expires 24h after its most recent write.

    The store depends on the :class:`ConversationStore` protocol's
    contract only; it shares no state with the FastAPI app beyond the
    Redis instance pointed at by ``redis_url``.
    """

    def __init__(
        self,
        *,
        redis_url: str,
        ttl: timedelta = _DEFAULT_TTL,
        client: aioredis.Redis | None = None,
    ) -> None:
        """Create a store bound to ``redis_url``.

        ``client`` is an injection seam for tests that want to supply
        a pre-built connection; production callers pass only
        ``redis_url`` and let the store build its own pool.
        """

        self._ttl = ttl
        # Millisecond precision so sub-second TTLs (used by tests to
        # prove expiry without a 24h wait) survive — ``SET … PX`` would
        # otherwise round a fractional-second TTL down to 0 and Redis
        # rejects a zero expiry.
        self._ttl_ms = max(1, int(ttl.total_seconds() * 1000))
        self._owns_client = client is None
        self._redis: aioredis.Redis = (
            client
            if client is not None
            else aioredis.from_url(redis_url, decode_responses=True)
        )

    @staticmethod
    def _key(conversation_id: str) -> str:
        """Return the namespaced Redis key for a conversation id."""

        return f"{_KEY_PREFIX}{conversation_id}"

    @staticmethod
    def _encode(turns: list[ConversationTurn]) -> str:
        """Serialise a list of turns to a JSON array string."""

        return json.dumps(
            [
                {
                    "role": turn.role,
                    "content": turn.content,
                    "timestamp": turn.timestamp.isoformat(),
                }
                for turn in turns
            ]
        )

    @staticmethod
    def _decode(raw: str) -> list[ConversationTurn]:
        """Parse a JSON array string back into a list of turns."""

        return [
            ConversationTurn(
                role=entry["role"],
                content=entry["content"],
                timestamp=datetime.fromisoformat(entry["timestamp"]),
            )
            for entry in json.loads(raw)
        ]

    async def append(
        self, conversation_id: str, turn: ConversationTurn
    ) -> None:
        """Append a turn and re-arm the 24h TTL on the conversation.

        The whole conversation is written back under a single
        ``SET … PX`` so the key's expiry is reset to the full window
        on every write — mirroring the in-memory store, where a fresh
        write renews the TTL for all turns.
        """

        key = self._key(conversation_id)
        existing = await self._redis.get(key)
        turns = self._decode(existing) if existing is not None else []
        turns.append(turn)
        await self._redis.set(key, self._encode(turns), px=self._ttl_ms)

    async def get(self, conversation_id: str) -> list[ConversationTurn]:
        """Return the conversation's turns, or ``[]`` if unknown or expired.

        A plain ``GET`` — reads never touch the TTL, so the key
        expires 24h after its last *write*, not its last read.
        """

        raw = await self._redis.get(self._key(conversation_id))
        if raw is None:
            return []
        return self._decode(raw)

    async def clear(self, conversation_id: str) -> None:
        """Delete a conversation entirely. No-op if absent."""

        await self._redis.delete(self._key(conversation_id))

    async def aclose(self) -> None:
        """Release the Redis connection pool if this store owns it."""

        if self._owns_client:
            await self._redis.aclose()

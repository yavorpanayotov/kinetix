"""Integration tests for :class:`RedisConversationStore` (plan ai-v2.md §10.1).

These exercise the Redis-backed conversation store against a *real*
Redis instance — no fakes, no in-memory shim — so the JSON
serialisation, the ``redis.asyncio`` wire protocol, and Redis-native
key expiry are all proven end to end.

Container management
--------------------
The checkbox calls for a Testcontainers Redis integration test.
``testcontainers`` is **not** an approved dependency of
``ai-insights-service`` (the plan's pre-approved list is ``mcp``,
``aiokafka``, ``redis[hiredis]``, ``prometheus-client``), so rather
than add an unapproved package the ``redis_container`` fixture below
spins up the same official ``redis:7-alpine`` image directly through
the ``docker`` CLI — a real ephemeral container, started before the
tests and torn down after, published on a random host port. This is
the Testcontainers *pattern* (disposable real Redis) without the
library. If ``docker`` is unavailable the module is skipped, exactly
as a Testcontainers-based suite would be.

Coverage mirrors the in-memory store's behavioural contract:

* ``get`` on an unknown id returns ``[]``.
* ``append`` then ``get`` round-trips turns in insertion order, with
  ``role`` / ``content`` / ``timestamp`` preserved across JSON.
* Conversations under different ids stay isolated.
* ``clear`` removes a conversation.
* The 24h TTL is applied — the key's Redis ``TTL`` is positive and
  close to 86400s after a write.
* ``append`` re-arms the TTL on every write.
* Expiry is wired: a store with a sub-second TTL drops the
  conversation once Redis expires the key, so ``get`` returns ``[]``.
"""

from __future__ import annotations

import asyncio
import shutil
import socket
import subprocess
import time
import uuid
from collections.abc import AsyncIterator, Iterator
from datetime import datetime, timedelta, timezone

import pytest
import pytest_asyncio
import redis.asyncio as aioredis

from kinetix_insights.chat.conversation_store import ConversationTurn
from kinetix_insights.chat.redis_conversation_store import RedisConversationStore

pytestmark = pytest.mark.integration

_REDIS_IMAGE = "redis:7-alpine"
_T0 = datetime(2026, 5, 20, 8, 0, 0, tzinfo=timezone.utc)


def _docker_available() -> bool:
    """Return ``True`` if a usable ``docker`` CLI + daemon is present."""

    if shutil.which("docker") is None:
        return False
    try:
        result = subprocess.run(
            ["docker", "info"],
            capture_output=True,
            timeout=20,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return False
    return result.returncode == 0


def _free_port() -> int:
    """Return a currently-free TCP port on the loopback interface."""

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def _turn(role: str, content: str, at: datetime) -> ConversationTurn:
    return ConversationTurn(role=role, content=content, timestamp=at)


@pytest.fixture(scope="module")
def redis_url() -> Iterator[str]:
    """Start an ephemeral ``redis:7-alpine`` container, yield its URL.

    Skips the whole module if Docker is not available. The container
    is force-removed on teardown regardless of test outcome.
    """

    if not _docker_available():
        pytest.skip("docker is not available — Redis integration test skipped")

    port = _free_port()
    name = f"kinetix-redis-it-{uuid.uuid4().hex[:12]}"
    subprocess.run(
        [
            "docker",
            "run",
            "-d",
            "--rm",
            "--name",
            name,
            "-p",
            f"127.0.0.1:{port}:6379",
            _REDIS_IMAGE,
        ],
        check=True,
        capture_output=True,
        timeout=120,
    )
    try:
        _wait_for_redis(port)
        yield f"redis://127.0.0.1:{port}/0"
    finally:
        subprocess.run(
            ["docker", "rm", "-f", name],
            check=False,
            capture_output=True,
            timeout=60,
        )


def _wait_for_redis(port: int, *, timeout: float = 30.0) -> None:
    """Block until the Redis container answers PING, or fail the run."""

    deadline = time.monotonic() + timeout
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            asyncio.run(_ping(port))
            return
        except Exception as exc:  # noqa: BLE001 — retry until the deadline
            last_error = exc
            time.sleep(0.25)
    raise RuntimeError(f"Redis container did not become ready: {last_error}")


async def _ping(port: int) -> None:
    client = aioredis.from_url(f"redis://127.0.0.1:{port}/0")
    try:
        await client.ping()
    finally:
        await client.aclose()


@pytest_asyncio.fixture
async def raw_client(redis_url: str) -> AsyncIterator[aioredis.Redis]:
    """A bare async Redis client, flushed before each test for isolation."""

    client = aioredis.from_url(redis_url, decode_responses=True)
    await client.flushdb()
    try:
        yield client
    finally:
        await client.aclose()


# ---------------------------------------------------------------------------
# add / get round-trip
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_unknown_conversation_returns_empty(redis_url: str) -> None:
    """`get` on an id never written returns `[]` — empty history is normal."""

    store = RedisConversationStore(redis_url=redis_url)
    try:
        assert await store.get(f"unknown-{uuid.uuid4().hex}") == []
    finally:
        await store.aclose()


@pytest.mark.asyncio
async def test_append_then_get_round_trips_turns(redis_url: str) -> None:
    """Two appended turns come back in insertion order, fields intact."""

    store = RedisConversationStore(redis_url=redis_url)
    conv = f"conv-{uuid.uuid4().hex}"
    first = _turn("user", "what is my VaR?", _T0)
    second = _turn("assistant", "Your 95% VaR is 1.2m.", _T0 + timedelta(seconds=2))
    try:
        await store.append(conv, first)
        await store.append(conv, second)

        assert await store.get(conv) == [first, second]
    finally:
        await store.aclose()


@pytest.mark.asyncio
async def test_conversations_are_isolated_by_id(redis_url: str) -> None:
    """Turns written under different ids do not bleed into each other."""

    store = RedisConversationStore(redis_url=redis_url)
    conv_a = f"A-{uuid.uuid4().hex}"
    conv_b = f"B-{uuid.uuid4().hex}"
    turn_a = _turn("user", "from A", _T0)
    turn_b = _turn("user", "from B", _T0)
    try:
        await store.append(conv_a, turn_a)
        await store.append(conv_b, turn_b)

        assert await store.get(conv_a) == [turn_a]
        assert await store.get(conv_b) == [turn_b]
    finally:
        await store.aclose()


@pytest.mark.asyncio
async def test_clear_removes_conversation(redis_url: str) -> None:
    """After `clear` the conversation reads back as empty."""

    store = RedisConversationStore(redis_url=redis_url)
    conv = f"conv-{uuid.uuid4().hex}"
    try:
        await store.append(conv, _turn("user", "hi", _T0))
        await store.clear(conv)

        assert await store.get(conv) == []
    finally:
        await store.aclose()


# ---------------------------------------------------------------------------
# 24h TTL
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_append_applies_24h_ttl_to_the_key(
    redis_url: str, raw_client: aioredis.Redis
) -> None:
    """`append` arms a Redis-native key expiry of ~24h (86400s)."""

    store = RedisConversationStore(redis_url=redis_url)
    conv = f"conv-{uuid.uuid4().hex}"
    try:
        await store.append(conv, _turn("user", "hi", _T0))

        ttl = await raw_client.ttl(f"ai-insights:conversation:{conv}")
        # Positive and within a minute of the full 24h window — generous
        # bound so the test never flakes on slow container round-trips.
        assert ttl > 0
        assert 86340 <= ttl <= 86400
    finally:
        await store.aclose()


@pytest.mark.asyncio
async def test_append_re_arms_the_ttl_on_every_write(
    redis_url: str, raw_client: aioredis.Redis
) -> None:
    """A second `append` resets the key's TTL back to the full 24h window."""

    store = RedisConversationStore(redis_url=redis_url)
    conv = f"conv-{uuid.uuid4().hex}"
    key = f"ai-insights:conversation:{conv}"
    try:
        await store.append(conv, _turn("user", "first", _T0))
        # Shrink the TTL behind the store's back to simulate elapsed time.
        await raw_client.expire(key, 100)
        assert await raw_client.ttl(key) <= 100

        await store.append(conv, _turn("assistant", "second", _T0))

        # The second write re-armed the full window.
        ttl = await raw_client.ttl(key)
        assert 86340 <= ttl <= 86400
    finally:
        await store.aclose()


@pytest.mark.asyncio
async def test_conversation_expires_once_ttl_elapses(redis_url: str) -> None:
    """Expiry is wired: a sub-second TTL drops the conversation in Redis.

    Waiting a real 24h is infeasible, so this proves the *mechanism* —
    Redis-native expiry — by constructing the store with a tiny TTL and
    confirming `get` returns nothing once Redis has expired the key.
    """

    store = RedisConversationStore(
        redis_url=redis_url, ttl=timedelta(milliseconds=300)
    )
    conv = f"conv-{uuid.uuid4().hex}"
    try:
        await store.append(conv, _turn("user", "ephemeral", _T0))
        # Visible immediately, before expiry.
        assert await store.get(conv) == [_turn("user", "ephemeral", _T0)]

        # Poll until Redis has expired the key (well within a second).
        deadline = time.monotonic() + 5.0
        while time.monotonic() < deadline:
            if await store.get(conv) == []:
                break
            await asyncio.sleep(0.1)

        assert await store.get(conv) == []
    finally:
        await store.aclose()

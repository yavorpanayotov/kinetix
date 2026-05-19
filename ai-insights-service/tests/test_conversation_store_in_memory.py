"""Unit tests for the in-memory ConversationStore implementation.

These tests pin the protocol contract and the TTL/eviction semantics
that the chat endpoint depends on. They are deterministic — every
test injects a mutable clock so time can be advanced without sleeping
— and treat the store as a black box (no peeking at private state
except where the plan explicitly calls for it).

Coverage:

* Empty / round-trip / isolation / clear semantics.
* Defensive copy on `get` so callers cannot mutate internal state.
* TTL: writes refresh, reads do not, eviction is lazy.
* Eviction order: oldest-by-last-touch evicts first.
* Custom TTL and the 24h default.
* Runtime-checkable Protocol conformance.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from kinetix_insights.chat.conversation_store import (
    _DEFAULT_TTL,
    ConversationStore,
    ConversationTurn,
    InMemoryConversationStore,
)

pytestmark = pytest.mark.unit


def _clock(start: datetime) -> dict[str, datetime]:
    """Return a mutable holder so tests can advance time deterministically."""
    return {"now": start}


def _turn(role: str, content: str, at: datetime) -> ConversationTurn:
    return ConversationTurn(role=role, content=content, timestamp=at)


T0 = datetime(2026, 5, 19, 8, 0, 0, tzinfo=timezone.utc)


# ---------------------------------------------------------------------------
# Basic CRUD
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_empty_returns_empty_list() -> None:
    """`get` on an unknown id returns `[]` — empty history is a normal state."""
    store = InMemoryConversationStore(now=lambda: T0)
    assert await store.get("unknown") == []


@pytest.mark.asyncio
async def test_append_and_get_round_trip() -> None:
    """Two turns appended under one id come back in insertion order."""
    clock = _clock(T0)
    store = InMemoryConversationStore(now=lambda: clock["now"])
    first = _turn("user", "hi", T0)
    second = _turn("assistant", "hello", T0)
    await store.append("conv-1", first)
    await store.append("conv-1", second)
    assert await store.get("conv-1") == [first, second]


@pytest.mark.asyncio
async def test_append_two_conversations_isolated() -> None:
    """Turns under different ids do not bleed into each other."""
    store = InMemoryConversationStore(now=lambda: T0)
    a = _turn("user", "from A", T0)
    b = _turn("user", "from B", T0)
    await store.append("A", a)
    await store.append("B", b)
    assert await store.get("A") == [a]
    assert await store.get("B") == [b]


@pytest.mark.asyncio
async def test_get_returns_defensive_copy() -> None:
    """Mutating the returned list must not affect subsequent reads."""
    store = InMemoryConversationStore(now=lambda: T0)
    turn = _turn("user", "hi", T0)
    await store.append("conv", turn)
    snapshot = await store.get("conv")
    snapshot.append(_turn("assistant", "tampered", T0))
    snapshot.clear()
    assert await store.get("conv") == [turn]


@pytest.mark.asyncio
async def test_clear_removes_conversation() -> None:
    """After `clear`, the conversation reads back as empty."""
    store = InMemoryConversationStore(now=lambda: T0)
    await store.append("conv", _turn("user", "hi", T0))
    await store.clear("conv")
    assert await store.get("conv") == []


@pytest.mark.asyncio
async def test_clear_unknown_id_no_op() -> None:
    """`clear` on an id that has never been seen does not raise."""
    store = InMemoryConversationStore(now=lambda: T0)
    await store.clear("never-seen")
    assert await store.get("never-seen") == []


# ---------------------------------------------------------------------------
# TTL behaviour
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_ttl_expires_entry_after_24h_no_touch() -> None:
    """A conversation untouched for >24h is evicted on the next `get`."""
    clock = _clock(T0)
    store = InMemoryConversationStore(now=lambda: clock["now"])
    await store.append("conv", _turn("user", "hi", T0))
    clock["now"] = T0 + timedelta(hours=24, seconds=1)
    assert await store.get("conv") == []


@pytest.mark.asyncio
async def test_ttl_renewed_on_append() -> None:
    """`append` refreshes TTL — a write at t=23h keeps the entry alive until t=47h."""
    # Math: append at t=0 (TTL expires at t=24h). Second append at t=23h
    # renews → new TTL expires at t=23h+24h = 47h. At t=24h+1s = 86_401s
    # the conversation is still alive (47h = 169_200s). Both turns must
    # survive.
    clock = _clock(T0)
    store = InMemoryConversationStore(now=lambda: clock["now"])
    first = _turn("user", "first", T0)
    await store.append("conv", first)
    clock["now"] = T0 + timedelta(hours=23)
    second = _turn("assistant", "second", clock["now"])
    await store.append("conv", second)
    clock["now"] = T0 + timedelta(hours=24, seconds=1)
    assert await store.get("conv") == [first, second]


@pytest.mark.asyncio
async def test_ttl_does_not_renew_on_get() -> None:
    """Reading does NOT refresh the TTL — only writes do."""
    clock = _clock(T0)
    store = InMemoryConversationStore(now=lambda: clock["now"])
    await store.append("conv", _turn("user", "hi", T0))
    clock["now"] = T0 + timedelta(hours=23)
    # A read at t=23h must NOT extend the TTL beyond t=24h.
    assert len(await store.get("conv")) == 1
    clock["now"] = T0 + timedelta(hours=24, seconds=1)
    assert await store.get("conv") == []


@pytest.mark.asyncio
async def test_eviction_removes_oldest_conversation_first() -> None:
    """Lazy sweep evicts the oldest-by-last-touch entry, leaving newer alive."""
    clock = _clock(T0)
    store = InMemoryConversationStore(now=lambda: clock["now"])
    turn_a = _turn("user", "A", T0)
    await store.append("id_A", turn_a)
    clock["now"] = T0 + timedelta(hours=1)
    turn_b = _turn("user", "B", clock["now"])
    await store.append("id_B", turn_b)
    # id_A's TTL expires at t=24h; id_B's at t=25h. At t=24h+1s only A is expired.
    clock["now"] = T0 + timedelta(hours=24, seconds=1)
    # Trigger a lazy sweep with any operation.
    await store.get("any")
    assert await store.get("id_A") == []
    assert await store.get("id_B") == [turn_b]


@pytest.mark.asyncio
async def test_eviction_handles_multiple_expired_in_one_sweep() -> None:
    """One sweep can evict several adjacent expired entries from the head."""
    clock = _clock(T0)
    store = InMemoryConversationStore(now=lambda: clock["now"])
    turn_a = _turn("user", "A", T0)
    await store.append("id_A", turn_a)
    clock["now"] = T0 + timedelta(hours=1)
    turn_b = _turn("user", "B", clock["now"])
    await store.append("id_B", turn_b)
    clock["now"] = T0 + timedelta(hours=2)
    turn_c = _turn("user", "C", clock["now"])
    await store.append("id_C", turn_c)
    # id_A expires at 24h, id_B at 25h, id_C at 26h. At 25h+1s, A and B are
    # expired, C is still alive — a single sweep should evict both head
    # entries in one go.
    clock["now"] = T0 + timedelta(hours=25, seconds=1)
    assert await store.get("id_C") == [turn_c]
    assert await store.get("id_A") == []
    assert await store.get("id_B") == []


@pytest.mark.asyncio
async def test_ttl_default_is_24_hours() -> None:
    """The module-level default TTL constant is 24h and is what no-arg ctor uses."""
    assert _DEFAULT_TTL == timedelta(hours=24)
    clock = _clock(T0)
    store = InMemoryConversationStore(now=lambda: clock["now"])
    await store.append("conv", _turn("user", "hi", T0))
    # Implementation uses strict `>` against the TTL window: at exactly
    # t=24h the entry is still considered alive.
    clock["now"] = T0 + timedelta(hours=24)
    assert len(await store.get("conv")) == 1
    clock["now"] = T0 + timedelta(hours=24, seconds=1)
    assert await store.get("conv") == []


@pytest.mark.asyncio
async def test_custom_ttl_is_respected() -> None:
    """A non-default TTL controls the eviction window."""
    clock = _clock(T0)
    store = InMemoryConversationStore(
        ttl=timedelta(minutes=5), now=lambda: clock["now"]
    )
    await store.append("conv", _turn("user", "hi", T0))
    clock["now"] = T0 + timedelta(minutes=5)
    assert len(await store.get("conv")) == 1
    clock["now"] = T0 + timedelta(minutes=5, seconds=1)
    assert await store.get("conv") == []


def test_implements_conversation_store_protocol() -> None:
    """`InMemoryConversationStore` is structurally a `ConversationStore`."""
    store = InMemoryConversationStore()
    assert isinstance(store, ConversationStore)

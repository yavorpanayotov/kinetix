"""Unit tests for the shared :class:`_FakeStreamingSdk` test double.

The fake is consumed by chat, brief, and queries tests that need to drive
the Claude Agent SDK ``query`` surface deterministically. These tests pin
its public contract so downstream test modules can rely on it.
"""

from __future__ import annotations

import time

import pytest

from tests.fakes.streaming_sdk import (
    FakeMessage,
    FakeSdkError,
    _FakeStreamingSdk,
)

pytestmark = pytest.mark.unit


@pytest.mark.asyncio
async def test_query_yields_messages_in_order() -> None:
    fake = _FakeStreamingSdk(
        messages=[
            FakeMessage(content="alpha"),
            FakeMessage(content="beta"),
            FakeMessage(content="gamma"),
        ]
    )

    texts: list[str] = []
    async for message in fake.query(prompt="hi"):
        texts.append(message.text)

    assert texts == ["alpha", "beta", "gamma"]


@pytest.mark.asyncio
async def test_query_records_prompt() -> None:
    fake = _FakeStreamingSdk(messages=[FakeMessage(content="ok")])

    async for _ in fake.query(prompt="hello"):
        pass

    assert fake.recorded_prompts == ["hello"]


@pytest.mark.asyncio
async def test_query_records_kwargs() -> None:
    fake = _FakeStreamingSdk(messages=[FakeMessage(content="ok")])

    async for _ in fake.query(prompt="x", model="claude-opus-4-7"):
        pass

    assert fake.recorded_kwargs[0] == {"model": "claude-opus-4-7"}


@pytest.mark.asyncio
async def test_call_count_tracks_invocations() -> None:
    fake = _FakeStreamingSdk(
        messages=[FakeMessage(content="one"), FakeMessage(content="two")]
    )

    async for _ in fake.query(prompt="first"):
        pass
    async for _ in fake.query(prompt="second"):
        pass

    assert fake.call_count == 2


@pytest.mark.asyncio
async def test_query_respects_per_message_delays() -> None:
    fake = _FakeStreamingSdk(
        messages=[
            FakeMessage(content="a", delay_seconds=0.01),
            FakeMessage(content="b", delay_seconds=0.02),
        ]
    )

    start = time.perf_counter()
    async for _ in fake.query(prompt="x"):
        pass
    elapsed = time.perf_counter() - start

    assert elapsed >= 0.03
    assert elapsed < 0.5


@pytest.mark.asyncio
async def test_query_extracts_text_via_text_attribute() -> None:
    fake = _FakeStreamingSdk(
        messages=[FakeMessage(content="hello"), FakeMessage(content="world")]
    )

    collected: list[str] = []
    async for message in fake.query(prompt="x"):
        assert isinstance(message.text, str)
        collected.append(message.text)

    assert collected == ["hello", "world"]


@pytest.mark.asyncio
async def test_query_extracts_text_via_content_blocks() -> None:
    fake = _FakeStreamingSdk(messages=[FakeMessage(content="payload")])

    async for message in fake.query(prompt="x"):
        assert isinstance(message.content, list)
        assert len(message.content) >= 1
        assert message.content[0].text == "payload"


@pytest.mark.asyncio
async def test_query_yields_nothing_when_messages_exhausted() -> None:
    fake = _FakeStreamingSdk(messages=[])

    items: list[object] = []
    async for message in fake.query(prompt="x"):
        items.append(message)

    assert items == []


@pytest.mark.asyncio
async def test_extra_calls_after_exhaustion_yield_nothing() -> None:
    fake = _FakeStreamingSdk(messages=[FakeMessage(content="only")])

    first_batch: list[str] = []
    async for message in fake.query(prompt="first"):
        first_batch.append(message.text)

    second_batch: list[str] = []
    async for message in fake.query(prompt="second"):
        second_batch.append(message.text)

    assert first_batch == ["only"]
    assert second_batch == []
    assert fake.recorded_prompts == ["first", "second"]


@pytest.mark.asyncio
async def test_raise_on_next_call_raises_on_subsequent_query() -> None:
    fake = _FakeStreamingSdk(messages=[FakeMessage(content="resumed")])
    fake.raise_on_next_call(FakeSdkError("boom"))

    with pytest.raises(FakeSdkError):
        async for _ in fake.query(prompt="boom-call"):
            pass

    resumed: list[str] = []
    async for message in fake.query(prompt="next"):
        resumed.append(message.text)

    assert resumed == ["resumed"]


@pytest.mark.asyncio
async def test_raise_on_next_call_is_one_shot() -> None:
    fake = _FakeStreamingSdk(
        messages=[FakeMessage(content="a"), FakeMessage(content="b")]
    )
    fake.raise_on_next_call(FakeSdkError("once"))

    with pytest.raises(FakeSdkError):
        async for _ in fake.query(prompt="will-raise"):
            pass

    # After the armed one-shot error fires, the queue is intact and the
    # next call drains all scripted messages (shared-queue contract).
    first: list[str] = []
    async for message in fake.query(prompt="ok-1"):
        first.append(message.text)

    # The third call finds the queue empty and yields nothing — proving
    # the armed error did not re-fire on this invocation.
    second: list[str] = []
    async for message in fake.query(prompt="ok-2"):
        second.append(message.text)

    assert first == ["a", "b"]
    assert second == []


@pytest.mark.asyncio
async def test_add_messages_appends_to_queue() -> None:
    fake = _FakeStreamingSdk(messages=[])
    fake.add_messages(FakeMessage(content="a"), FakeMessage(content="b"))

    collected: list[str] = []
    async for message in fake.query(prompt="x"):
        collected.append(message.text)

    assert collected == ["a", "b"]

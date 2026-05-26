"""Shared test fake that mimics ``claude_agent_sdk.query``.

Chat, brief, and queries tests all drive the Claude Agent SDK through the
same ``query(prompt=..., **kwargs)`` callable that returns an async iterator
of message objects. Rather than each test module hand-rolling its own
fake, this module provides one consistent implementation.

Behavioural model — shared queue
--------------------------------

The fake holds a single ordered queue of :class:`FakeMessage` instances
shared across all ``query`` invocations. Each call drains messages from
the front of the queue until it is empty, then subsequent calls yield
nothing. That matches the assertion in
``test_extra_calls_after_exhaustion_yield_nothing`` and keeps the contract
simple — tests script the *total* sequence of messages they expect rather
than per-call batches.

This diverges from the real SDK (where each ``query`` is independent), but
it is the right shape for our use cases: chat tests stream a single
response, brief tests stream a single response, and the queries tests
need deterministic ordering across the whole interaction.

Yielded objects
---------------

Each yielded message satisfies both extraction paths in
``claude_agent_client._extract_text``:

* a top-level ``.text: str`` attribute, and
* a ``.content: list[FakeTextBlock]`` attribute where each block exposes
  ``.text: str``.

This lets the fake plug into any client regardless of which extraction
path it prefers.

Tool-use and tool-result events
-------------------------------

Tests that exercise the tool-call accumulation path script events using
:class:`FakeToolUseEvent` and :class:`FakeToolResultEvent`. These produce
yielded messages whose ``.content`` contains a single block with
``.type == "tool_use"`` or ``.type == "tool_result"`` respectively,
mirroring the real SDK's content-block shapes. The yielded message's
``.text`` attribute is the empty string for both event types so the
existing text-extraction path is unaffected.
"""

from __future__ import annotations

import asyncio
from collections import deque
from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from typing import Any, Union

from kinetix_insights.citations.models import Citation


class FakeSdkError(Exception):
    """Exception type for tests that want to simulate SDK transport failures."""


@dataclass(frozen=True)
class FakeMessage:
    """One scripted text message in the fake stream.

    ``content`` is the text the message should yield. ``delay_seconds`` is
    the wall-clock delay applied *before* the message is yielded — set it
    to model first-token latency or per-token streaming pacing.
    ``citations`` carries provenance the chat client's extraction helper
    reads off the yielded message's ``.citations`` attribute; the default
    empty tuple keeps the contract backwards-compatible for tests that do
    not care about citations.
    """

    content: str
    delay_seconds: float = 0.0
    citations: tuple[Citation, ...] = ()


@dataclass(frozen=True)
class FakeToolUseEvent:
    """Scripts a tool-use event in the fake stream.

    Produces a yielded message with a single ``ToolUseBlock``-shaped
    content block (``type="tool_use"``, ``id``, ``name``, ``input``).
    """

    tool_use_id: str
    name: str
    input: dict[str, Any] = field(default_factory=dict)
    delay_seconds: float = 0.0


@dataclass(frozen=True)
class FakeToolResultEvent:
    """Scripts a tool-result event in the fake stream.

    Produces a yielded message with a single ``ToolResultBlock``-shaped
    content block (``type="tool_result"``, ``tool_use_id``, ``is_error``).
    """

    tool_use_id: str
    is_error: bool = False
    delay_seconds: float = 0.0


# Union of all scriptable event types. The list union keeps the type
# explicit while allowing _FakeStreamingSdk.messages to hold any mix.
_ScriptedEvent = Union[FakeMessage, FakeToolUseEvent, FakeToolResultEvent]


@dataclass(frozen=True)
class FakeTextBlock:
    """Mimics the SDK's ``TextBlock`` — a single ``text`` field."""

    text: str


@dataclass(frozen=True)
class FakeToolUseBlock:
    """Mimics the SDK's ``ToolUseBlock`` content block."""

    type: str = "tool_use"
    id: str = ""
    name: str = ""
    input: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class FakeToolResultBlock:
    """Mimics the SDK's ``ToolResultBlock`` content block."""

    type: str = "tool_result"
    tool_use_id: str = ""
    is_error: bool = False


@dataclass(frozen=True)
class _FakeMessage:
    """Concrete yielded object exposing both extraction paths.

    Has ``text`` (for direct extraction) and ``content`` (for block-list
    extraction). ``citations`` mirrors the scripted ``FakeMessage`` so
    citation-aware clients can pull provenance straight off the yielded
    message — defaults to an empty list so existing tests are unaffected.
    Frozen so tests can compare instances safely.
    """

    text: str
    content: list[Any]
    citations: list[Citation] = field(default_factory=list)


class FakeToolUseMessage:
    """Convenience constructor that builds a message containing one ToolUseBlock.

    Used by tests to assert on the yielded-message shape that the fake
    produces for :class:`FakeToolUseEvent` entries without going through
    the queue.
    """

    def __init__(
        self,
        *,
        tool_use_id: str,
        name: str,
        input: dict[str, Any] | None = None,
    ) -> None:
        self.text = ""
        self.citations: list[Citation] = []
        self.content = [
            FakeToolUseBlock(
                type="tool_use",
                id=tool_use_id,
                name=name,
                input=input if input is not None else {},
            )
        ]


class FakeToolResultMessage:
    """Convenience constructor that builds a message containing one ToolResultBlock.

    Used by tests to assert on the yielded-message shape that the fake
    produces for :class:`FakeToolResultEvent` entries.
    """

    def __init__(
        self,
        *,
        tool_use_id: str,
        is_error: bool = False,
    ) -> None:
        self.text = ""
        self.citations: list[Citation] = []
        self.content = [
            FakeToolResultBlock(
                type="tool_result",
                tool_use_id=tool_use_id,
                is_error=is_error,
            )
        ]


@dataclass
class _FakeStreamingSdk:
    """Shared async-iterator fake for ``claude_agent_sdk.query``.

    Construct with a list of scripted event instances (:class:`FakeMessage`,
    :class:`FakeToolUseEvent`, or :class:`FakeToolResultEvent`). Iteration
    of ``query(...)`` drains the shared queue, sleeping ``delay_seconds``
    before each yield. ``recorded_prompts`` and ``recorded_kwargs`` capture
    every invocation so tests can assert on what the client passed in.
    """

    messages: list[_ScriptedEvent] = field(default_factory=list)
    recorded_prompts: list[str] = field(default_factory=list)
    recorded_kwargs: list[dict[str, Any]] = field(default_factory=list)
    _queue: deque[_ScriptedEvent] = field(init=False)
    _pending_error: Exception | None = field(default=None, init=False)

    def __post_init__(self) -> None:
        self._queue = deque(self.messages)

    @property
    def call_count(self) -> int:
        """Number of times ``query`` has been invoked."""

        return len(self.recorded_prompts)

    def add_messages(self, *messages: _ScriptedEvent) -> None:
        """Append scripted messages to the shared queue.

        Tests that want to mutate the script mid-test (e.g. after asserting
        on an earlier interaction) can use this to extend the stream
        without rebuilding the fake.
        """

        self._queue.extend(messages)

    def raise_on_next_call(self, error: Exception) -> None:
        """Arm a one-shot exception for the next ``query`` invocation.

        The exception is raised *before* any iteration begins, mirroring
        an SDK transport failure that prevents the stream from starting.
        It is cleared after firing once — subsequent calls return to
        normal queue-drain behaviour.
        """

        self._pending_error = error

    def query(self, *, prompt: str, **kwargs: Any) -> AsyncIterator[Any]:
        """Record the call and return an async iterator over the queue.

        The async generator below sleeps ``delay_seconds`` before each
        yield. When the shared queue is empty the generator simply
        returns, which is what the real SDK does for an empty stream.
        """

        self.recorded_prompts.append(prompt)
        self.recorded_kwargs.append(dict(kwargs))
        pending_error = self._pending_error
        self._pending_error = None
        return self._stream(pending_error)

    async def _stream(self, pending_error: Exception | None) -> AsyncIterator[Any]:
        if pending_error is not None:
            raise pending_error
        while self._queue:
            scripted = self._queue.popleft()
            delay = getattr(scripted, "delay_seconds", 0.0)
            if delay > 0:
                await asyncio.sleep(delay)
            if isinstance(scripted, FakeMessage):
                yield _FakeMessage(
                    text=scripted.content,
                    content=[FakeTextBlock(text=scripted.content)],
                    citations=list(scripted.citations),
                )
            elif isinstance(scripted, FakeToolUseEvent):
                yield _FakeMessage(
                    text="",
                    content=[
                        FakeToolUseBlock(
                            type="tool_use",
                            id=scripted.tool_use_id,
                            name=scripted.name,
                            input=scripted.input,
                        )
                    ],
                )
            elif isinstance(scripted, FakeToolResultEvent):
                yield _FakeMessage(
                    text="",
                    content=[
                        FakeToolResultBlock(
                            type="tool_result",
                            tool_use_id=scripted.tool_use_id,
                            is_error=scripted.is_error,
                        )
                    ],
                )

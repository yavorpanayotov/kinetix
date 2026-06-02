"""Unit tests for ``build_chat_client`` demo-mode client selection.

These pin the rung-2 wiring (kx-fant): within demo mode,
``COPILOT_GROUNDED_DEMO`` chooses between the plain fixture client and
the data-grounded client, and neither path constructs the live SDK
client.

Metric-split tests (kx-ikk9): the intentional ``DEMO_MODE=true`` path
increments ``COPILOT_DEMO_MODE_TOTAL`` only; the exception-fallback
path increments ``COPILOT_DEMO_MODE_FALLBACK_TOTAL`` only.
"""

from __future__ import annotations

import pytest

from kinetix_insights.chat import factory as chat_factory
from kinetix_insights.chat.canned import CannedCopilotChatClient
from kinetix_insights.chat.conversation_store import InMemoryConversationStore
from kinetix_insights.chat.data_grounded_canned import DataGroundedCannedChatClient
from kinetix_insights.chat.factory import build_chat_client
from kinetix_insights.metrics import copilot_metrics

pytestmark = pytest.mark.unit


def _store() -> InMemoryConversationStore:
    return InMemoryConversationStore()


def test_demo_mode_defaults_to_plain_canned(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("DEMO_MODE", "true")
    monkeypatch.delenv("COPILOT_GROUNDED_DEMO", raising=False)

    client = build_chat_client(conversation_store=_store())

    assert isinstance(client, CannedCopilotChatClient)


def test_demo_mode_with_grounded_flag_uses_data_grounded_client(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("DEMO_MODE", "true")
    monkeypatch.setenv("COPILOT_GROUNDED_DEMO", "true")

    client = build_chat_client(conversation_store=_store())

    assert isinstance(client, DataGroundedCannedChatClient)


def test_grounded_flag_is_case_insensitive(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("DEMO_MODE", "true")
    monkeypatch.setenv("COPILOT_GROUNDED_DEMO", "TRUE")

    client = build_chat_client(conversation_store=_store())

    assert isinstance(client, DataGroundedCannedChatClient)


def test_grounded_flag_ignored_outside_demo_mode_does_not_crash(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Outside demo mode the grounded flag is irrelevant; selection still
    resolves to a usable client without raising."""

    monkeypatch.setenv("DEMO_MODE", "false")
    monkeypatch.setenv("COPILOT_GROUNDED_DEMO", "true")

    client = build_chat_client(conversation_store=_store())

    # Either the live client (if it constructs) or the canned fallback —
    # never the grounded demo client, which is demo-mode only.
    assert not isinstance(client, DataGroundedCannedChatClient)


# ---------------------------------------------------------------------------
# Metric-split tests (kx-ikk9)
# ---------------------------------------------------------------------------


def test_demo_mode_true_increments_demo_mode_total_not_fallback(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Intentional DEMO_MODE=true increments COPILOT_DEMO_MODE_TOTAL only.

    COPILOT_DEMO_MODE_FALLBACK_TOTAL must not be touched — it is reserved
    for the Claude-unavailable error path so alerts on it signal real
    degradation, not operator intent.
    """
    monkeypatch.setenv("DEMO_MODE", "true")
    monkeypatch.delenv("COPILOT_GROUNDED_DEMO", raising=False)

    before_demo = copilot_metrics.COPILOT_DEMO_MODE_TOTAL._value.get()
    before_fallback = copilot_metrics.COPILOT_DEMO_MODE_FALLBACK_TOTAL._value.get()

    build_chat_client(conversation_store=_store())

    assert copilot_metrics.COPILOT_DEMO_MODE_TOTAL._value.get() == before_demo + 1
    assert (
        copilot_metrics.COPILOT_DEMO_MODE_FALLBACK_TOTAL._value.get()
        == before_fallback
    ), "COPILOT_DEMO_MODE_FALLBACK_TOTAL must not fire on intentional demo mode"


def test_exception_fallback_increments_fallback_total_not_demo_mode(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Claude-unavailable exception path increments COPILOT_DEMO_MODE_FALLBACK_TOTAL only.

    COPILOT_DEMO_MODE_TOTAL must not be touched — it tracks deliberate
    operator intent (DEMO_MODE env var), not error recovery.
    """
    monkeypatch.delenv("DEMO_MODE", raising=False)

    def _raise(*args: object, **kwargs: object) -> None:
        raise RuntimeError("claude unavailable")

    monkeypatch.setattr(chat_factory, "ClaudeAgentCopilotChatClient", _raise)

    before_demo = copilot_metrics.COPILOT_DEMO_MODE_TOTAL._value.get()
    before_fallback = copilot_metrics.COPILOT_DEMO_MODE_FALLBACK_TOTAL._value.get()

    build_chat_client(conversation_store=_store())

    assert (
        copilot_metrics.COPILOT_DEMO_MODE_FALLBACK_TOTAL._value.get()
        == before_fallback + 1
    )
    assert (
        copilot_metrics.COPILOT_DEMO_MODE_TOTAL._value.get() == before_demo
    ), "COPILOT_DEMO_MODE_TOTAL must not fire on exception fallback"

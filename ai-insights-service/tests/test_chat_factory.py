"""Unit tests for ``build_chat_client`` demo-mode client selection.

These pin the rung-2 wiring (kx-fant): within demo mode,
``COPILOT_GROUNDED_DEMO`` chooses between the plain fixture client and
the data-grounded client, and neither path constructs the live SDK
client.
"""

from __future__ import annotations

import pytest

from kinetix_insights.chat.canned import CannedCopilotChatClient
from kinetix_insights.chat.conversation_store import InMemoryConversationStore
from kinetix_insights.chat.data_grounded_canned import DataGroundedCannedChatClient
from kinetix_insights.chat.factory import build_chat_client

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

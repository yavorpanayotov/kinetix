"""Unit tests for :func:`kinetix_insights.factory.build_client`.

The factory chooses between the live :class:`ClaudeAgentInsightClient`
and the offline :class:`CannedInsightClient` based on the ``DEMO_MODE``
environment variable and whether the live client can be constructed.
These tests cover all three branches without touching the real
``claude-agent-sdk`` package.
"""

from __future__ import annotations

import pytest

from kinetix_insights import factory
from kinetix_insights.canned import CannedInsightClient
from kinetix_insights.claude_agent_client import ClaudeAgentInsightClient
from kinetix_insights.factory import build_client

pytestmark = pytest.mark.unit


def test_demo_mode_true_returns_canned(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("DEMO_MODE", "true")

    client = build_client()

    assert isinstance(client, CannedInsightClient)


def test_demo_mode_uppercase_returns_canned(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("DEMO_MODE", "TRUE")

    client = build_client()

    assert isinstance(client, CannedInsightClient)


def test_demo_mode_false_returns_claude_agent(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("DEMO_MODE", "false")

    client = build_client()

    assert isinstance(client, ClaudeAgentInsightClient)


def test_no_demo_mode_returns_claude_agent(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("DEMO_MODE", raising=False)

    client = build_client()

    assert isinstance(client, ClaudeAgentInsightClient)


def test_claude_agent_instantiation_raises_falls_back_to_canned(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("DEMO_MODE", raising=False)

    def _raise(*args: object, **kwargs: object) -> None:
        raise RuntimeError("boom")

    monkeypatch.setattr(factory, "ClaudeAgentInsightClient", _raise)

    client = build_client()

    assert isinstance(client, CannedInsightClient)

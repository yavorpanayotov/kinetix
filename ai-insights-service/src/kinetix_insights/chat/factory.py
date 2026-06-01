"""Selects the appropriate ``CopilotChatClient`` for the current env.

Mirrors the resolution order of :func:`kinetix_insights.factory.build_client`:
``DEMO_MODE=true`` (case-insensitive) → a canned client; otherwise try to
construct :class:`ClaudeAgentCopilotChatClient`, falling back to the canned
client on any error so demo / CI environments stay green.

Within demo mode there are two canned variants:

* :class:`CannedCopilotChatClient` — fixture replay (the default). Its
  numbers are fixed constants and may not match the seeded backend.
* :class:`DataGroundedCannedChatClient` — selected when
  ``COPILOT_GROUNDED_DEMO`` is ``"true"``. It still spawns no ``claude``
  subprocess and reads no ``~/.claude`` credential, but for the topics it
  templates it pulls **live** numbers + citations from the seeded backend
  so the Copilot stops contradicting the dashboard. It degrades to plain
  fixture replay for ungrounded topics and on any tool failure, so
  enabling it is safe even when the backend is unreachable.

The factory is intentionally minimal: it has no opinion on caching,
process-wide singletons, or lifecycle. The caller (the FastAPI lifespan)
owns the chat-client instance and decides when to construct or drop it.
"""

from __future__ import annotations

import logging
import os

from kinetix_insights.chat.canned import CannedCopilotChatClient, CopilotChatClient
from kinetix_insights.chat.claude_agent_chat_client import ClaudeAgentCopilotChatClient
from kinetix_insights.chat.conversation_store import ConversationStore
from kinetix_insights.chat.data_grounded_canned import DataGroundedCannedChatClient
from kinetix_insights.clients.kinetix_http_client import HttpxKinetixHttpClient
from kinetix_insights.metrics.copilot_metrics import (
    COPILOT_DEMO_MODE_FALLBACK_TOTAL,
)

logger = logging.getLogger(__name__)


def _build_canned_chat_client() -> CopilotChatClient:
    """Return the canned demo client, grounded when opted in.

    ``COPILOT_GROUNDED_DEMO=true`` selects the data-grounded variant
    (live numbers, no Claude) with a plain fixture client as its
    fallback; otherwise the plain fixture client is returned.
    """

    if os.environ.get("COPILOT_GROUNDED_DEMO", "").strip().lower() == "true":
        logger.info(
            "COPILOT_GROUNDED_DEMO=true — using DataGroundedCannedChatClient"
        )
        return DataGroundedCannedChatClient(
            http=HttpxKinetixHttpClient(),
            fallback=CannedCopilotChatClient(),
        )
    return CannedCopilotChatClient()


def build_chat_client(
    *,
    conversation_store: ConversationStore,
) -> CopilotChatClient:
    """Return the chat client appropriate for the current environment.

    Resolution order:

    1. If ``DEMO_MODE`` is ``"true"`` (case-insensitive) → a canned
       client (grounded when ``COPILOT_GROUNDED_DEMO=true``, else plain
       fixtures).
    2. Otherwise attempt to construct
       :class:`ClaudeAgentCopilotChatClient`; on any exception fall back
       to :class:`CannedCopilotChatClient` with a warning.
    """

    if os.environ.get("DEMO_MODE", "").strip().lower() == "true":
        logger.info("DEMO_MODE=true — using a canned chat client")
        COPILOT_DEMO_MODE_FALLBACK_TOTAL.inc()
        return _build_canned_chat_client()
    try:
        return ClaudeAgentCopilotChatClient(conversation_store=conversation_store)
    except Exception as exc:  # noqa: BLE001 — any failure falls back to canned
        logger.warning(
            "ClaudeAgentCopilotChatClient unavailable (%s); "
            "falling back to CannedCopilotChatClient",
            exc,
        )
        COPILOT_DEMO_MODE_FALLBACK_TOTAL.inc()
        return CannedCopilotChatClient()

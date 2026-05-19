"""Selects the appropriate ``CopilotChatClient`` for the current env.

Mirrors the resolution order of :func:`kinetix_insights.factory.build_client`:
``DEMO_MODE=true`` (case-insensitive) → :class:`CannedCopilotChatClient`;
otherwise try to construct :class:`ClaudeAgentCopilotChatClient`, falling
back to the canned client on any error so demo / CI environments stay green.

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

logger = logging.getLogger(__name__)


def build_chat_client(
    *,
    conversation_store: ConversationStore,
) -> CopilotChatClient:
    """Return the chat client appropriate for the current environment.

    Resolution order:

    1. If ``DEMO_MODE`` is ``"true"`` (case-insensitive) →
       :class:`CannedCopilotChatClient` with the default fixtures dir.
    2. Otherwise attempt to construct
       :class:`ClaudeAgentCopilotChatClient`; on any exception fall back
       to :class:`CannedCopilotChatClient` with a warning.
    """

    if os.environ.get("DEMO_MODE", "").strip().lower() == "true":
        logger.info("DEMO_MODE=true — using CannedCopilotChatClient")
        return CannedCopilotChatClient()
    try:
        return ClaudeAgentCopilotChatClient(conversation_store=conversation_store)
    except Exception as exc:  # noqa: BLE001 — any failure falls back to canned
        logger.warning(
            "ClaudeAgentCopilotChatClient unavailable (%s); "
            "falling back to CannedCopilotChatClient",
            exc,
        )
        return CannedCopilotChatClient()

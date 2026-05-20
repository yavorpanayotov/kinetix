"""Selects the appropriate :class:`ConversationStore` for the current env.

Mirrors the resolution order of the other env-driven factories
(:func:`kinetix_insights.chat.factory.build_chat_client`,
:func:`kinetix_insights.brief.factory.build_brief_client`): one
environment variable picks the implementation, with a safe fallback so
demo / CI environments stay green.

Resolution:

1. If ``REDIS_URL`` is set (non-blank) → :class:`RedisConversationStore`
   bound to that URL, so conversation history is shared across replicas
   and survives a restart (plan ai-v2.md §10.1).
2. Otherwise → :class:`InMemoryConversationStore`, the process-local
   v2 default used in DEMO_MODE, CI, and single-replica local dev.

The factory is intentionally minimal — it has no opinion on caching,
singletons, or lifecycle. The caller (the FastAPI lifespan) owns the
store instance and decides when to construct or drop it.
"""

from __future__ import annotations

import logging
import os

from kinetix_insights.chat.conversation_store import (
    ConversationStore,
    InMemoryConversationStore,
)
from kinetix_insights.chat.redis_conversation_store import RedisConversationStore

logger = logging.getLogger(__name__)


def build_conversation_store() -> ConversationStore:
    """Return the conversation store appropriate for the current env.

    ``REDIS_URL`` set → :class:`RedisConversationStore`; otherwise the
    process-local :class:`InMemoryConversationStore`.
    """

    redis_url = os.environ.get("REDIS_URL", "").strip()
    if redis_url:
        logger.info("REDIS_URL set — using RedisConversationStore")
        return RedisConversationStore(redis_url=redis_url)
    logger.info("REDIS_URL unset — using InMemoryConversationStore")
    return InMemoryConversationStore()

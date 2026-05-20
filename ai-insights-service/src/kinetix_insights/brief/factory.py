"""Selects the appropriate :class:`BriefClient` for the current env.

Mirrors the resolution order of :func:`kinetix_insights.factory.build_client`
and :func:`kinetix_insights.chat.factory.build_chat_client`:
``DEMO_MODE=true`` (case-insensitive) → :class:`CannedBriefClient`;
otherwise construct :class:`ClaudeAgentBriefClient`, falling back to the
canned client on any construction error so demo / CI environments stay
green even without an authenticated Claude CLI session.

The factory is intentionally minimal — it has no opinion on caching,
singletons, or lifecycle. The caller (the FastAPI lifespan) owns the
brief-client instance and decides when to construct or drop it.
"""

from __future__ import annotations

import logging
import os

from kinetix_insights.brief.canned import BriefClient, CannedBriefClient
from kinetix_insights.brief.claude_agent_brief_client import ClaudeAgentBriefClient
from kinetix_insights.clients.kinetix_http_client import KinetixHttpClient
from kinetix_insights.metrics.copilot_metrics import (
    COPILOT_DEMO_MODE_FALLBACK_TOTAL,
)

logger = logging.getLogger(__name__)


def build_brief_client(*, http: KinetixHttpClient) -> BriefClient:
    """Return the brief client appropriate for the current environment.

    Resolution order:

    1. If ``DEMO_MODE`` is ``"true"`` (case-insensitive) →
       :class:`CannedBriefClient` with the default fixture.
    2. Otherwise attempt to construct :class:`ClaudeAgentBriefClient`;
       on any exception fall back to :class:`CannedBriefClient` with a
       warning so demo / CI runs stay green.
    """

    if os.environ.get("DEMO_MODE", "").strip().lower() == "true":
        logger.info("DEMO_MODE=true — using CannedBriefClient")
        COPILOT_DEMO_MODE_FALLBACK_TOTAL.inc()
        return CannedBriefClient()
    try:
        return ClaudeAgentBriefClient(http=http)
    except Exception as exc:  # noqa: BLE001 — any failure falls back to canned
        logger.warning(
            "ClaudeAgentBriefClient unavailable (%s); "
            "falling back to CannedBriefClient",
            exc,
        )
        COPILOT_DEMO_MODE_FALLBACK_TOTAL.inc()
        return CannedBriefClient()

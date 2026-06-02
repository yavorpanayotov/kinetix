"""Factory for selecting the appropriate :class:`InsightClient` at startup.

The factory returns :class:`CannedInsightClient` when ``DEMO_MODE`` is set
to ``"true"`` (case-insensitive), or when constructing the live
:class:`ClaudeAgentInsightClient` raises any exception. Otherwise it
returns the live client.

This keeps demo and CI environments green even without an authenticated
Claude CLI session, while letting production opt into the live SDK path
simply by leaving ``DEMO_MODE`` unset.
"""

from __future__ import annotations

import logging
import os

from .canned import CannedInsightClient
from .claude_agent_client import ClaudeAgentInsightClient
from .insights_client import InsightClient
from .metrics.copilot_metrics import (
    COPILOT_DEMO_MODE_FALLBACK_TOTAL,
    COPILOT_DEMO_MODE_TOTAL,
)

logger = logging.getLogger(__name__)


def build_client() -> InsightClient:
    """Return the insight client appropriate for the current environment.

    Resolution order:

    1. If ``DEMO_MODE`` is ``"true"`` (case-insensitive) → ``CannedInsightClient``.
    2. Otherwise attempt to construct :class:`ClaudeAgentInsightClient`;
       on any exception, fall back to ``CannedInsightClient``.
    3. On success, return the live :class:`ClaudeAgentInsightClient`.
    """

    if os.environ.get("DEMO_MODE", "").strip().lower() == "true":
        logger.info("DEMO_MODE=true — using CannedInsightClient")
        COPILOT_DEMO_MODE_TOTAL.inc()
        return CannedInsightClient()
    try:
        return ClaudeAgentInsightClient()
    except Exception as exc:  # noqa: BLE001 — any failure falls back to canned
        logger.warning(
            "ClaudeAgentInsightClient unavailable (%s); falling back to CannedInsightClient",
            exc,
        )
        COPILOT_DEMO_MODE_FALLBACK_TOTAL.inc()
        return CannedInsightClient()

"""Selects the appropriate intraday-push generator for the current env.

Mirrors the resolution order of :func:`kinetix_insights.brief.factory.
build_brief_client` and :func:`kinetix_insights.chat.factory.
build_chat_client`: ``DEMO_MODE=true`` (case-insensitive) selects the
fixture-driven :class:`~kinetix_insights.push.canned.
CannedIntradayPushGenerator`; otherwise the live
:class:`~kinetix_insights.push.push_generator.IntradayPushGenerator` is
returned.

Unlike the brief/chat factories there is no live-construction fallback:
the live :class:`IntradayPushGenerator` is cheap, side-effect free, and
needs no SDK or network at construction time, so it cannot fail to
build. Both generators satisfy the
:class:`~kinetix_insights.push.kafka_consumer.PushGenerator` protocol.

The factory is intentionally minimal — it has no opinion on caching,
singletons, or lifecycle. The caller (the FastAPI lifespan) owns the
generator instance and decides when to construct or drop it.
"""

from __future__ import annotations

import logging
import os

from kinetix_insights.push.canned import CannedIntradayPushGenerator
from kinetix_insights.push.kafka_consumer import PushGenerator
from kinetix_insights.push.push_generator import IntradayPushGenerator, PushSink

logger = logging.getLogger(__name__)


def build_intraday_push_generator(*, sink: PushSink | None = None) -> PushGenerator:
    """Return the intraday-push generator appropriate for the current env.

    Resolution order:

    1. If ``DEMO_MODE`` is ``"true"`` (case-insensitive) →
       :class:`~kinetix_insights.push.canned.CannedIntradayPushGenerator`
       with the default fixture. The ``sink`` is ignored in demo mode —
       canned pushes are not dispatched to the gateway.
    2. Otherwise → :class:`~kinetix_insights.push.push_generator.
       IntradayPushGenerator`, with ``sink`` threaded through so
       checkbox 7.7's ``GatewayPushClient`` can plug in.

    Args:
        sink: Optional async push sink forwarded to the live generator.
            Honoured only on the live path.
    """

    if os.environ.get("DEMO_MODE", "").strip().lower() == "true":
        logger.info("DEMO_MODE=true — using CannedIntradayPushGenerator")
        return CannedIntradayPushGenerator()
    return IntradayPushGenerator(sink=sink)

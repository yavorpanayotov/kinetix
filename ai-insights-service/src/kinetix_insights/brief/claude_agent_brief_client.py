"""The live :class:`BriefClient` — :class:`ClaudeAgentBriefClient`.

This client backs the morning-brief endpoint in live mode. It wraps
:class:`kinetix_insights.brief.generator.MorningBriefGenerator`, which
already composes the five v2 read tools into per-book
:class:`MorningBrief` objects with deterministic prose narratives.

What v2 ships
-------------
v2 ships the generator's deterministic narratives unchanged. The
generator is already resilient by construction — a failure on any one
tool or book produces an ``error``/``timeout`` section rather than
aborting the batch — so :meth:`generate_brief` simply delegates to
:meth:`MorningBriefGenerator.generate_all` and never raises for
upstream or tool failures.

SDK narrative polish is a follow-up
-----------------------------------
The constructor accepts an optional ``sdk`` handle and a ``model``
name so a future revision can pass each section's raw figures through
the Claude SDK for a more fluent narrative. That polish is a
deliberate v2 follow-up: it must stay OPTIONAL (only attempted when an
SDK is available) and RESILIENT (any SDK failure falls back to the
generator's own narratives, and the brief is always returned). Until
that lands, ``sdk`` and ``model`` are stored but unused — see the
``# v2: SDK narrative polish is a follow-up`` seam in
:meth:`generate_brief`. No elaborate SDK plumbing is built here.
"""

from __future__ import annotations

from datetime import datetime
from typing import Any, Callable

from kinetix_insights.brief.generator import MorningBriefGenerator
from kinetix_insights.brief.models import MorningBrief
from kinetix_insights.clients.kinetix_http_client import KinetixHttpClient
from kinetix_insights.clients.user_context import UserContext

DEFAULT_MODEL = "claude-opus-4-7"


class ClaudeAgentBriefClient:
    """Live :class:`BriefClient` that wraps :class:`MorningBriefGenerator`.

    Construction is cheap and side-effect free — no SDK call, no
    network. Each :meth:`generate_brief` call builds a fresh
    :class:`MorningBriefGenerator` in ``live`` mode and runs it over
    every book in the user's scope.
    """

    def __init__(
        self,
        *,
        http: KinetixHttpClient,
        sdk: Any | None = None,
        now: Callable[[], datetime] | None = None,
        model: str = DEFAULT_MODEL,
    ) -> None:
        self._http = http
        self._sdk = sdk
        self._now = now
        self._model = model

    async def generate_brief(self, *, user: UserContext) -> list[MorningBrief]:
        """Generate one :class:`MorningBrief` per book in the user's scope.

        Delegates to :meth:`MorningBriefGenerator.generate_all`, which
        is resilient by construction: per-book and per-tool failures
        become ``error``/``timeout`` sections rather than raising. This
        method therefore never raises for upstream/tool failures — it
        propagates whatever ``generate_all`` returns.
        """

        generator = MorningBriefGenerator(
            http=self._http, now=self._now, mode="live"
        )
        briefs = await generator.generate_all(user=user)
        # v2: SDK narrative polish is a follow-up. When self._sdk is
        # available, a future revision will rewrite each section's
        # narrative more fluently here — optionally and resiliently,
        # falling back to the generator's deterministic narratives on
        # any SDK failure. v2 ships the generator's narratives as-is.
        return briefs

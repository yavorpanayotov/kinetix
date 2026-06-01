"""Data-grounded canned ``CopilotChatClient``.

The plain :class:`~kinetix_insights.chat.canned.CannedCopilotChatClient`
replays fixtures whose numbers are hard-coded constants — they do not
reflect the book the user is looking at, so the Copilot can quote a VaR
that contradicts the dashboard. This client closes that gap **without
calling Claude**: for the topics it has templates for, it invokes the
same MCP read tools the live client would (``get_book_var``,
``get_pnl_attribution``) against the seeded backend, then templates the
**real** tool-result numbers and their :class:`Citation` provenance into
a scripted narrative. The prose is deterministic; the figures are live.

This is "rung 2" of making the demo Copilot more real (see issue
kx-fant). It is the canned path — no ``claude`` subprocess is ever
spawned and ``~/.claude`` is never read — so it carries no subscription
cost and is safe to run on a public showcase.

Scope and graceful degradation:

* Grounded topics: :data:`~kinetix_insights.chat.intent_router.VAR`,
  :data:`~kinetix_insights.chat.intent_router.VAR_DRIVERS`, and
  :data:`~kinetix_insights.chat.intent_router.PNL`.
* For every other routed topic, for a request that carries no
  ``book_id`` in ``page_context``, and for ANY failure while calling a
  tool (ACL fail-closed, upstream error, payload drift), the client
  delegates to an injected fallback :class:`CopilotChatClient` (the
  plain canned client by default). The stream therefore always closes
  cleanly and never raises — a missing backend degrades to fixtures,
  it does not break the demo.

Identity: outbound tool calls need a :class:`UserContext` so downstream
services authorise and audit per-user, and so ``get_book_var`` /
``get_pnl_attribution`` (which fail closed when the book is outside the
caller's scope) accept the call. The chat protocol does not thread the
caller's books to ``chat()``, so on the canned/demo path the client
scopes a context to the page's own ``book_id`` under a configurable
demo user id. The book is whatever the page is already showing the
user, so this grants no access the dashboard did not already.
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import AsyncIterator
from dataclasses import dataclass
from datetime import datetime
from typing import Any, Awaitable, Callable

from kinetix_insights.chat.canned import CannedCopilotChatClient, CopilotChatClient
from kinetix_insights.chat.conversation_store import ConversationTurn
from kinetix_insights.chat.intent_router import (
    PNL,
    VAR,
    VAR_DRIVERS,
    route_intent,
)
from kinetix_insights.chat.models import ChatChunk, ChatRequest, ToolCall
from kinetix_insights.citations.models import Citation
from kinetix_insights.clients.kinetix_http_client import KinetixHttpClient
from kinetix_insights.clients.user_context import UserContext
from kinetix_insights.mcp.tools.get_book_var import get_book_var
from kinetix_insights.mcp.tools.get_pnl_attribution import get_pnl_attribution

_LOGGER = logging.getLogger("kinetix_insights.chat")

_GROUNDED_MODE = "canned-grounded"
_GROUNDED_MODEL = "canned-grounded"
_DEFAULT_DELAY_SECONDS = 0.020
_DEFAULT_DEMO_USER_ID = "copilot-demo"

# Human labels for P&L component keys, for the narrative.
_PNL_COMPONENT_LABELS: dict[str, str] = {
    "delta_pnl": "delta",
    "gamma_pnl": "gamma",
    "vega_pnl": "vega",
    "theta_pnl": "theta",
    "rho_pnl": "rates carry",
    "vanna_pnl": "vanna",
    "volga_pnl": "volga",
    "charm_pnl": "charm",
    "cross_gamma_pnl": "cross-gamma",
    "unexplained_pnl": "unexplained",
}


def _fmt_usd(value: float) -> str:
    """Format a USD amount as a signed, thousands-separated string.

    ``5234567.0 -> "$5,234,567"``; ``-1840000.0 -> "-$1,840,000"``. The
    same formatting is applied to the citation-backed value so the
    narrative token and the citation's ``result_value`` describe the
    identical number.
    """

    sign = "-" if value < 0 else ""
    return f"{sign}${abs(value):,.0f}"


@dataclass(frozen=True)
class _Grounded:
    """A fully-grounded response: prose plus its real provenance."""

    deltas: list[str]
    citations: list[Citation]
    tool_calls: list[ToolCall] | None


class DataGroundedCannedChatClient:
    """Canned chat client that grounds numbers in live tool results.

    Args:
        http: The :class:`KinetixHttpClient` MCP tools dispatch through.
        fallback: Client used for ungrounded topics, book-less requests,
            and tool failures. Defaults to a plain
            :class:`CannedCopilotChatClient`.
        delay_seconds: Per-delta artificial latency for the streamed
            feel; set to ``0.0`` in tests.
        demo_user_id: User id stamped on outbound tool calls.
        now: Injectable clock forwarded to the tools so citation
            freshness is deterministic in tests.
    """

    def __init__(
        self,
        *,
        http: KinetixHttpClient,
        fallback: CopilotChatClient | None = None,
        delay_seconds: float = _DEFAULT_DELAY_SECONDS,
        demo_user_id: str = _DEFAULT_DEMO_USER_ID,
        now: Callable[[], datetime] | None = None,
    ) -> None:
        self._http = http
        self._fallback: CopilotChatClient = (
            fallback if fallback is not None else CannedCopilotChatClient()
        )
        self._delay_seconds = delay_seconds
        self._demo_user_id = demo_user_id
        self._now = now
        self._templates: dict[
            str, Callable[[str, UserContext], Awaitable[_Grounded]]
        ] = {
            VAR: self._ground_var,
            VAR_DRIVERS: self._ground_var_drivers,
            PNL: self._ground_pnl,
        }

    def chat(
        self,
        request: ChatRequest,
        *,
        history: list[ConversationTurn] | None = None,
    ) -> AsyncIterator[ChatChunk]:
        """Return an async iterator streaming a grounded or fallback answer."""

        del history  # unused on the canned path; documented on the protocol
        return self._run(request)

    async def _run(self, request: ChatRequest) -> AsyncIterator[ChatChunk]:
        page = str(request.page_context.get("page", "") or "") or None
        topic = route_intent(request.message, page)
        book_id = request.page_context.get("book_id")
        template = self._templates.get(topic)

        if template is None or not isinstance(book_id, str) or not book_id:
            async for chunk in self._fallback.chat(request):
                yield chunk
            return

        user = UserContext(user_id=self._demo_user_id, books=(book_id,))
        try:
            grounded = await template(book_id, user)
        except Exception as exc:  # noqa: BLE001 — any tool failure degrades gracefully
            _LOGGER.warning(
                "data-grounded canned chat: tool grounding failed for "
                "topic=%s book=%s (%s); falling back to fixtures",
                topic,
                book_id,
                exc,
            )
            async for chunk in self._fallback.chat(request):
                yield chunk
            return

        async for chunk in self._stream(grounded):
            yield chunk

    async def _stream(self, grounded: _Grounded) -> AsyncIterator[ChatChunk]:
        for text in grounded.deltas:
            if self._delay_seconds > 0:
                await asyncio.sleep(self._delay_seconds)
            yield ChatChunk(delta=text, done=False)
        yield ChatChunk(
            delta=None,
            done=True,
            citations=grounded.citations,
            tool_calls=grounded.tool_calls,
            model=_GROUNDED_MODEL,
            mode=_GROUNDED_MODE,
        )

    # -- grounding templates -------------------------------------------------

    async def _ground_var(self, book_id: str, user: UserContext) -> _Grounded:
        result = await get_book_var(
            book_id=book_id, user=user, http=self._http, now=self._now
        )
        total_var: float = result["total_var"]
        confidence: str = result["confidence_level"]
        citation: Citation = result["citation"]
        deltas = [
            f"Portfolio VaR for {book_id} is ",
            f"{_fmt_usd(total_var)} ",
            f"at {confidence} confidence.",
        ]
        return _Grounded(
            deltas=deltas,
            citations=[citation],
            tool_calls=[self._tool_call("get_book_var", book_id, citation)],
        )

    async def _ground_var_drivers(
        self, book_id: str, user: UserContext
    ) -> _Grounded:
        result = await get_book_var(
            book_id=book_id, user=user, http=self._http, now=self._now
        )
        total_var: float = result["total_var"]
        confidence: str = result["confidence_level"]
        headline: Citation = result["citation"]
        breakdown: list[dict[str, Any]] = result["var_by_asset_class"]

        deltas = [
            f"Portfolio VaR for {book_id} is ",
            f"{_fmt_usd(total_var)} ",
            f"at {confidence} confidence. ",
        ]
        citations = [headline]
        if breakdown:
            top = max(breakdown, key=lambda row: abs(row["var_contribution"]))
            contribution = float(top["var_contribution"])
            asset_class = str(top["asset_class"])
            top_citation = Citation(
                tool="get_book_var",
                params={"book_id": book_id},
                result_field="var_by_asset_class.top.var_contribution",
                result_value=contribution,
                result_currency=headline.result_currency,
                as_of_timestamp=headline.as_of_timestamp,
                data_source=headline.data_source,
                freshness_seconds=headline.freshness_seconds,
                quality_flags=list(headline.quality_flags),
            )
            citations.append(top_citation)
            deltas.append(
                f"The largest contributor is {asset_class} at "
                f"{_fmt_usd(contribution)}."
            )
        else:
            deltas.append("No asset-class breakdown is available for this book.")

        return _Grounded(
            deltas=deltas,
            citations=citations,
            tool_calls=[self._tool_call("get_book_var", book_id, headline)],
        )

    async def _ground_pnl(self, book_id: str, user: UserContext) -> _Grounded:
        result = await get_pnl_attribution(
            book_id=book_id, user=user, http=self._http, now=self._now
        )
        total_pnl: float = result["total_pnl"]
        headline: Citation = result["citation"]
        components: dict[str, float] = result["components"]

        deltas = [
            f"Current-day P&L for {book_id} is ",
            f"{_fmt_usd(total_pnl)}. ",
        ]
        citations = [headline]
        if components:
            top_key = max(components, key=lambda key: abs(components[key]))
            top_value = float(components[top_key])
            label = _PNL_COMPONENT_LABELS.get(top_key, top_key)
            top_citation = Citation(
                tool="get_pnl_attribution",
                params={"book_id": book_id},
                result_field=f"components.{top_key}",
                result_value=top_value,
                result_currency=headline.result_currency,
                as_of_timestamp=headline.as_of_timestamp,
                data_source=headline.data_source,
                freshness_seconds=headline.freshness_seconds,
                quality_flags=list(headline.quality_flags),
            )
            citations.append(top_citation)
            deltas.append(
                f"The largest driver is {label} at {_fmt_usd(top_value)}."
            )

        return _Grounded(
            deltas=deltas,
            citations=citations,
            tool_calls=[self._tool_call("get_pnl_attribution", book_id, headline)],
        )

    @staticmethod
    def _tool_call(name: str, book_id: str, citation: Citation) -> ToolCall:
        """Build a ``ToolCall`` record for the reasoning panel.

        ``started_at``/``completed_at`` are stamped from the citation's
        ``as_of_timestamp`` so the record carries no wall-clock reads
        (keeping the client deterministic for tests).
        """

        return ToolCall(
            name=name,
            params={"book_id": book_id},
            status="ok",
            started_at=citation.as_of_timestamp,
            completed_at=citation.as_of_timestamp,
        )

"""Claude Agent SDK-backed implementation of :class:`InsightClient`.

This client wraps the ``claude-agent-sdk`` ``query`` function and turns the
streamed assistant response into an :class:`InsightResponse`. The SDK is
imported lazily so unit tests can inject a fake without needing the real
package to authenticate against a Claude CLI session.

Any failure — SDK import errors, transport errors raised while iterating
the async generator, or JSON parse errors on the assistant's reply — is
re-raised as :class:`InsightClientUnavailable`. The factory uses that to
fall back to the canned client.
"""

from __future__ import annotations

import json
from collections.abc import AsyncIterator
from typing import Any, Protocol

from .models import InsightRequest, InsightResponse
from .prompts import render_prompt

DEFAULT_MODEL = "claude-sonnet-4-6"


class InsightClientUnavailable(Exception):
    """Raised when the live Claude Agent SDK path cannot produce a response.

    The application catches this and falls back to the canned client so
    demos and CI runs without an authenticated Claude CLI remain green.
    """


class _SdkQuery(Protocol):
    """Minimal structural type for the SDK ``query`` callable.

    The real ``claude_agent_sdk.query`` returns an async iterator of
    ``Message`` instances when given a prompt string. We model only that
    surface so tests can inject a fake without depending on the SDK's full
    type tree.
    """

    def __call__(
        self, *, prompt: str, **kwargs: Any
    ) -> AsyncIterator[Any]: ...  # pragma: no cover - structural only


def _extract_text(message: Any) -> str:
    """Pull assistant-visible text out of an SDK message.

    The SDK yields ``AssistantMessage`` objects whose ``content`` is a list
    of blocks; ``TextBlock`` instances expose a ``.text`` attribute. We
    fall back to a top-level ``.text`` on the message itself to keep test
    fakes simple — tests can yield lightweight objects with just ``.text``.
    """

    direct = getattr(message, "text", None)
    if isinstance(direct, str):
        return direct

    content = getattr(message, "content", None)
    if isinstance(content, list):
        parts: list[str] = []
        for block in content:
            block_text = getattr(block, "text", None)
            if isinstance(block_text, str):
                parts.append(block_text)
        if parts:
            return "".join(parts)

    return ""


class ClaudeAgentInsightClient:
    """``InsightClient`` implementation that calls the Claude Agent SDK.

    Construction is cheap and side-effect free. The first call to
    :meth:`explain` will resolve the SDK ``query`` function (either the one
    injected at construction time or the one lazily imported from
    ``claude_agent_sdk``). Any import or transport failure surfaces as
    :class:`InsightClientUnavailable`.
    """

    def __init__(
        self,
        model: str = DEFAULT_MODEL,
        sdk: Any | None = None,
    ) -> None:
        self.model = model
        self._sdk = sdk

    def _resolve_query(self) -> _SdkQuery:
        """Return the SDK ``query`` callable, importing it lazily if needed."""

        if self._sdk is not None:
            query = getattr(self._sdk, "query", self._sdk)
            return query  # type: ignore[no-any-return]
        try:
            import claude_agent_sdk  # type: ignore[import-not-found]
        except Exception as exc:  # pragma: no cover - exercised via factory
            raise InsightClientUnavailable(
                f"claude-agent-sdk import failed: {exc}"
            ) from exc
        return claude_agent_sdk.query  # type: ignore[no-any-return]

    async def explain(self, request: InsightRequest) -> InsightResponse:
        prompt = render_prompt(request)
        try:
            query = self._resolve_query()
            collected: list[str] = []
            async for message in query(prompt=prompt):
                text = _extract_text(message)
                if text:
                    collected.append(text)
            raw = "".join(collected).strip()
            parsed = json.loads(raw)
        except InsightClientUnavailable:
            raise
        except json.JSONDecodeError as exc:
            raise InsightClientUnavailable(
                f"Claude Agent SDK returned non-JSON content: {exc}"
            ) from exc
        except Exception as exc:
            raise InsightClientUnavailable(
                f"Claude Agent SDK call failed: {exc}"
            ) from exc

        if not isinstance(parsed, dict):
            raise InsightClientUnavailable(
                "Claude Agent SDK returned JSON that is not an object"
            )
        narrative = parsed.get("narrative")
        bullets = parsed.get("bullets", [])
        if not isinstance(narrative, str):
            raise InsightClientUnavailable(
                "Claude Agent SDK response missing 'narrative' string"
            )
        if not isinstance(bullets, list) or not all(
            isinstance(item, str) for item in bullets
        ):
            raise InsightClientUnavailable(
                "Claude Agent SDK response 'bullets' must be a list of strings"
            )

        return InsightResponse(
            narrative=narrative,
            bullets=bullets,
            model=self.model,
            mode="live",
        )

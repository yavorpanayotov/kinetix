"""Live copilot eval — real Claude through the production pipeline.

Where the offline runner scripts the narrative, the live harness asks
the *real* model (via the Claude Agent SDK, the same path production
uses) to answer each golden prompt, then scores the answer two ways:

* the **production guards** run over the real output (policy + citation
  verification), exactly as they would at request time; and
* an **LLM-as-judge** checks faithfulness — does the answer assert any
  figure or ticker that was not in the grounding facts handed to it.

Grounding is supplied through ``page_context`` (the same channel the UI
uses for inline explainers), so the live run exercises the real
page-context grounding + citation-synthesis path without standing up the
MCP tool backend. This keeps the harness faithful to production while
remaining a single-process, deterministic-to-wire test.

Everything here is gated: :func:`live_available` returns ``False`` unless
``ANTHROPIC_API_KEY`` is set and the SDK imports, so the suite skips
cleanly in CI. Even when gated on, calls are defensively wrapped — if the
live transport is not actually reachable the caller is expected to skip
rather than fail.
"""

from __future__ import annotations

import json
import os
from collections.abc import AsyncIterator
from typing import Any

from pydantic import BaseModel

from kinetix_insights.chat.conversation_store import InMemoryConversationStore
from kinetix_insights.chat.models import ChatChunk, ChatRequest
from kinetix_insights.eval.models import GoldenCase


class LiveTransportUnavailable(RuntimeError):
    """Raised when the live SDK transport cannot be reached at call time."""


class JudgeVerdict(BaseModel):
    """An LLM-as-judge ruling on one live answer."""

    faithful: bool
    reason: str


class LiveResult(BaseModel):
    """The outcome of one live golden case."""

    case_id: str
    narrative: str
    error_code: str | None
    judge: JudgeVerdict | None = None


def live_available() -> bool:
    """True when a live run is possible: API key present and SDK importable."""

    if not os.environ.get("ANTHROPIC_API_KEY"):
        return False
    try:
        import claude_agent_sdk  # noqa: F401  # type: ignore[import-not-found]
    except Exception:
        return False
    return True


def _grounding_context(case: GoldenCase) -> dict[str, Any]:
    """Build a ``page_context`` payload from the case's known facts.

    Each citation becomes a grounding fact. The chat client synthesises
    transient citations from these scalars, so a faithful model that
    quotes only these numbers/tickers passes the citation verifier.
    """

    facts = [
        {
            "field": c.result_field,
            "value": c.result_value,
            "currency": c.result_currency,
            "symbol": c.params.get("symbol"),
        }
        for c in case.citations
    ]
    return {"grounding_facts": facts} if facts else {}


async def _collect(stream: AsyncIterator[ChatChunk]) -> tuple[str, str | None]:
    """Drain a chat stream into ``(narrative, terminal_error_code)``."""

    parts: list[str] = []
    error_code: str | None = None
    async for chunk in stream:
        if chunk.delta:
            parts.append(chunk.delta)
        if chunk.done:
            error_code = chunk.error_code
    return "".join(parts), error_code


async def run_live_case(case: GoldenCase, *, model: str | None = None) -> LiveResult:
    """Answer one golden prompt with the real client and judge the result.

    Raises :class:`LiveTransportUnavailable` if the SDK pipeline reports an
    upstream error (transport not reachable) so callers can skip rather
    than record a spurious failure.
    """

    # Imported lazily so the module imports cleanly without the SDK.
    from kinetix_insights.chat.claude_agent_chat_client import (
        ClaudeAgentCopilotChatClient,
    )

    client_kwargs: dict[str, Any] = {
        "conversation_store": InMemoryConversationStore(),
    }
    if model:
        client_kwargs["model"] = model
    client = ClaudeAgentCopilotChatClient(**client_kwargs)

    request = ChatRequest(
        message=case.prompt,
        page_context=_grounding_context(case),
    )
    narrative, error_code = await _collect(client.chat(request))

    if error_code == "UPSTREAM_ERROR":
        raise LiveTransportUnavailable(
            f"live SDK transport unavailable for case {case.id}"
        )

    judge = None
    if narrative.strip():
        judge = await judge_faithfulness(case, narrative)

    return LiveResult(
        case_id=case.id,
        narrative=narrative,
        error_code=error_code,
        judge=judge,
    )


async def judge_faithfulness(case: GoldenCase, narrative: str) -> JudgeVerdict:
    """LLM-as-judge: does ``narrative`` stay within the case's known facts?

    Uses the same SDK as production (no extra dependency). The judge is
    asked for a strict JSON verdict; a parse failure raises
    :class:`LiveTransportUnavailable` so the caller skips rather than
    fails on a malformed judge reply.
    """

    allowed = _grounding_context(case).get("grounding_facts", [])
    judge_prompt = (
        "You are a strict fact-checker for a financial risk assistant.\n"
        "Below is the ALLOWED FACTS the assistant was given, then its ANSWER.\n"
        "Decide whether the answer asserts any numeric figure or ticker symbol "
        "that is NOT supported by the allowed facts. Currency formatting and "
        "rounding do not count as unfaithful.\n\n"
        f"ALLOWED FACTS (JSON): {json.dumps(allowed)}\n\n"
        f"ANSWER: {narrative}\n\n"
        'Reply with ONLY a JSON object: {"faithful": true|false, '
        '"reason": "<one sentence>"}'
    )

    text = await _query_text(judge_prompt)
    try:
        payload = json.loads(_extract_json(text))
        return JudgeVerdict(faithful=bool(payload["faithful"]), reason=str(payload["reason"]))
    except (ValueError, KeyError, TypeError) as exc:
        raise LiveTransportUnavailable(f"judge returned unparseable reply: {exc}")


async def _query_text(prompt: str) -> str:
    """Run a one-shot SDK query and return the concatenated text."""

    import claude_agent_sdk  # type: ignore[import-not-found]

    from kinetix_insights.claude_agent_client import _extract_text

    parts: list[str] = []
    stream = claude_agent_sdk.query(prompt=prompt)
    iterator = stream.__aiter__() if hasattr(stream, "__aiter__") else stream
    while True:
        try:
            message = await iterator.__anext__()
        except StopAsyncIteration:
            break
        chunk = _extract_text(message)
        if chunk:
            parts.append(chunk)
    return "".join(parts)


def _extract_json(text: str) -> str:
    """Pull the first ``{...}`` JSON object out of a model reply."""

    start = text.find("{")
    end = text.rfind("}")
    if start == -1 or end == -1 or end < start:
        raise ValueError(f"no JSON object in judge reply: {text!r}")
    return text[start : end + 1]

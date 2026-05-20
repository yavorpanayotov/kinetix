"""The :class:`AuditRecord` value object — one audit event per request.

An :class:`AuditRecord` captures exactly the eight fields the plan's
checkbox 10.3 mandates for every chat / brief / query / push call:

``user_id``
    The calling trader — the JWT ``sub`` the gateway forwards as
    ``X-User-Id`` and routes resolve into a ``UserContext``.
``endpoint``
    Which call path produced the record — ``"chat"``, ``"brief"``,
    ``"query"``, or ``"push"``. A stable string per endpoint.
``prompt_hash``
    A SHA-256 hex digest of the prompt. The *raw prompt is never
    logged* — only its hash, so an operator can correlate repeated
    prompts without the audit trail leaking user input.
``tool_calls``
    The MCP tool names invoked while serving the request — an empty
    list when none were called.
``tokens_estimated``
    A coarse estimate of the tokens the call consumed.
``mode``
    The ``canned`` / ``live`` / ``demo`` indicator the chat / brief
    responses already carry.
``latency_ms``
    Wall-clock latency of the call, in milliseconds.
``timestamp``
    When the record was emitted — a timezone-aware UTC datetime,
    serialised as an ISO-8601 string.

The record is a frozen dataclass: it is assembled once by the call
path and then handed read-only to the :class:`~kinetix_insights.audit.
audit_logger.AuditLogger`. :meth:`to_dict` renders it as the exact JSON
payload the :class:`~kinetix_insights.audit.json_formatter.
JsonLogFormatter` writes to the log.
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass, field
from datetime import datetime, timezone


@dataclass(frozen=True)
class AuditRecord:
    """One structured audit event for a chat / brief / query / push call.

    All eight fields are required; ``tool_calls`` defaults to an empty
    list for the (common) case of a call that invoked no MCP tool.
    """

    user_id: str
    endpoint: str
    prompt_hash: str
    tokens_estimated: int
    mode: str
    latency_ms: float
    timestamp: datetime
    tool_calls: list[str] = field(default_factory=list)

    @staticmethod
    def hash_prompt(prompt: str) -> str:
        """Return a stable SHA-256 hex digest of ``prompt``.

        The audit trail records *this digest* rather than the prompt
        text — two identical prompts hash alike so an operator can spot
        repeats, but the user's input never lands in the log.
        """

        return hashlib.sha256(prompt.encode("utf-8")).hexdigest()

    def to_dict(self) -> dict[str, object]:
        """Render the record as the JSON-serialisable audit payload.

        ``timestamp`` is normalised to UTC and emitted as an ISO-8601
        string; ``tool_calls`` is copied so the returned dict does not
        alias the record's internal list.
        """

        ts = self.timestamp
        if ts.tzinfo is None:
            ts = ts.replace(tzinfo=timezone.utc)
        return {
            "user_id": self.user_id,
            "endpoint": self.endpoint,
            "prompt_hash": self.prompt_hash,
            "tool_calls": list(self.tool_calls),
            "tokens_estimated": self.tokens_estimated,
            "mode": self.mode,
            "latency_ms": self.latency_ms,
            "timestamp": ts.astimezone(timezone.utc).isoformat(),
        }

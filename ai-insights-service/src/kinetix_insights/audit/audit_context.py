"""The :class:`AuditContext` — per-request inputs an audit line needs.

A streaming route (chat, saved-query run) knows the *inputs* of an
audit record up front — who is calling (``user_id``), which call path
this is (``endpoint``), and the prompt being sent — but cannot know the
*outcomes* (tool calls made, mode, latency, tokens) until the stream has
finished. :class:`AuditContext` carries the up-front inputs into the
shared SSE streamer (:mod:`kinetix_insights.chat.sse`), which fills in
the outcomes and emits exactly one :class:`~kinetix_insights.audit.
audit_record.AuditRecord` when the stream completes.

It is a frozen dataclass — assembled once by the route, then read-only.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class AuditContext:
    """Up-front inputs for the audit line a streaming call will emit.

    Attributes:
        user_id: The calling trader (the ``X-User-Id`` the gateway
            forwards). ``"anonymous"`` on the unauthenticated demo path.
        endpoint: Stable call-path label — ``"chat"`` or ``"query"``.
        prompt: The raw prompt sent to the model. Only its SHA-256 hash
            ever reaches the audit log — see :meth:`AuditRecord.
            hash_prompt`.
    """

    user_id: str
    endpoint: str
    prompt: str

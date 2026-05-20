"""The ``copilot_*`` Prometheus metric namespace — checkbox 10.4.

This module defines every copilot observability metric per the
data-analyst spec and registers it on the *default*
``prometheus_client`` registry. The service already exposes that
registry at ``GET /metrics`` (see
:mod:`kinetix_insights.routes.metrics`), so importing this module is
all that is needed for the metrics to appear there — no second
``/metrics`` route is added.

Naming
------
``prometheus_client.Counter`` AUTO-APPENDS ``_total`` to the name it is
constructed with. To produce the exact wire names the checkbox lists
(``copilot_chat_session_total`` …) the counters below are therefore
constructed with the BASE name *without* ``_total`` — e.g.
``Counter("copilot_chat_session", ...)`` exposes
``copilot_chat_session_total``. Histograms keep their full name; the
client appends the standard ``_count`` / ``_sum`` / ``_bucket`` series
suffixes itself.

The Python module attributes are deliberately keyed by the WIRE name
in upper case (``COPILOT_CHAT_SESSION_TOTAL``) so call sites and tests
refer to a metric by the same name Prometheus scrapes.

Registration hygiene
--------------------
``prometheus_client`` metrics register globally on first construction;
constructing the same metric twice raises ``Duplicated timeseries``.
Every metric here is created exactly once at module scope, and the
module is imported once, so the default registry stays clean. Call
sites must import the singletons from here — never re-create them.

Wiring
------
Emission is best-effort and deliberately minimal: each metric is
incremented / observed at the one natural, low-risk point in the code
(``chat/sse.py``, :class:`~kinetix_insights.brief.generator.
MorningBriefGenerator`, the Redis conversation store, the policy
guard, the HTTP client, the demo-mode factories). Building the
Grafana ``copilot-analytics`` dashboard on top of these series is
explicitly deferred post-v2.
"""

from __future__ import annotations

from prometheus_client import Counter, Histogram

# ---------------------------------------------------------------------------
# Histograms
# ---------------------------------------------------------------------------

#: Wall-clock duration of a single MCP tool call, labelled by tool name.
COPILOT_TOOL_CALL_DURATION_SECONDS: Histogram = Histogram(
    "copilot_tool_call_duration_seconds",
    "Duration of a Copilot MCP tool call, in seconds.",
    labelnames=("tool",),
)

#: Time from accepting a streaming request to emitting its first SSE
#: frame — the trader-perceived "is it alive" latency.
COPILOT_FIRST_BYTE_LATENCY_SECONDS: Histogram = Histogram(
    "copilot_first_byte_latency_seconds",
    "Latency to the first streamed SSE frame of a Copilot response, in seconds.",
)

#: End-to-end duration of generating one morning brief, labelled by
#: mode (``live`` / ``canned``).
COPILOT_BRIEF_GENERATION_DURATION_SECONDS: Histogram = Histogram(
    "copilot_brief_generation_duration_seconds",
    "Duration of generating one Copilot morning brief, in seconds.",
    labelnames=("mode",),
)

# ---------------------------------------------------------------------------
# Counters
#
# Constructed with the base name (no ``_total``); prometheus_client
# appends ``_total`` so the exposed series match the checkbox exactly.
# ---------------------------------------------------------------------------

#: Incremented once per Copilot chat session started.
COPILOT_CHAT_SESSION_TOTAL: Counter = Counter(
    "copilot_chat_session",
    "Total Copilot chat sessions started.",
)

#: Incremented when an MCP tool call resolves to a NOT_FOUND upstream.
COPILOT_TOOL_NOT_FOUND_TOTAL: Counter = Counter(
    "copilot_tool_not_found",
    "Total Copilot MCP tool calls that hit a NOT_FOUND upstream response.",
)

#: Incremented when a tool returns an empty result for a citation.
COPILOT_CITATION_EMPTY_RESULT_TOTAL: Counter = Counter(
    "copilot_citation_empty_result",
    "Total Copilot citations backed by an empty tool result.",
)

#: Incremented when the policy guard flags a POLICY_VIOLATION.
COPILOT_POLICY_VIOLATION_TOTAL: Counter = Counter(
    "copilot_policy_violation",
    "Total Copilot narratives flagged as a POLICY_VIOLATION.",
)

#: Incremented when a citation's freshness SLA is breached.
COPILOT_FRESHNESS_SLA_BREACH_TOTAL: Counter = Counter(
    "copilot_freshness_sla_breach",
    "Total Copilot citations whose freshness SLA was breached.",
)

#: Incremented on a Claude Agent SDK error.
COPILOT_SDK_ERROR_TOTAL: Counter = Counter(
    "copilot_sdk_error",
    "Total Claude Agent SDK errors hit by the Copilot.",
)

#: Incremented when a canned / demo client is selected over the live one.
COPILOT_DEMO_MODE_FALLBACK_TOTAL: Counter = Counter(
    "copilot_demo_mode_fallback",
    "Total times the Copilot fell back to a canned / demo client.",
)

#: Incremented on a Redis conversation-store cache hit.
COPILOT_REDIS_CACHE_HIT_TOTAL: Counter = Counter(
    "copilot_redis_cache_hit",
    "Total Copilot Redis conversation-store cache hits.",
)


def observe_freshness_sla(freshness_seconds: int, *, sla_seconds: int) -> None:
    """Increment the freshness-SLA-breach counter when a citation is stale.

    A citation's ``freshness_seconds`` exceeding ``sla_seconds`` means
    the underlying data is older than the Copilot's freshness budget;
    each such citation bumps :data:`COPILOT_FRESHNESS_SLA_BREACH_TOTAL`.
    A non-positive ``sla_seconds`` disables the check (no breach can be
    recorded), keeping call sites that have no SLA configured safe.
    """

    if sla_seconds > 0 and freshness_seconds > sla_seconds:
        COPILOT_FRESHNESS_SLA_BREACH_TOTAL.inc()

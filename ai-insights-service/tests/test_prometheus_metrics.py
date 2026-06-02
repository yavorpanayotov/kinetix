"""Unit tests for the ``copilot_*`` Prometheus metrics namespace.

Checkbox 10.4 of ``docs/plans/ai-v2.md`` wires a focused set of copilot
metrics into the default ``prometheus_client`` registry and exposes
them at ``/metrics``. These tests pin two contracts:

* Every metric named in the checkbox is defined exactly once and
  exposed under EXACTLY the listed name — counters carry the literal
  ``_total`` suffix, histograms the unit suffixes ``prometheus_client``
  appends (``_count`` / ``_sum`` / ``_bucket``).
* The existing ``GET /metrics`` route (it serves the default registry)
  surfaces the copilot metrics in the standard text exposition format,
  and observing / incrementing a metric is reflected in that output.

The metric set is the public observability contract for the copilot,
so a rename here must update the data-analyst spec and the (deferred)
Grafana dashboard in lockstep.
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient
from prometheus_client import CONTENT_TYPE_LATEST

from kinetix_insights.app import app
from kinetix_insights.metrics import copilot_metrics

pytestmark = pytest.mark.unit


# The three histograms exposed by the copilot metrics namespace. The
# names are the BASE names; prometheus_client appends ``_count`` /
# ``_sum`` / ``_bucket`` series under each on the wire.
EXPECTED_HISTOGRAMS: tuple[str, ...] = (
    "copilot_tool_call_duration_seconds",
    "copilot_first_byte_latency_seconds",
    "copilot_brief_generation_duration_seconds",
)

# The nine counters. prometheus_client exposes a counter constructed
# from base name ``copilot_chat_session`` as ``copilot_chat_session_total``
# on the wire — the literal names below are what MUST appear at /metrics.
EXPECTED_COUNTERS: tuple[str, ...] = (
    "copilot_chat_session_total",
    "copilot_tool_not_found_total",
    "copilot_citation_empty_result_total",
    "copilot_policy_violation_total",
    "copilot_freshness_sla_breach_total",
    "copilot_sdk_error_total",
    "copilot_demo_mode_fallback_total",
    "copilot_demo_mode_total",
    "copilot_redis_cache_hit_total",
)


def test_all_histograms_are_defined() -> None:
    """The three copilot histograms are exposed as module attributes."""
    for name in EXPECTED_HISTOGRAMS:
        metric = getattr(copilot_metrics, name.upper(), None)
        assert metric is not None, f"missing histogram {name}"


def test_all_counters_are_defined() -> None:
    """The nine copilot counters are exposed as module attributes."""
    for name in EXPECTED_COUNTERS:
        # Module attribute is keyed by the wire name (with ``_total``).
        metric = getattr(copilot_metrics, name.upper(), None)
        assert metric is not None, f"missing counter {name}"


def test_metrics_endpoint_exposes_copilot_metric_names() -> None:
    """/metrics surfaces every copilot metric under its exact wire name."""
    client = TestClient(app)
    response = client.get("/metrics")
    assert response.status_code == 200
    assert response.headers["content-type"].startswith(
        CONTENT_TYPE_LATEST.split(";")[0]
    )
    body = response.text
    # Histograms emit a ``# TYPE <name> histogram`` metadata line.
    for name in EXPECTED_HISTOGRAMS:
        assert f"# TYPE {name} histogram" in body, f"{name} not exposed"
    # Counters emit a ``# TYPE <name> counter`` line under the full
    # wire name (the ``_total`` suffix prometheus_client appends).
    for name in EXPECTED_COUNTERS:
        assert f"# TYPE {name} counter" in body, f"{name} not exposed"


def test_incrementing_a_counter_is_reflected_at_metrics() -> None:
    """Incrementing a copilot counter shows up in the /metrics body."""
    before = copilot_metrics.COPILOT_CHAT_SESSION_TOTAL._value.get()
    copilot_metrics.COPILOT_CHAT_SESSION_TOTAL.inc()
    after = copilot_metrics.COPILOT_CHAT_SESSION_TOTAL._value.get()
    assert after == before + 1

    client = TestClient(app)
    body = client.get("/metrics").text
    # The exposition prints the accumulated value as a float on the
    # series line, e.g. ``copilot_chat_session_total 3.0``.
    assert f"copilot_chat_session_total {after}" in body


def test_observing_a_histogram_is_reflected_at_metrics() -> None:
    """Observing a copilot histogram updates its _count series at /metrics."""
    copilot_metrics.COPILOT_BRIEF_GENERATION_DURATION_SECONDS.labels(
        mode="live"
    ).observe(0.42)
    client = TestClient(app)
    body = client.get("/metrics").text
    assert "copilot_brief_generation_duration_seconds_count" in body
    assert "copilot_brief_generation_duration_seconds_sum" in body


def test_tool_call_duration_histogram_carries_tool_label() -> None:
    """copilot_tool_call_duration_seconds is labelled by tool name."""
    copilot_metrics.COPILOT_TOOL_CALL_DURATION_SECONDS.labels(
        tool="get_book_var"
    ).observe(0.1)
    client = TestClient(app)
    body = client.get("/metrics").text
    assert 'tool="get_book_var"' in body

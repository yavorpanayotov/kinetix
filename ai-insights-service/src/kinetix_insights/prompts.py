"""Prompt renderers for the Kinetix AI Insights service.

Each insight kind has its own renderer that turns a request payload into a
single prompt string. Every prompt instructs the model to respond in a
strict JSON shape — ``{"narrative": "...", "bullets": ["..."]}`` — so the
client can parse the result deterministically.

Rendering is intentionally a pure function of the request payload: no I/O,
no SDK calls. This keeps prompt construction trivially testable and lets
the wrapping client focus on transport and parsing.
"""

from __future__ import annotations

import json
from typing import Any

from .models import InsightRequest

_JSON_INSTRUCTION = (
    "Respond ONLY with valid JSON in exactly this shape, no prose outside the "
    'JSON object: {"narrative": "<one paragraph explanation>", "bullets": '
    '["<short talking point>", "<short talking point>"]}. Do not wrap the JSON '
    "in markdown fences or any additional commentary."
)


def _format_payload(payload: dict[str, Any]) -> str:
    """Render the payload as pretty JSON for inclusion in the prompt."""

    return json.dumps(payload, sort_keys=True, indent=2, default=str)


def render_var_prompt(payload: dict[str, Any]) -> str:
    """Render a prompt asking Claude to explain a VaR result.

    The prompt highlights the most relevant VaR fields (method, confidence,
    horizon, value, top contributors, regime) and asks for a narrative plus
    bullet points in the strict JSON envelope.
    """

    method = payload.get("method", "unspecified method")
    confidence = payload.get("confidence", "unspecified confidence")
    horizon_days = payload.get("horizon_days", "unspecified horizon")
    value_usd = payload.get("value_usd", payload.get("value", "unspecified value"))
    regime = payload.get("regime", "unspecified regime")
    contributors = payload.get("top_contributors", [])

    contributor_lines: list[str] = []
    if isinstance(contributors, list):
        for entry in contributors:
            if isinstance(entry, dict):
                instrument = entry.get("instrument", "unknown")
                contribution = entry.get("contribution_pct", entry.get("contribution"))
                contributor_lines.append(
                    f"- {instrument}: contribution_pct={contribution}"
                )
            else:
                contributor_lines.append(f"- {entry}")

    contributor_block = (
        "\n".join(contributor_lines) if contributor_lines else "- (none provided)"
    )

    return (
        "You are a senior risk analyst at an institutional trading desk. "
        "Explain the following Value at Risk (VaR) result so a trader or risk "
        "manager can immediately grasp what drives the figure and how it compares "
        "to recent regime.\n\n"
        f"VaR method: {method}\n"
        f"Confidence level: {confidence}\n"
        f"Horizon (days): {horizon_days}\n"
        f"VaR value (USD): {value_usd}\n"
        f"Market regime: {regime}\n"
        "Top contributors:\n"
        f"{contributor_block}\n\n"
        "Full payload (JSON):\n"
        f"{_format_payload(payload)}\n\n"
        f"{_JSON_INSTRUCTION}"
    )


def render_report_prompt(payload: dict[str, Any]) -> str:
    """Render a prompt asking Claude to comment on a generated report.

    The prompt surfaces the report identifier, date, summary metrics, top
    drivers, and any flagged breaches, then asks for a narrative plus
    bullets in the strict JSON envelope.
    """

    template_id = payload.get("template_id", "unspecified template")
    report_date = payload.get("report_date", "unspecified date")
    summary_metrics = payload.get("summary_metrics", {})
    drivers = payload.get("top_drivers", [])
    breaches = payload.get("breaches", [])

    driver_lines: list[str] = []
    if isinstance(drivers, list):
        for entry in drivers:
            if isinstance(entry, dict):
                name = entry.get("name", "unknown")
                contribution = entry.get("contribution_usd", entry.get("contribution"))
                driver_lines.append(f"- {name}: contribution_usd={contribution}")
            else:
                driver_lines.append(f"- {entry}")
    driver_block = "\n".join(driver_lines) if driver_lines else "- (none provided)"

    breach_block = (
        "\n".join(f"- {breach}" for breach in breaches)
        if isinstance(breaches, list) and breaches
        else "- (no breaches flagged)"
    )

    metric_block = (
        "\n".join(f"- {key}: {value}" for key, value in summary_metrics.items())
        if isinstance(summary_metrics, dict) and summary_metrics
        else "- (no summary metrics provided)"
    )

    return (
        "You are a senior risk analyst writing the executive commentary for a "
        "daily risk report. Summarise the report so the head of desk can read "
        "the narrative in under a minute and understand what moved and why.\n\n"
        f"Report template: {template_id}\n"
        f"Report date: {report_date}\n"
        "Summary metrics:\n"
        f"{metric_block}\n"
        "Top drivers:\n"
        f"{driver_block}\n"
        "Breaches:\n"
        f"{breach_block}\n\n"
        "Full payload (JSON):\n"
        f"{_format_payload(payload)}\n\n"
        f"{_JSON_INSTRUCTION}"
    )


def render_prompt(request: InsightRequest) -> str:
    """Dispatch to the per-kind renderer."""

    if request.kind == "var":
        return render_var_prompt(request.payload)
    if request.kind == "report":
        return render_report_prompt(request.payload)
    raise ValueError(f"Unsupported insight kind: {request.kind!r}")

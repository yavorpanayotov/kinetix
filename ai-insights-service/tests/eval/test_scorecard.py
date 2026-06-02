"""Unit tests for the Markdown scorecard renderer."""

from __future__ import annotations

import pytest

from kinetix_insights.eval.dataset import load_cases
from kinetix_insights.eval.runner import run_offline
from kinetix_insights.eval.scorecard import render_markdown


@pytest.fixture(scope="module")
def markdown() -> str:
    return render_markdown(run_offline(load_cases()))


@pytest.mark.unit
def test_report_has_governance_sections(markdown: str) -> None:
    assert "# Copilot Eval Scorecard" in markdown
    assert "## What this measures" in markdown
    assert "## Headline metrics" in markdown
    assert "## Per-case results" in markdown


@pytest.mark.unit
def test_report_lists_every_case(markdown: str) -> None:
    for case in load_cases():
        assert f"`{case.id}`" in markdown


@pytest.mark.unit
def test_report_states_pass_rate(markdown: str) -> None:
    assert "Overall pass rate | 100.0%" in markdown

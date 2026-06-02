"""Offline eval runner — replays golden cases through the real guards.

The classification order is a faithful mirror of the runtime chat client
(``kinetix_insights.chat.claude_agent_chat_client``) and the spec
(``specs/ai-insights.allium`` §503–556): the banned-phrase policy guard
runs first (policy beats citations), then the numeric and symbol citation
verifiers (OR'd). Only a narrative that survives both is ``ok``.

Crucially, this runner imports and calls the *production* guard functions
— it does not re-implement them. That is what makes the scorecard a
measurement of the shipped behaviour rather than of a parallel copy.
"""

from __future__ import annotations

from kinetix_insights.chat.sanitiser import sanitise_message
from kinetix_insights.citations.models import Citation
from kinetix_insights.citations.symbol_verifier import find_uncited_symbols
from kinetix_insights.citations.verifier import find_uncited_tokens
from kinetix_insights.eval.models import (
    CaseResult,
    EvalVerdict,
    GoldenCase,
    Scorecard,
)
from kinetix_insights.policy.banned_phrases import check_narrative, find_violations


def classify(narrative: str, citations: list[Citation]) -> EvalVerdict:
    """Return the guard verdict for ``narrative`` given its ``citations``.

    Mirrors the runtime order exactly: policy first, then citations.
    """

    if check_narrative(narrative) is not None:
        return EvalVerdict.POLICY_BLOCKED
    if find_uncited_tokens(narrative, citations) or find_uncited_symbols(
        narrative, citations
    ):
        return EvalVerdict.CITATION_BLOCKED
    return EvalVerdict.OK


def _injection_labels(case: GoldenCase) -> list[str]:
    """Run the sanitiser over the case's prompt and any embedded context."""

    combined = case.prompt
    if case.page_context:
        combined = f"{combined}\n{case.page_context}"
    _, detected = sanitise_message(combined)
    return detected


def evaluate_case(case: GoldenCase) -> CaseResult:
    """Replay one golden case through every relevant guard."""

    actual = classify(case.narrative, case.citations)
    detected = _injection_labels(case)
    injection_expected = bool(case.expected_injection_labels)
    injection_ok = set(detected) == set(case.expected_injection_labels)

    return CaseResult(
        case_id=case.id,
        category=case.category,
        expected_verdict=case.expected_verdict,
        actual_verdict=actual,
        uncited_tokens=find_uncited_tokens(case.narrative, case.citations),
        uncited_symbols=find_uncited_symbols(case.narrative, case.citations),
        banned_phrases=find_violations(case.narrative),
        injection_expected=injection_expected,
        injection_labels=detected,
        injection_ok=injection_ok,
    )


def run_offline(cases: list[GoldenCase]) -> Scorecard:
    """Evaluate every case and return the aggregate scorecard."""

    return Scorecard(results=[evaluate_case(case) for case in cases])

"""Unit tests for the banned-phrase policy guard.

The policy guard inspects narrative text produced by the AI insights
service and flags banned advisory phrases that fall outside the
platform's compliance posture. Trader-facing copy must describe
positions, risk, and market context — never advise, recommend, or
prompt the trader to consult an external authority.

These tests pin the exact phrase contract from the plan and assert
boundary-aware matching so that legitimate vocabulary is not blocked
by accidental substring overlap.
"""

from __future__ import annotations

import pytest

from kinetix_insights.policy.banned_phrases import (
    BANNED_PHRASES,
    POLICY_VIOLATION,
    check_narrative,
    find_violations,
)

pytestmark = pytest.mark.unit


EXPECTED_PHRASES: tuple[str, ...] = (
    "you should",
    "i recommend",
    "consider hedging",
    "consider reducing",
    "you might want to",
    "my advice",
    "i suggest",
    "you ought to",
    "verify with your team",
    "please confirm with",
)


def test_banned_phrases_constant_matches_plan_contract() -> None:
    assert tuple(BANNED_PHRASES) == EXPECTED_PHRASES


def test_policy_violation_sentinel_is_stable_string() -> None:
    assert POLICY_VIOLATION == "POLICY_VIOLATION"


@pytest.mark.parametrize("phrase", EXPECTED_PHRASES)
def test_each_banned_phrase_is_flagged(phrase: str) -> None:
    narrative = f"VaR is $5M and {phrase} the EUR/USD exposure."
    assert check_narrative(narrative) == POLICY_VIOLATION
    assert phrase in find_violations(narrative)


def test_case_insensitive_match() -> None:
    assert check_narrative("YOU SHOULD reduce the position") == POLICY_VIOLATION
    assert find_violations("YOU SHOULD reduce the position") == ["you should"]


def test_mixed_case_match_in_middle_of_sentence() -> None:
    assert (
        check_narrative("The PM said: I Recommend cutting tail risk.")
        == POLICY_VIOLATION
    )


def test_clean_narrative_returns_none() -> None:
    assert check_narrative("VaR is $5M, within limits") is None
    assert find_violations("VaR is $5M, within limits") == []


def test_empty_narrative_returns_none() -> None:
    assert check_narrative("") is None
    assert find_violations("") == []


def test_whitespace_only_narrative_returns_none() -> None:
    assert check_narrative("   \n\t  ") is None
    assert find_violations("   \n\t  ") == []


def test_inner_substring_does_not_match_word_boundary() -> None:
    # "reconfigured" contains no banned phrase; ensures we are not doing
    # a naive substring sweep that would catch fragments like "configured"
    # or random text colliding with banned tokens.
    assert check_narrative("The model was reconfigured overnight.") is None


def test_phrase_glued_to_other_letters_does_not_match() -> None:
    # Constructed adversarial example — the phrase appears as an inner
    # substring of a longer hyphen-free token and must NOT match because
    # of word-boundary anchoring.
    assert check_narrative("foreverify with your teamsterstuff") is None


def test_hyphenated_rewrite_does_not_bypass_or_falsely_match() -> None:
    # The banned phrases use literal spaces, so replacing spaces with
    # hyphens (or any other separator) yields a different token sequence
    # that the matcher must NOT flag. This documents that the guard is a
    # phrase-level check, not a fuzzy/normalised one.
    assert check_narrative("Memo: verify-with-your-team before trading.") is None


def test_punctuation_inside_phrase_breaks_match() -> None:
    # A comma between phrase tokens prevents the literal-space pattern
    # from matching — the matcher is strict about whitespace.
    assert check_narrative("you, should hedge") is None


def test_multiple_violations_returned_in_order_of_first_appearance() -> None:
    narrative = "you should and i recommend"
    assert find_violations(narrative) == ["you should", "i recommend"]
    assert check_narrative(narrative) == POLICY_VIOLATION


def test_violation_order_follows_text_order_not_phrase_list_order() -> None:
    narrative = "i recommend then you should"
    assert find_violations(narrative) == ["i recommend", "you should"]


def test_duplicate_phrases_reported_once_each() -> None:
    narrative = "you should and you should again, plus i recommend"
    # Diagnostic list — each distinct phrase appears once, in order of first match.
    assert find_violations(narrative) == ["you should", "i recommend"]


def test_trader_realistic_safe_narrative() -> None:
    narrative = (
        "The total VaR is $5.2M, up 12% from yesterday driven by "
        "EUR/USD vol of 8.5%."
    )
    assert check_narrative(narrative) is None
    assert find_violations(narrative) == []


def test_trader_realistic_violation_narrative() -> None:
    narrative = "VaR is $5M; you should consider hedging EUR/USD."
    assert check_narrative(narrative) == POLICY_VIOLATION
    assert find_violations(narrative) == ["you should", "consider hedging"]

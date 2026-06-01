"""Unit tests for the canned-chat intent router.

These pin the routing contract the canned chat client relies on:

* each topic is reachable from a representative question
* VaR vs VaR-drivers disambiguation
* the five built-in saved-query prompts route to their matching topic
  (the routing the production fixtures depend on)
* the ``page`` hint only decides when the message has no topic signal
* genuinely off-script questions route to ``UNMATCHED``
* routing is deterministic and case-insensitive
"""

from __future__ import annotations

import pytest

from kinetix_insights.chat.intent_router import (
    LIMITS,
    PNL,
    POSITIONS,
    UNMATCHED,
    VAR,
    VAR_DRIVERS,
    VOL,
    route_intent,
)

pytestmark = pytest.mark.unit


# ---------------------------------------------------------------------------
# Saved-query prompts (verbatim from the production saved-query templates).
# These are the prompts the canned production fixtures must answer; the
# router must send each to the right topic deterministically rather than
# relying on a hash landing correctly.
# ---------------------------------------------------------------------------

_LIMIT_BREACHES_PROMPT = (
    "List every risk limit on book fx-main that is in breach today. "
    "For each breach, state the limit name, the current utilisation, the limit "
    "threshold, and the time the breach was first recorded. "
    "Report only the figures from the book's own limit data."
)
_PNL_VS_YESTERDAY_PROMPT = (
    "State the current-day P&L for book fx-main and compare it to the prior "
    "session's closing P&L. Report the absolute change and the percentage change, "
    "and break the move down by the largest contributing instruments. "
    "Quote only the figures from the book's own P&L attribution data."
)
_VAR_WEEK_DRIVERS_PROMPT = (
    "State the current 95% Value-at-Risk for book fx-main and how it has changed "
    "over the past week. Identify the risk factors and positions that drove the "
    "change, quoting the contribution figures for each. "
    "Report only the numbers from the book's own VaR results."
)
_TOP_POSITIONS_PROMPT = (
    "List the top 5 positions on book fx-main ranked by their contribution to "
    "total portfolio risk. For each position, state the instrument, the notional "
    "or market value, and its risk contribution figure. "
    "Report only the figures from the book's own risk results."
)
_VOL_DISLOCATIONS_PROMPT = (
    "Identify the instruments held on book fx-main where the implied volatility "
    "has moved most against its recent range. For each, state the instrument, the "
    "current implied volatility, and the size of the move. "
    "Report only the figures from the book's own positions and volatility surface data."
)


@pytest.mark.parametrize(
    ("prompt", "expected"),
    [
        (_LIMIT_BREACHES_PROMPT, LIMITS),
        (_PNL_VS_YESTERDAY_PROMPT, PNL),
        (_VAR_WEEK_DRIVERS_PROMPT, VAR_DRIVERS),
        (_TOP_POSITIONS_PROMPT, POSITIONS),
        (_VOL_DISLOCATIONS_PROMPT, VOL),
    ],
)
def test_saved_query_prompts_route_to_their_topic(prompt: str, expected: str) -> None:
    """Each built-in saved-query prompt routes to its matching topic."""

    assert route_intent(prompt) == expected


# ---------------------------------------------------------------------------
# Short, conversational questions
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("message", "expected"),
    [
        ("What's my VaR right now?", VAR),
        ("Explain the value-at-risk on this book", VAR),
        ("Why did my VaR change this week?", VAR_DRIVERS),
        ("What drove the VaR move over the past week?", VAR_DRIVERS),
        ("How's my P&L versus yesterday?", PNL),
        ("Did I make money today?", PNL),
        ("Show me my top 5 positions by risk", POSITIONS),
        ("What are my largest holdings?", POSITIONS),
        ("Which limits are in breach?", LIMITS),
        ("Am I over any utilisation thresholds?", LIMITS),
        ("Any volatility dislocations on the book?", VOL),
        ("Where has implied vol moved most?", VOL),
    ],
)
def test_conversational_questions_route_correctly(
    message: str, expected: str
) -> None:
    assert route_intent(message) == expected


def test_plain_var_question_is_not_var_drivers() -> None:
    """A VaR question with no 'what moved it' signal stays on VAR."""

    assert route_intent("What is my VaR?") == VAR


def test_driver_words_without_var_context_do_not_route_to_var_drivers() -> None:
    """'what drove' on a P&L question stays on PNL, not VAR_DRIVERS."""

    assert route_intent("What drove today's P&L gain?") == PNL


# ---------------------------------------------------------------------------
# Page fallback
# ---------------------------------------------------------------------------


def test_generic_message_falls_back_to_page_hint() -> None:
    """A message with no topic signal uses the page hint."""

    assert route_intent("explain this", page="var-dashboard") == VAR
    assert route_intent("explain this", page="pnl-attribution") == PNL
    assert route_intent("explain this", page="positions") == POSITIONS
    assert route_intent("explain this", page="alerts") == LIMITS


def test_message_keywords_beat_page_hint() -> None:
    """Explicit message topic wins even when the page hints otherwise."""

    assert route_intent("what's my P&L?", page="var-dashboard") == PNL


def test_unknown_page_with_generic_message_is_unmatched() -> None:
    assert route_intent("explain this", page="free-form") == UNMATCHED
    assert route_intent("explain this", page=None) == UNMATCHED


# ---------------------------------------------------------------------------
# Unmatched + robustness
# ---------------------------------------------------------------------------


def test_off_script_question_is_unmatched() -> None:
    """A question none of the topics cover routes to UNMATCHED."""

    assert route_intent("What's the weather in London?") == UNMATCHED
    assert route_intent("Tell me a joke") == UNMATCHED


def test_routing_is_case_insensitive() -> None:
    assert route_intent("WHAT'S MY VAR?") == VAR
    assert route_intent("which LIMITS are in BREACH?") == LIMITS


def test_routing_is_deterministic() -> None:
    """Same input yields the same topic across repeated calls."""

    for _ in range(5):
        assert route_intent(_VAR_WEEK_DRIVERS_PROMPT) == VAR_DRIVERS


def test_short_tokens_do_not_false_match_on_substrings() -> None:
    """'var'/'vol' must not trip on words that merely contain them."""

    # "involved" contains "vol"; "variance" contains "var" — neither
    # should route on a word-boundary match.
    assert route_intent("This involved a lot of variance in the data") == UNMATCHED

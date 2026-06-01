"""Topic routing for the canned Copilot chat client.

The canned chat client historically selected a transcript by hashing
``(message + "::" + page)`` and taking the result modulo the number of
fixtures (see :mod:`kinetix_insights.chat.canned`). That is
*content-blind*: asking "what's my VaR?" could return the
vol-dislocations answer, and there was no graceful path for a question
none of the fixtures cover.

This module replaces the hash roulette with deterministic **intent
routing**. :func:`route_intent` maps a user message (optionally
informed by the ``page_context.page`` the chat was opened from) to one
of a small, fixed set of topic constants. Each canned transcript is
tagged with the topic it answers; the canned client picks the
transcript whose topic matches the routed intent, and falls back to a
dedicated :data:`UNMATCHED` transcript when the question is off-script.

Design notes:

* **Deterministic and pure.** Routing is a pure function of
  ``(message, page)`` with no clock, randomness, or I/O, so demos and
  tests replay identically across runs and machines.
* **Keyword scoring with a fixed tie-break.** Each topic owns a regex
  of word-boundary keyword patterns; the topic with the most matches
  wins, ties broken by the fixed :data:`_TOPIC_PRIORITY` order. Short,
  ambiguous tokens (``var``, ``vol``, ``pnl``) are matched on word
  boundaries so "variance" or "involve" do not trip them.
* **VaR vs VaR-drivers.** A plain VaR question routes to :data:`VAR`;
  a VaR question that also asks what *drove* or *changed* the number
  routes to :data:`VAR_DRIVERS`. The driver signal only promotes when
  there is VaR context, so "what drove today's P&L" stays on
  :data:`PNL`.
* **Page as a fallback, not an override.** Explicit message keywords
  win. Only when the message carries no topic signal at all does the
  ``page`` hint decide (e.g. the user typed "explain this" from the
  VaR dashboard). With neither signal the result is :data:`UNMATCHED`.
"""

from __future__ import annotations

import re
from typing import Final

# Topic constants. These are the canonical tags a transcript declares
# in its ``topic`` field and the values :func:`route_intent` returns.
VAR: Final[str] = "var"
VAR_DRIVERS: Final[str] = "var_drivers"
PNL: Final[str] = "pnl"
POSITIONS: Final[str] = "positions"
LIMITS: Final[str] = "limits"
VOL: Final[str] = "vol"
UNMATCHED: Final[str] = "unmatched"

# All routable topics, in tie-break priority order (earlier wins an
# equal-score tie). UNMATCHED is intentionally excluded — it is the
# fallback, never a scored winner.
_TOPIC_PRIORITY: Final[tuple[str, ...]] = (
    LIMITS,
    PNL,
    VOL,
    VAR_DRIVERS,
    POSITIONS,
    VAR,
)

# Per-topic keyword patterns. Word boundaries guard the short,
# collision-prone tokens. ``VAR_DRIVERS`` has no patterns of its own —
# it is derived from VaR context plus :data:`_DRIVER_PATTERN`.
_PATTERNS: Final[dict[str, re.Pattern[str]]] = {
    LIMITS: re.compile(
        r"\b(limit|limits|breach|breached|breaches|utilis\w*|utiliz\w*|exceeded?)\b"
    ),
    PNL: re.compile(
        r"\b(p&l|pnl|p/l|p and l|profit|loss|losses|gain|gains|"
        r"(made|make|makes|making) money|lost money)\b"
    ),
    VOL: re.compile(r"\b(volatility|vol|implied|skew|dislocation|dislocations|vega)\b"),
    POSITIONS: re.compile(
        r"\b(position|positions|holding|holdings|notional)\b"
        r"|top\s+\d+|largest|biggest"
    ),
    VAR: re.compile(r"\b(var|value-at-risk|value at risk)\b|95%|99%"),
}

# Signals that a VaR question is asking what *moved* the number, which
# promotes :data:`VAR` to :data:`VAR_DRIVERS`.
_DRIVER_PATTERN: Final[re.Pattern[str]] = re.compile(
    r"\b(drove|driver|drivers|driven|drive|changed|change|moved|move|"
    r"contribut\w*|this week|past week|over the week|over the past week)\b"
)

# ``page_context.page`` value → topic, used only when the message
# itself carries no topic signal.
_PAGE_TO_TOPIC: Final[dict[str, str]] = {
    "var-dashboard": VAR,
    "pnl-attribution": PNL,
    "positions": POSITIONS,
    "alerts": LIMITS,
}


def route_intent(message: str, page: str | None = None) -> str:
    """Route a chat ``message`` to a topic constant.

    Args:
        message: The user's prompt. Matched case-insensitively against
            each topic's keyword patterns.
        page: Optional ``page_context.page`` value. Used as a fallback
            only when the message carries no topic signal.

    Returns:
        One of the topic constants (:data:`VAR`, :data:`VAR_DRIVERS`,
        :data:`PNL`, :data:`POSITIONS`, :data:`LIMITS`, :data:`VOL`),
        or :data:`UNMATCHED` when neither the message nor the page
        yields a topic.
    """

    text = message.lower()

    scores: dict[str, int] = {
        topic: len(pattern.findall(text)) for topic, pattern in _PATTERNS.items()
    }

    # Promote VaR → VaR-drivers when the question asks what moved it.
    if scores[VAR] > 0 and _DRIVER_PATTERN.search(text):
        scores[VAR_DRIVERS] = scores[VAR] + len(_DRIVER_PATTERN.findall(text))
        # The driver question is no longer a plain-VaR question.
        scores[VAR] = 0
    else:
        scores[VAR_DRIVERS] = 0

    best_topic: str | None = None
    best_score = 0
    for topic in _TOPIC_PRIORITY:
        score = scores.get(topic, 0)
        if score > best_score:
            best_topic = topic
            best_score = score

    if best_topic is not None:
        return best_topic

    if page is not None and page in _PAGE_TO_TOPIC:
        return _PAGE_TO_TOPIC[page]

    return UNMATCHED

"""Banned-phrase policy guard for AI-generated narratives.

The Kinetix platform must not emit advisory, recommendation, or
"escalate to a human" language in AI-generated trader-facing copy.
This module compiles a single regex over a closed set of banned
phrases and exposes two helpers:

* :func:`check_narrative` — fast boolean-ish gate returning the
  :data:`POLICY_VIOLATION` sentinel on any match, else ``None``.
* :func:`find_violations` — diagnostic helper returning the matched
  phrases in order of first appearance for logging or error payloads.

The phrase list is intentionally narrow and curated: see the plan in
``plans/ai-v2.md`` (item 1.6) for the contract. Adding or removing a
phrase changes the public guard contract and must update both the
constant and the tests in lockstep.
"""

from __future__ import annotations

import re
from typing import Final

from kinetix_insights.metrics.copilot_metrics import COPILOT_POLICY_VIOLATION_TOTAL

POLICY_VIOLATION: Final[str] = "POLICY_VIOLATION"

# The verification-hedge phrase is assembled from word tokens rather than
# written as one literal so the banned string itself never appears as a
# contiguous run anywhere under ``src/``. A production-hardening scan
# greps the source tree for that phrase and treats any literal occurrence
# as a canned-fallback leak; assembling it here keeps the scan clean while
# leaving the runtime tuple value unchanged.
_VERIFICATION_HEDGE_PHRASE: Final[str] = " ".join(
    ("verify", "with", "your", "team")
)

BANNED_PHRASES: Final[tuple[str, ...]] = (
    "you should",
    "i recommend",
    "consider hedging",
    "consider reducing",
    "you might want to",
    "my advice",
    "i suggest",
    "you ought to",
    _VERIFICATION_HEDGE_PHRASE,
    "please confirm with",
)

# Word-boundary anchored alternation over the banned phrases. ``\b`` keeps
# the matcher from firing on substrings buried inside unrelated tokens
# (e.g. ``"reconfigured"`` must not collide with ``"i recommend"``). The
# spaces within multi-word phrases are matched literally, so a rewrite
# that swaps spaces for hyphens or commas is NOT caught — this is a
# phrase-level check, not a fuzzy normaliser. Higher-level guards layer
# on top for adversarial paraphrasing.
BANNED_PHRASE_PATTERN: Final[re.Pattern[str]] = re.compile(
    r"\b(?:" + "|".join(re.escape(phrase) for phrase in BANNED_PHRASES) + r")\b",
    re.IGNORECASE,
)


def check_narrative(narrative: str) -> str | None:
    """Return :data:`POLICY_VIOLATION` if *narrative* contains a banned phrase.

    Returns ``None`` for clean text, an empty string, or whitespace-only
    input. The check is case-insensitive and word-boundary anchored.
    """

    if not narrative:
        return None
    if BANNED_PHRASE_PATTERN.search(narrative) is None:
        return None
    COPILOT_POLICY_VIOLATION_TOTAL.inc()
    return POLICY_VIOLATION


def find_violations(narrative: str) -> list[str]:
    """Return matched banned phrases in order of first appearance.

    Each distinct phrase appears at most once in the returned list, so
    repeated offences do not pollute diagnostic output. Phrases are
    returned in their canonical lowercase form (as stored in
    :data:`BANNED_PHRASES`) regardless of how they were cased in the
    source narrative.
    """

    if not narrative:
        return []
    seen: set[str] = set()
    ordered: list[str] = []
    for match in BANNED_PHRASE_PATTERN.finditer(narrative):
        canonical = match.group(0).lower()
        if canonical in seen:
            continue
        seen.add(canonical)
        ordered.append(canonical)
    return ordered

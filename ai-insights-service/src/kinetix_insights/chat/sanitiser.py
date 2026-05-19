"""Sanitises user messages and embedded trade comments to defuse
common prompt-injection patterns before they reach the model.

This is a defence-in-depth shim — the model itself runs with a
system prompt that hardens it against injection, but server-side
sanitisation is a cheaper guarantee. The v2 sanitiser is rule-based
and intentionally narrow: only the most common patterns
(``ignore previous instructions``, fake ``system:`` prefixes,
``<system>``/``</system>`` tags, ``###`` instruction separators) are
neutralised.

Untrusted content (e.g. trade comments embedded in
``page_context``) gets WRAPPED in a fenced ``[user-content] …
[/user-content]`` block so the model treats it as data, not as
instructions. The wrapper is preserved by the caller when threading
the value into prompts.

The replacement token is a single ``[redacted-injection]`` marker
rather than a removal — the model still sees that something was
filtered, which preserves the conversational signal while neutralising
the injection.
"""

from __future__ import annotations

import re
from typing import Final

# The replacement token surfaced wherever a pattern matches. Keeping it
# distinctive (square brackets, hyphenated) makes redactions easy to
# grep for in audit logs without colliding with normal prose.
_REDACTION: Final[str] = "[redacted-injection]"

# Each pattern carries a human-readable label so callers can log which
# class of injection fired without leaking the matched span verbatim.
_INJECTION_PATTERNS: Final[tuple[tuple[str, re.Pattern[str]], ...]] = (
    (
        "ignore-previous-instructions",
        re.compile(
            r"(?i)ignore\s+(?:all\s+)?previous\s+(?:instructions?|prompts?)"
        ),
    ),
    (
        "disregard-previous",
        re.compile(r"(?i)disregard\s+(?:the\s+)?(?:above|previous)"),
    ),
    ("system-prefix", re.compile(r"(?im)^\s*system\s*:")),
    ("system-tag", re.compile(r"(?i)</?\s*system\s*>")),
    ("instruction-separator", re.compile(r"(?m)^\s*###\s+")),
)


def sanitise_message(text: str) -> tuple[str, list[str]]:
    """Redact known injection patterns from ``text``.

    Each match is replaced with :data:`_REDACTION` (a single sentinel,
    not the original span). The second return value is the list of
    pattern labels that fired, in declaration order; callers typically
    log it at WARNING level so attempted injections are observable.

    An empty input is returned unchanged with an empty detection list —
    sanitisation is idempotent on clean text.
    """

    if not text:
        return text, []

    detected: list[str] = []
    sanitised = text
    for label, pattern in _INJECTION_PATTERNS:
        sanitised, count = pattern.subn(_REDACTION, sanitised)
        if count > 0:
            detected.append(label)
    return sanitised, detected


def wrap_untrusted(text: str) -> str:
    """Wrap free-form text in ``[user-content]`` tags.

    Used for trade comments and other free-form fields embedded in
    ``page_context`` that the model should treat strictly as data.
    The wrapper is purely structural — no pattern matching is applied —
    because the goal is to give the surrounding prompt a clear signal
    that everything between the tags is opaque user content. Pair with
    :func:`sanitise_message` when the wrapped content itself originates
    from a user (versus a system-generated payload).
    """

    return f"[user-content]{text}[/user-content]"

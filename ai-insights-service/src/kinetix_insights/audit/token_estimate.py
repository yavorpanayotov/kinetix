"""Coarse token estimation for the audit trail.

The audit record's ``tokens_estimated`` field is a *rough* indicator of
how much an AI call consumed — it drives capacity dashboards and rate
budgeting, not billing, so an exact tokeniser is unnecessary (and would
mean a new dependency). The well-worn ``~4 characters per token``
heuristic is good enough: it is deterministic, dependency-free, and
consistently proportional to real token counts for English prose.

:func:`estimate_tokens` sums the heuristic over the prompt plus the
generated text so a record reflects both the input the user sent and
the output the model produced.
"""

from __future__ import annotations

from collections.abc import Iterable

# Average characters per token for English text. The OpenAI / Anthropic
# rule of thumb; close enough for a capacity-planning estimate.
_CHARS_PER_TOKEN = 4


def estimate_tokens(*texts: str) -> int:
    """Estimate the token count of one or more text fragments.

    Returns the ceil-rounded ``total_chars / 4`` over every fragment.
    An empty input yields ``0``.
    """

    total_chars = sum(len(t) for t in texts if t)
    if total_chars == 0:
        return 0
    return -(-total_chars // _CHARS_PER_TOKEN)  # ceil division


def estimate_tokens_iter(texts: Iterable[str]) -> int:
    """Estimate the token count over an iterable of text fragments."""

    return estimate_tokens(*[t for t in texts if t])

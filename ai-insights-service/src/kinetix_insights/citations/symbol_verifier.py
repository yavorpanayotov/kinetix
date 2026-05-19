"""Detects ticker-like symbols in narratives that lack a backing citation.

LLMs occasionally invent counterparty names or tickers. The numeric
``find_uncited_tokens`` doesn't cover that — symbols look nothing
like ``$5.2M``. This module scans narratives for uppercase
ticker-shaped tokens (3–6 capitals, e.g. ``EURUSD``, ``MS``, ``GS``,
``AAPL``) and returns those that don't appear in any citation's
``params`` values.

Common stopwords (e.g. ``VAR``, ``USD``, ``EUR``, ``JPY``, ``CL``)
are excluded from the scan so currency-code mentions don't trip
false positives.

The matcher is intentionally conservative: it flags only tokens
that look unambiguous (all-caps, 3–6 chars). False negatives are
preferred over false positives in v2 — we'd rather miss an exotic
ticker than block legitimate copy.
"""

from __future__ import annotations

import re
from typing import Any

from kinetix_insights.citations.models import Citation

# Word-boundary anchored alternation matching 3-6 contiguous uppercase
# letters. Anything outside that range (e.g. two-letter pronouns like
# ``IT``, or seven-letter acronyms) is intentionally ignored — the goal
# is to flag obvious ticker-shaped tokens, not to police every acronym.
_TOKEN_PATTERN: re.Pattern[str] = re.compile(r"\b[A-Z]{3,6}\b")

# Currency codes and common short acronyms that look like tickers but
# are not (and would otherwise force every narrative mentioning USD or
# EUR to attach a citation for the currency itself). The list is small
# and deliberately curated; expand only when a real false-positive shows
# up in narrative output.
_STOPWORDS: frozenset[str] = frozenset(
    {
        "VAR",
        "USD",
        "EUR",
        "JPY",
        "GBP",
        "CHF",
        "AUD",
        "CAD",
        "NZD",
        "HKD",
        "SGD",
        "CL_95",
        "CL_99",
        "OK",
        "FX",
        "ETF",
        "OTC",
    }
)


def find_uncited_symbols(narrative: str, citations: list[Citation]) -> list[str]:
    """Return ticker-shaped tokens in ``narrative`` not backed by any citation.

    Tokens are extracted by :data:`_TOKEN_PATTERN` (uppercase 3-6 chars),
    filtered against :data:`_STOPWORDS`, and checked against the flattened
    set of citation ``params`` values. The match is a substring check —
    a citation with ``params={"symbol": "EURUSD"}`` covers a narrative
    mention of ``EURUSD``.

    Duplicates are collapsed in the returned list (first-seen order
    preserved). Order matches the narrative left-to-right.
    """

    if not narrative:
        return []

    haystack = _flatten_param_values(citations)
    seen: set[str] = set()
    ordered: list[str] = []
    for token in _TOKEN_PATTERN.findall(narrative):
        if token in _STOPWORDS or token in seen:
            continue
        if any(token in value for value in haystack):
            seen.add(token)
            continue
        seen.add(token)
        ordered.append(token)
    return ordered


def _flatten_param_values(citations: list[Citation]) -> list[str]:
    """Recursively pull every string value out of every citation's ``params``.

    Numeric and boolean values are stringified so substring matching
    works uniformly. Nested dicts and lists are walked depth-first.
    The list is materialised once per ``find_uncited_symbols`` call so
    the inner loop runs against a flat sequence rather than re-walking
    the citation tree for every token.
    """

    values: list[str] = []
    for citation in citations:
        _walk_into(citation.params, values)
    return values


def _walk_into(node: Any, into: list[str]) -> None:
    """Append every string-coerced leaf reachable from ``node`` into ``into``."""

    if isinstance(node, dict):
        for value in node.values():
            _walk_into(value, into)
        return
    if isinstance(node, (list, tuple, set)):
        for item in node:
            _walk_into(item, into)
        return
    if node is None:
        return
    if isinstance(node, str):
        into.append(node)
        return
    into.append(str(node))

"""Post-generation numeric-token verifier for AI narratives.

LLMs hallucinate numbers. Even when prompted to cite every figure,
they sometimes emit a confident `$5.2B` that no upstream tool ever
returned. `find_uncited_tokens` is the safety net: it scans a
narrative for numeric tokens and returns those whose value does not
match any supplied `Citation.result_value`.

The function is deliberately pure — no I/O, no global state. It is
called from the request/response pipeline after the model finishes
generating, and any returned tokens are surfaced to the UI as
`CITATION_UNVERIFIABLE` flags.

Matching rules (kept currency-agnostic so JPY, BHD, and percent-style
ratios all work without special-casing):

* Token regex: ``\\$?[\\d,]+(?:\\.\\d+)?%?`` — captures plain integers,
  decimals, thousands-separated values, leading dollar prefixes, and
  trailing percent suffixes.
* Stripping: ``$`` and ``%`` and ``,`` are removed before parsing the
  token to a float.
* Precision: comparisons round both sides to the **token's own**
  decimal precision. ``5.2`` matches anything in roughly
  ``[5.15, 5.25)``; ``5.20`` is stricter and only matches at 2dp;
  ``5`` matches at integer precision. This handles JPY (0 decimals)
  and BHD (3 decimals) cleanly.
* String citations: a `result_value` like ``"5.2M USD"`` is scanned
  with the same regex and its first numeric prefix is parsed; if no
  prefix is present, the citation cannot satisfy any token.
"""

import re

from kinetix_insights.citations.models import Citation

# Greedy enough to cover `$5,000.50%`; restrictive enough to avoid
# eating surrounding punctuation. We require at least one digit via
# the lookahead so the plan's literal pattern doesn't degenerate into
# matching lone commas in prose like ``$5B, comfortably above``.
_TOKEN_PATTERN = re.compile(r"\$?(?=[\d,]*\d)[\d,]+(?:\.\d+)?%?")


def find_uncited_tokens(narrative: str, citations: list[Citation]) -> list[str]:
    """Return numeric tokens in ``narrative`` not matched by any citation.

    Tokens appear in source order and duplicates are preserved — the
    same hallucinated figure repeated twice surfaces twice so callers
    can highlight each occurrence.
    """
    citation_values = _extract_citation_values(citations)
    uncited: list[str] = []
    for token in _TOKEN_PATTERN.findall(narrative):
        parsed = _parse_token(token)
        if parsed is None:
            # Token shape allowed by regex but unparseable (e.g. lone
            # ``,``-only sequence) — treat as uncited so callers see it.
            uncited.append(token)
            continue
        value, decimals = parsed
        if not _matches_any_citation(value, decimals, citation_values):
            uncited.append(token)
    return uncited


def _extract_citation_values(citations: list[Citation]) -> list[float]:
    """Pull a numeric value out of each citation, skipping unparseable ones."""
    values: list[float] = []
    for citation in citations:
        numeric = _coerce_citation_value(citation.result_value)
        if numeric is not None:
            values.append(numeric)
    return values


def _coerce_citation_value(raw: float | int | str) -> float | None:
    """Convert a citation's `result_value` to a float, or None if impossible."""
    if isinstance(raw, (int, float)):
        return float(raw)
    # String form: extract the first numeric prefix the regex finds.
    match = _TOKEN_PATTERN.search(raw)
    if match is None:
        return None
    parsed = _parse_token(match.group(0))
    return None if parsed is None else parsed[0]


def _parse_token(token: str) -> tuple[float, int] | None:
    """Return ``(value, decimal_precision)`` or None if the token won't parse.

    Precision is the number of digits after the decimal point as
    written in the narrative; for ``5`` that is 0, for ``5.20`` that
    is 2. The caller rounds both sides to this precision before
    comparing — that is what gives JPY and BHD their natural match
    behaviour without hardcoding currency tables.
    """
    stripped = token.lstrip("$").rstrip("%").replace(",", "")
    if not stripped:
        return None
    try:
        value = float(stripped)
    except ValueError:
        return None
    decimals = len(stripped.split(".", 1)[1]) if "." in stripped else 0
    return value, decimals


def _matches_any_citation(
    token_value: float, decimals: int, citation_values: list[float]
) -> bool:
    """True if any citation lies within half-a-unit of the token at its precision.

    Using a half-open ``[token - 0.5*unit, token + 0.5*unit)`` window
    instead of Python's built-in ``round`` because the latter uses
    banker's rounding — ``round(5.25, 1) == 5.2`` would let ``5.25``
    silently masquerade as a citation for the narrative token ``5.2``.
    The window is keyed off the token's own decimal precision so JPY
    (0dp) is strict-to-integer and BHD (3dp) is strict-to-mille.
    """
    half_unit = 0.5 * (10 ** (-decimals))
    epsilon = 1e-9
    lower = token_value - half_unit
    upper = token_value + half_unit
    for citation_value in citation_values:
        if lower - epsilon <= citation_value < upper - epsilon:
            return True
    return False

"""Unit tests for the citation_verifier numeric-token check.

`find_uncited_tokens` is the safety net that catches hallucinated
numbers: it tokenises an LLM narrative with a deliberately greedy
numeric regex and returns every token whose value does not appear
in the supplied `Citation` list. These tests pin down the matching
contract — currency strip, percent strip, thousands separator,
JPY (no decimals) / BHD (three decimals) precision handling, and
duplicate preservation — that downstream guardrail code depends on.
"""

from datetime import datetime, timezone

import pytest

from kinetix_insights.citations.models import Citation
from kinetix_insights.citations.verifier import find_uncited_tokens

pytestmark = pytest.mark.unit


def _citation(value: float | int | str, *, field: str = "v") -> Citation:
    """Build a Citation carrying `value` — defaults are irrelevant here."""
    return Citation(
        tool="get_book_var",
        params={"book_id": "MAIN"},
        result_field=field,
        result_value=value,
        result_currency="USD",
        as_of_timestamp=datetime(2026, 5, 19, 12, 0, 0, tzinfo=timezone.utc),
        data_source="risk-orchestrator",
        freshness_seconds=0,
    )


def test_empty_narrative_returns_empty_list() -> None:
    """A narrative containing no characters has no tokens to verify."""
    assert find_uncited_tokens("", [_citation(5.2)]) == []


def test_narrative_with_no_numerics_returns_empty_list() -> None:
    """Prose without digits produces no tokens regardless of citations."""
    assert find_uncited_tokens("The book is well within limits.", []) == []


def test_no_citations_returns_every_numeric_token() -> None:
    """Without citations, every numeric token is flagged as uncited."""
    narrative = "VaR is $5.2M and exposure is 1,000 across 3 books."
    assert find_uncited_tokens(narrative, []) == ["$5.2", "1,000", "3"]


def test_thousands_separator_matches_plain_integer() -> None:
    """`1,000` in narrative is cited by `1000.0` in a citation."""
    narrative = "Notional is 1,000."
    assert find_uncited_tokens(narrative, [_citation(1000.0)]) == []


def test_dollar_prefix_and_thousands_separator_match_plain_float() -> None:
    """`$1,234.56` is cited by `1234.56` once `$` and `,` are stripped."""
    narrative = "Loss is $1,234.56 today."
    assert find_uncited_tokens(narrative, [_citation(1234.56)]) == []


def test_percent_suffix_matches_plain_float() -> None:
    """`5.0%` is cited by `5.0` once the trailing `%` is stripped."""
    narrative = "Confidence sits at 5.0%."
    assert find_uncited_tokens(narrative, [_citation(5.0)]) == []


def test_jpy_integer_token_matches_float_citation() -> None:
    """JPY values lack decimals; `1234` is cited by `1234.0`."""
    narrative = "JPY exposure is 1234."
    assert find_uncited_tokens(narrative, [_citation(1234.0)]) == []


def test_bhd_three_decimal_token_matches_three_decimal_citation() -> None:
    """BHD uses three decimals; `1.234` is cited by `1.234`."""
    narrative = "BHD position is 1.234."
    assert find_uncited_tokens(narrative, [_citation(1.234)]) == []


def test_token_not_matched_when_no_citation_within_precision() -> None:
    """`5.2` is uncited when only `5.3` is available."""
    narrative = "Vol is 5.2 today."
    assert find_uncited_tokens(narrative, [_citation(5.3)]) == ["5.2"]


def test_duplicate_uncited_tokens_preserved_in_order() -> None:
    """Two `5.2` tokens both uncited surface twice in the result."""
    narrative = "value is 5.2 and 5.2"
    assert find_uncited_tokens(narrative, []) == ["5.2", "5.2"]


def test_zero_token_matches_zero_citation() -> None:
    """`0` cited by a `0` value — no special-casing required."""
    narrative = "Delta is 0 and gamma is 0.0 and theta is 0%."
    assert find_uncited_tokens(narrative, [_citation(0)]) == []


def test_one_decimal_token_matches_two_decimal_citation_at_one_decimal() -> None:
    """`5.2` (1dp) matches `5.20` (2dp) when rounded to the token's precision."""
    narrative = "Vol is 5.2."
    assert find_uncited_tokens(narrative, [_citation(5.20)]) == []


def test_one_decimal_token_does_not_match_off_by_one_in_second_decimal() -> None:
    """`5.2` (1dp) does NOT match `5.25` — rounds to 5.3 not 5.2."""
    narrative = "Vol is 5.2."
    assert find_uncited_tokens(narrative, [_citation(5.25)]) == ["5.2"]


def test_two_decimal_token_does_not_match_off_by_hundredth() -> None:
    """`5.20` (2dp) does NOT match `5.21` — distinct at 2dp precision."""
    narrative = "Vol is 5.20."
    assert find_uncited_tokens(narrative, [_citation(5.21)]) == ["5.20"]


def test_string_result_value_with_numeric_prefix_can_cite() -> None:
    """String like `"5.2M USD"` parses its `5.2` prefix to cite `5.2`."""
    narrative = "Loss is 5.2 million."
    assert find_uncited_tokens(narrative, [_citation("5.2M USD")]) == []


def test_string_result_value_without_numeric_prefix_cites_nothing() -> None:
    """A non-numeric string like `"unknown"` cannot satisfy any token."""
    narrative = "Loss is 5.2 million."
    assert find_uncited_tokens(narrative, [_citation("unknown")]) == ["5.2"]


def test_integer_token_matches_float_citation_at_integer_precision() -> None:
    """`100` (integer) is cited by `100.0` — both round to 100."""
    narrative = "Positions: 100."
    assert find_uncited_tokens(narrative, [_citation(100.0)]) == []


def test_happy_path_multiple_cited_tokens_one_uncited() -> None:
    """Narrative with three numbers, two cited, one uncited token surfaces verbatim."""
    narrative = "VaR is $5.2M with 1,000 trades at 3.5%."
    citations = [
        _citation(5.2, field="var_millions"),
        _citation(1000, field="trade_count"),
    ]
    # Token is returned verbatim with its `%` suffix so callers can
    # highlight the exact substring the model emitted.
    assert find_uncited_tokens(narrative, citations) == ["3.5%"]


def test_adversarial_hallucination_only_hallucinated_token_returned() -> None:
    """Narrative claims $5B when only $4B is cited — `$5` is flagged."""
    narrative = "Total exposure is $5B, comfortably above the $4B baseline."
    citations = [_citation(4, field="baseline_billions")]
    assert find_uncited_tokens(narrative, citations) == ["$5"]

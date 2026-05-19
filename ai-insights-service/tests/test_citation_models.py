"""Unit tests for the Citation pydantic model.

Every numeric token an insight emits must carry a machine-readable
`Citation` describing exactly where the number came from, when the
underlying data was captured, and any data-quality caveats. These
tests pin down the JSON contract and immutability guarantees that
downstream consumers (UI footnotes, the `citation_verifier`, audit
logs) rely on.
"""

from datetime import datetime, timezone

import pytest
from pydantic import ValidationError

from kinetix_insights.citations.models import Citation

pytestmark = pytest.mark.unit


def _make_citation(**overrides: object) -> Citation:
    """Build a Citation with sensible defaults; tests override per case."""
    base: dict[str, object] = {
        "tool": "get_book_var",
        "params": {"book_id": "MAIN", "horizon_days": 1},
        "result_field": "total_var",
        "result_value": 5_200_000.0,
        "result_currency": "USD",
        "as_of_timestamp": datetime(2026, 5, 19, 12, 0, 0, tzinfo=timezone.utc),
        "data_source": "risk-orchestrator",
        "freshness_seconds": 42,
    }
    base.update(overrides)
    return Citation(**base)  # type: ignore[arg-type]


def test_citation_constructs_with_all_required_fields() -> None:
    """A Citation populated with every field exposes the same values back."""
    as_of = datetime(2026, 5, 19, 12, 0, 0, tzinfo=timezone.utc)
    citation = Citation(
        tool="get_book_var",
        params={"book_id": "MAIN", "horizon_days": 1},
        result_field="total_var",
        result_value=5_200_000.0,
        result_currency="USD",
        as_of_timestamp=as_of,
        data_source="risk-orchestrator",
        freshness_seconds=42,
        quality_flags=["STALE_PRICE"],
    )
    assert citation.tool == "get_book_var"
    assert citation.params == {"book_id": "MAIN", "horizon_days": 1}
    assert citation.result_field == "total_var"
    assert citation.result_value == 5_200_000.0
    assert citation.result_currency == "USD"
    assert citation.as_of_timestamp == as_of
    assert citation.data_source == "risk-orchestrator"
    assert citation.freshness_seconds == 42
    assert citation.quality_flags == ["STALE_PRICE"]


def test_citation_json_round_trip_is_identity() -> None:
    """model_dump_json -> model_validate_json yields an equal Citation."""
    original = _make_citation(quality_flags=["EXTRAPOLATED", "INCOMPLETE_BOOK"])
    parsed = Citation.model_validate_json(original.model_dump_json())
    assert parsed == original


def test_citation_json_dump_serialises_timestamp_as_iso8601_string() -> None:
    """JSON output uses ISO 8601 for datetimes and preserves field types."""
    citation = _make_citation()
    payload = citation.model_dump(mode="json")
    assert payload["tool"] == "get_book_var"
    assert payload["params"] == {"book_id": "MAIN", "horizon_days": 1}
    assert payload["result_field"] == "total_var"
    assert payload["result_value"] == 5_200_000.0
    assert payload["result_currency"] == "USD"
    assert isinstance(payload["as_of_timestamp"], str)
    assert payload["as_of_timestamp"].startswith("2026-05-19T12:00:00")
    assert payload["data_source"] == "risk-orchestrator"
    assert payload["freshness_seconds"] == 42
    assert payload["quality_flags"] == []


def test_citation_quality_flags_defaults_to_empty_list() -> None:
    """Omitting `quality_flags` yields an empty list, not None."""
    citation = _make_citation()
    assert citation.quality_flags == []


def test_citation_result_currency_accepts_none() -> None:
    """Dimensionless values (ratios, percentages) carry no currency."""
    citation = _make_citation(
        result_field="sharpe_ratio",
        result_value=1.42,
        result_currency=None,
    )
    assert citation.result_currency is None


def test_citation_result_value_accepts_string_for_pre_formatted_numbers() -> None:
    """Pre-formatted values like "5.2M USD" are tolerated as strings."""
    citation = _make_citation(result_value="5.2M USD")
    assert citation.result_value == "5.2M USD"


def test_citation_result_value_accepts_int() -> None:
    """Integer values (counts, no fractional part) are accepted as-is."""
    citation = _make_citation(
        result_field="position_count",
        result_value=137,
        result_currency=None,
    )
    assert citation.result_value == 137


def test_citation_is_frozen_assignment_raises() -> None:
    """Citations are immutable; field assignment after construction fails."""
    citation = _make_citation()
    with pytest.raises(ValidationError):
        citation.result_value = 6_000_000.0  # type: ignore[misc]


def test_citation_equality_holds_for_identical_fields() -> None:
    """Two Citations with identical fields compare equal."""
    a = _make_citation()
    b = _make_citation()
    assert a == b


def test_citation_equality_breaks_when_any_field_differs() -> None:
    """Citations are not equal when even one field differs."""
    a = _make_citation()
    b = _make_citation(result_value=5_300_000.0)
    assert a != b


def test_citation_requires_tool() -> None:
    """Omitting `tool` raises ValidationError."""
    with pytest.raises(ValidationError):
        Citation(  # type: ignore[call-arg]
            params={},
            result_field="total_var",
            result_value=1.0,
            as_of_timestamp=datetime(2026, 5, 19, tzinfo=timezone.utc),
            data_source="risk-orchestrator",
            freshness_seconds=0,
        )


def test_citation_requires_params() -> None:
    """Omitting `params` raises ValidationError."""
    with pytest.raises(ValidationError):
        Citation(  # type: ignore[call-arg]
            tool="get_book_var",
            result_field="total_var",
            result_value=1.0,
            as_of_timestamp=datetime(2026, 5, 19, tzinfo=timezone.utc),
            data_source="risk-orchestrator",
            freshness_seconds=0,
        )


def test_citation_requires_result_field() -> None:
    """Omitting `result_field` raises ValidationError."""
    with pytest.raises(ValidationError):
        Citation(  # type: ignore[call-arg]
            tool="get_book_var",
            params={},
            result_value=1.0,
            as_of_timestamp=datetime(2026, 5, 19, tzinfo=timezone.utc),
            data_source="risk-orchestrator",
            freshness_seconds=0,
        )


def test_citation_requires_result_value() -> None:
    """Omitting `result_value` raises ValidationError."""
    with pytest.raises(ValidationError):
        Citation(  # type: ignore[call-arg]
            tool="get_book_var",
            params={},
            result_field="total_var",
            as_of_timestamp=datetime(2026, 5, 19, tzinfo=timezone.utc),
            data_source="risk-orchestrator",
            freshness_seconds=0,
        )


def test_citation_requires_as_of_timestamp() -> None:
    """Omitting `as_of_timestamp` raises ValidationError."""
    with pytest.raises(ValidationError):
        Citation(  # type: ignore[call-arg]
            tool="get_book_var",
            params={},
            result_field="total_var",
            result_value=1.0,
            data_source="risk-orchestrator",
            freshness_seconds=0,
        )


def test_citation_requires_data_source() -> None:
    """Omitting `data_source` raises ValidationError."""
    with pytest.raises(ValidationError):
        Citation(  # type: ignore[call-arg]
            tool="get_book_var",
            params={},
            result_field="total_var",
            result_value=1.0,
            as_of_timestamp=datetime(2026, 5, 19, tzinfo=timezone.utc),
            freshness_seconds=0,
        )


def test_citation_requires_freshness_seconds() -> None:
    """Omitting `freshness_seconds` raises ValidationError."""
    with pytest.raises(ValidationError):
        Citation(  # type: ignore[call-arg]
            tool="get_book_var",
            params={},
            result_field="total_var",
            result_value=1.0,
            as_of_timestamp=datetime(2026, 5, 19, tzinfo=timezone.utc),
            data_source="risk-orchestrator",
        )

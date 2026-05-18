"""Unit tests for the InsightRequest / InsightResponse pydantic models.

These tests pin down the JSON contract that every insight endpoint
must honour: the shapes round-trip through `model_dump_json` /
`model_validate_json`, unknown discriminator values are rejected, and
required fields are enforced.
"""

import pytest
from pydantic import ValidationError

from kinetix_insights.models import InsightRequest, InsightResponse

pytestmark = pytest.mark.unit


def test_insight_request_round_trips() -> None:
    """An InsightRequest survives a JSON dump/parse cycle unchanged."""
    original = InsightRequest(kind="var", payload={"x": 1, "y": "a"})
    parsed = InsightRequest.model_validate_json(original.model_dump_json())
    assert parsed == original


def test_insight_request_accepts_report_kind() -> None:
    """The `report` discriminator is also valid alongside `var`."""
    original = InsightRequest(kind="report", payload={"period": "Q1"})
    parsed = InsightRequest.model_validate_json(original.model_dump_json())
    assert parsed == original
    assert parsed.kind == "report"


def test_insight_request_defaults_payload_to_empty_dict() -> None:
    """Omitting `payload` yields an empty dict, not None."""
    request = InsightRequest(kind="var")
    assert request.payload == {}


def test_insight_request_rejects_unknown_kind() -> None:
    """An unknown `kind` value raises pydantic ValidationError."""
    with pytest.raises(ValidationError):
        InsightRequest(kind="foo", payload={})  # type: ignore[arg-type]


def test_insight_response_round_trips() -> None:
    """An InsightResponse survives a JSON dump/parse cycle unchanged."""
    original = InsightResponse(
        narrative="VaR rose 12% week-on-week driven by equity vol.",
        bullets=["a", "b"],
        model="claude-sonnet-4-6",
        mode="canned",
    )
    parsed = InsightResponse.model_validate_json(original.model_dump_json())
    assert parsed == original


def test_insight_response_defaults_bullets_to_empty_list() -> None:
    """Omitting `bullets` yields an empty list, not None."""
    response = InsightResponse(
        narrative="Quiet day.",
        model="claude-sonnet-4-6",
        mode="live",
    )
    assert response.bullets == []


def test_insight_response_rejects_unknown_mode() -> None:
    """An unknown `mode` value raises pydantic ValidationError."""
    with pytest.raises(ValidationError):
        InsightResponse(
            narrative="x",
            bullets=[],
            model="claude-sonnet-4-6",
            mode="other",  # type: ignore[arg-type]
        )


def test_insight_response_requires_narrative() -> None:
    """Omitting `narrative` raises pydantic ValidationError."""
    with pytest.raises(ValidationError):
        InsightResponse(  # type: ignore[call-arg]
            bullets=[],
            model="claude-sonnet-4-6",
            mode="live",
        )


def test_insight_response_requires_model() -> None:
    """Omitting `model` raises pydantic ValidationError."""
    with pytest.raises(ValidationError):
        InsightResponse(  # type: ignore[call-arg]
            narrative="x",
            bullets=[],
            mode="live",
        )

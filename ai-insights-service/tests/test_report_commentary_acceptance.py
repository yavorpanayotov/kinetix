"""Acceptance tests for the report commentary route.

These tests exercise the in-process FastAPI app over a real HTTP path
(via :class:`fastapi.testclient.TestClient`) so the route, the lifespan
that wires ``app.state.insight_client``, and the canned client are all
exercised end-to-end. ``DEMO_MODE=true`` (set by the acceptance command)
forces the factory to return :class:`CannedInsightClient`, which lets us
assert deterministic shape and content without any external dependency.
"""

from __future__ import annotations

import os

import pytest
from fastapi.testclient import TestClient

pytestmark = pytest.mark.unit


_PAYLOAD = {
    "template_id": "monthly_risk_v1",
    "report_date": "2026-04-30",
    "summary_metrics": {"total_var": 1_250_000.0, "max_drawdown": 0.085},
    "top_drivers": [
        {"name": "Tech sector concentration", "contribution_usd": 850_000.0},
        {"name": "GBP exposure", "contribution_usd": 220_000.0},
    ],
    "breaches": ["VaR limit exceeded", "Concentration limit warning"],
}


def _build_client() -> TestClient:
    """Construct a TestClient with DEMO_MODE forced on.

    Setting the env var before importing the app ensures the lifespan
    constructs a :class:`CannedInsightClient`. The import lives inside
    the helper so the env is set before module import.
    """
    os.environ.setdefault("DEMO_MODE", "true")
    os.environ["DEMO_MODE"] = "true"
    from kinetix_insights.app import app  # imported after env is set

    return TestClient(app)


def test_report_commentary_returns_canned_response_in_demo_mode() -> None:
    """POST /api/v1/insights/explain/report returns a canned InsightResponse."""
    with _build_client() as client:
        response = client.post("/api/v1/insights/explain/report", json=_PAYLOAD)

    assert response.status_code == 200
    body = response.json()
    assert set(body.keys()) >= {"narrative", "bullets", "model", "mode"}
    assert body["mode"] == "canned"
    assert isinstance(body["narrative"], str) and body["narrative"]
    assert isinstance(body["bullets"], list) and body["bullets"]

    bullets = body["bullets"]
    joined = " ".join(bullets)
    driver_names = [driver["name"] for driver in _PAYLOAD["top_drivers"]]
    breaches = _PAYLOAD["breaches"]
    mentioned = any(name in joined for name in driver_names) or any(
        breach in joined for breach in breaches
    )
    assert mentioned, (
        f"Expected at least one driver name or breach in bullets, got {bullets!r}"
    )


def test_report_commentary_with_no_drivers_or_breaches_still_returns_valid_shape() -> None:
    """Empty drivers and breaches still yield a valid InsightResponse shape."""
    payload = {
        "template_id": "monthly_risk_v1",
        "report_date": "2026-04-30",
        "summary_metrics": {"total_var": 1_250_000.0},
        "top_drivers": [],
        "breaches": [],
    }
    with _build_client() as client:
        response = client.post("/api/v1/insights/explain/report", json=payload)

    assert response.status_code == 200
    body = response.json()
    assert set(body.keys()) >= {"narrative", "bullets", "model", "mode"}
    assert body["mode"] == "canned"
    assert isinstance(body["narrative"], str) and body["narrative"]
    assert isinstance(body["bullets"], list) and body["bullets"]


def test_report_commentary_rejects_invalid_payload() -> None:
    """Missing required fields produce a 422 validation error."""
    with _build_client() as client:
        response = client.post(
            "/api/v1/insights/explain/report",
            json={"report_date": "2026-04-30"},
        )
    assert response.status_code == 422

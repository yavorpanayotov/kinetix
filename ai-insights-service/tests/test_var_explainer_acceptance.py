"""Acceptance tests for the VaR explainer route.

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
    "method": "historical",
    "confidence": 0.99,
    "horizon_days": 1,
    "value_usd": 2_500_000.0,
    "top_contributors": [
        {"instrument": "AAPL", "contribution_pct": 0.32},
        {"instrument": "MSFT", "contribution_pct": 0.18},
    ],
    "regime": "high_vol",
}


def _build_client() -> TestClient:
    """Construct a TestClient with DEMO_MODE forced on.

    Setting the env var before importing the app ensures the lifespan
    constructs a :class:`CannedInsightClient`. The import lives inside
    the helper so the env is set before module import.
    """
    os.environ["DEMO_MODE"] = "true"
    from kinetix_insights.app import app  # imported after env is set

    return TestClient(app)


def test_var_explainer_returns_canned_response_in_demo_mode() -> None:
    """POST /api/v1/insights/explain/var returns a canned InsightResponse."""
    with _build_client() as client:
        response = client.post("/api/v1/insights/explain/var", json=_PAYLOAD)

    assert response.status_code == 200
    body = response.json()
    assert set(body.keys()) >= {"narrative", "bullets", "model", "mode"}
    assert body["mode"] == "canned"
    assert isinstance(body["narrative"], str) and body["narrative"]
    assert isinstance(body["bullets"], list) and body["bullets"]


def test_var_explainer_bullets_cover_top_contributors() -> None:
    """Canned bullets must reference each top contributor instrument."""
    with _build_client() as client:
        response = client.post("/api/v1/insights/explain/var", json=_PAYLOAD)

    assert response.status_code == 200
    bullets = response.json()["bullets"]
    joined = " ".join(bullets)
    for contributor in _PAYLOAD["top_contributors"]:
        assert contributor["instrument"] in joined, (
            f"Expected {contributor['instrument']!r} in bullets, got {bullets!r}"
        )


def test_var_explainer_rejects_invalid_payload() -> None:
    """Missing required fields produce a 422 validation error."""
    with _build_client() as client:
        response = client.post("/api/v1/insights/explain/var", json={"method": "historical"})
    assert response.status_code == 422

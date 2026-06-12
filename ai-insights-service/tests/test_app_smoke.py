"""Smoke tests for the FastAPI scaffold.

These tests are intentionally trivial — they anchor the `unit` pytest
marker so the acceptance command (`uv run pytest -m unit`) collects at
least one test, and they prove that the application imports and that
the probe endpoints are wired correctly.
"""

import pytest
from fastapi.testclient import TestClient

from kinetix_insights.app import app

pytestmark = pytest.mark.unit


def test_app_imports() -> None:
    """The FastAPI app object must be importable from kinetix_insights.app."""
    assert app is not None
    assert app.title == "Kinetix Insights"


def test_health_endpoint_returns_ok() -> None:
    """/health returns 200 with a JSON body of {'status': 'ok'}."""
    client = TestClient(app)
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_ready_endpoint_returns_ready() -> None:
    """/ready returns 200 with a JSON body of {'status': 'ready'}."""
    client = TestClient(app)
    response = client.get("/ready")
    assert response.status_code == 200
    assert response.json() == {"status": "ready"}


def test_health_ready_alias_returns_ready() -> None:
    """/health/ready returns 200 — the gateway's system-health aggregator
    probes every monitored service at this path."""
    client = TestClient(app)
    response = client.get("/health/ready")
    assert response.status_code == 200
    assert response.json() == {"status": "ready"}

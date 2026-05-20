"""Tests for the Prometheus ``/metrics`` endpoint.

The endpoint lets Prometheus scrape the AI Insights service (see the
``ai-insights-service`` job in ``deploy/observability/prometheus.yml``).
It exposes the default ``prometheus_client`` registry in the standard
text exposition format.
"""

import pytest
from fastapi.testclient import TestClient
from prometheus_client import CONTENT_TYPE_LATEST

from kinetix_insights.app import app

pytestmark = pytest.mark.unit


def test_metrics_endpoint_returns_200() -> None:
    """/metrics returns 200 so Prometheus can scrape the service."""
    client = TestClient(app)
    response = client.get("/metrics")
    assert response.status_code == 200


def test_metrics_endpoint_uses_prometheus_content_type() -> None:
    """/metrics responds with the Prometheus text exposition content type."""
    client = TestClient(app)
    response = client.get("/metrics")
    assert response.headers["content-type"].startswith(
        CONTENT_TYPE_LATEST.split(";")[0]
    )


def test_metrics_endpoint_emits_prometheus_text_format() -> None:
    """/metrics body is recognisable Prometheus exposition output."""
    client = TestClient(app)
    response = client.get("/metrics")
    body = response.text
    # Default registry always exposes python_info plus standard
    # ``# HELP`` / ``# TYPE`` metadata lines.
    assert "# HELP" in body
    assert "# TYPE" in body
    assert "python_info" in body

"""Prometheus ``/metrics`` route for the AI Insights service.

Exposes the default ``prometheus_client`` registry in the standard text
exposition format so Prometheus can scrape the service. The
corresponding scrape job (``ai-insights-service``) lives in
``deploy/observability/prometheus.yml``.

The route is mounted on the main FastAPI app alongside the ``/health``
and ``/ready`` probes, mirroring the convention used by the other
Kinetix services which all serve metrics on ``/metrics``.
"""

from fastapi import APIRouter, Response
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest

router = APIRouter(tags=["observability"])


@router.get("/metrics")
def metrics() -> Response:
    """Expose Prometheus metrics in the text exposition format."""
    return Response(
        content=generate_latest(),
        media_type=CONTENT_TYPE_LATEST,
    )

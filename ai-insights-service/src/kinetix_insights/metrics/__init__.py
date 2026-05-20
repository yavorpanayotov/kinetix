"""Prometheus instrumentation for the Kinetix Copilot.

The :mod:`kinetix_insights.metrics.copilot_metrics` module defines the
``copilot_*`` metric namespace and registers it on the default
``prometheus_client`` registry, so the existing ``/metrics`` route
(:mod:`kinetix_insights.routes.metrics`) exposes it without a second
endpoint.
"""

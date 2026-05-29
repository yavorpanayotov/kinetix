"""OpenTelemetry SDK initialisation for the risk-engine gRPC server.

Call ``init_otel()`` once at server startup. If ``OTEL_EXPORTER_OTLP_ENDPOINT``
is not set, the call is a no-op — no tracer provider is installed and no spans
are exported. The service name defaults to ``risk-engine`` and can be overridden
with ``OTEL_SERVICE_NAME``.

Exporters use ``BatchSpanProcessor`` so the hot path (Valuate, VaR) is never
blocked by OTLP I/O. The gRPC auto-instrumentation (``server_interceptor``) is
applied separately via ``build_grpc_server_interceptors()`` in ``server.py``; this
module handles SDK wiring only.

Calling ``get_tracer()`` returns the module-level tracer for use in hotpath spans.
When OTel is not initialised it returns the no-op tracer that ships with the API.
"""

from __future__ import annotations

import logging
import os

from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor

logger = logging.getLogger(__name__)

_SERVICE_NAME = "risk-engine"
_TRACER_NAME = "kinetix_risk"


def init_otel() -> None:
    """Initialise the OTel SDK if ``OTEL_EXPORTER_OTLP_ENDPOINT`` is configured.

    Safe to call multiple times — subsequent calls are no-ops if an SDK provider
    is already active, because OpenTelemetry forbids replacing a set provider.
    """
    endpoint = os.environ.get("OTEL_EXPORTER_OTLP_ENDPOINT")
    if not endpoint:
        logger.debug("OTEL_EXPORTER_OTLP_ENDPOINT not set — OTel tracing disabled")
        return

    # Guard: don't override if a real SDK provider was already installed (e.g. in
    # tests that manage their own provider).
    existing = trace.get_tracer_provider()
    if isinstance(existing, TracerProvider):
        logger.debug("OTel TracerProvider already installed — skipping init_otel")
        return

    try:
        from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
        from opentelemetry.sdk.resources import Resource

        service_name = os.environ.get("OTEL_SERVICE_NAME", _SERVICE_NAME)
        resource = Resource.create({"service.name": service_name})

        provider = TracerProvider(resource=resource)
        provider.add_span_processor(
            BatchSpanProcessor(
                OTLPSpanExporter(endpoint=endpoint, insecure=True)
            )
        )
        trace.set_tracer_provider(provider)
        logger.info(
            "OTel tracing configured: service=%s endpoint=%s",
            service_name,
            endpoint,
        )
    except Exception:
        logger.warning("Failed to initialise OTel tracing — continuing without it", exc_info=True)


def get_tracer() -> trace.Tracer:
    """Return the tracer for kinetix_risk spans.

    When OTel is not initialised this returns the no-op tracer, so callers
    never need to guard against None.
    """
    return trace.get_tracer(_TRACER_NAME)

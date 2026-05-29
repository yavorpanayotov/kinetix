"""Verifies that the risk-engine emits OpenTelemetry spans when handling RPCs.

The Valuate RPC is the canonical hotpath (ADR-0024). This module asserts:
- At least one span is emitted when Valuate is called.
- The span carries a ``book_id`` attribute.
- The span name identifies the operation (contains "Valuate").

Uses InMemorySpanExporter so no OTLP collector is required.
"""

from concurrent import futures

import grpc
import pytest
from opentelemetry import trace
from opentelemetry.instrumentation.grpc import server_interceptor
from opentelemetry.propagate import set_global_textmap
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator

from kinetix.risk import risk_calculation_pb2, risk_calculation_pb2_grpc
from kinetix_risk.server import RiskCalculationServicer

pytestmark = pytest.mark.unit


@pytest.fixture(scope="module")
def _span_exporter():
    """Install an SDK TracerProvider with an in-memory exporter.

    Module-scoped because OpenTelemetry prevents overriding a TracerProvider
    once set. If another test already installed a real SDK provider, the
    exporter is attached to that one instead.
    """
    set_global_textmap(TraceContextTextMapPropagator())
    exporter = InMemorySpanExporter()
    existing = trace.get_tracer_provider()
    if isinstance(existing, TracerProvider):
        existing.add_span_processor(SimpleSpanProcessor(exporter))
    else:
        provider = TracerProvider()
        provider.add_span_processor(SimpleSpanProcessor(exporter))
        trace.set_tracer_provider(provider)
    return exporter


@pytest.fixture
def span_exporter(_span_exporter):
    """Per-test: clear spans before and after each test for isolation."""
    _span_exporter.clear()
    yield _span_exporter
    _span_exporter.clear()


@pytest.fixture(scope="module")
def valuate_server(_span_exporter):
    """In-process gRPC server with RiskCalculationServicer and OTel interceptor."""
    servicer = RiskCalculationServicer()
    server = grpc.server(
        futures.ThreadPoolExecutor(max_workers=2),
        interceptors=[server_interceptor()],
    )
    risk_calculation_pb2_grpc.add_RiskCalculationServiceServicer_to_server(servicer, server)
    port = server.add_insecure_port("[::]:0")
    server.start()
    channel = grpc.insecure_channel(f"localhost:{port}")
    stub = risk_calculation_pb2_grpc.RiskCalculationServiceStub(channel)

    yield stub

    channel.close()
    server.stop(grace=None)


def _minimal_valuate_request(book_id: str = "TEST-BOOK") -> risk_calculation_pb2.ValuationRequest:
    from kinetix.common import types_pb2

    return risk_calculation_pb2.ValuationRequest(
        book_id=types_pb2.BookId(value=book_id),
        calculation_type=risk_calculation_pb2.MONTE_CARLO,
        confidence_level=risk_calculation_pb2.CL_95,
        time_horizon_days=1,
        num_simulations=100,
    )


class TestValuateEmitsSpan:
    def test_valuate_emits_at_least_one_span(self, valuate_server, span_exporter):
        """A Valuate RPC must produce at least one recorded OTel span."""
        valuate_server.Valuate(_minimal_valuate_request())

        spans = span_exporter.get_finished_spans()
        assert len(spans) >= 1, (
            f"Expected at least one span after Valuate, got {len(spans)}"
        )

    def test_valuate_span_name_identifies_the_operation(self, valuate_server, span_exporter):
        """The server span name must identify the Valuate operation so traces
        are navigable in Tempo without reading span attributes."""
        valuate_server.Valuate(_minimal_valuate_request())

        spans = span_exporter.get_finished_spans()
        span_names = [s.name for s in spans]
        assert any("Valuate" in name for name in span_names), (
            f"No span named 'Valuate*' found. Recorded spans: {span_names}"
        )

    def test_valuate_span_carries_book_id_attribute(self, valuate_server, span_exporter):
        """The Valuate span must carry book_id so traces can be filtered by book
        in Grafana/Tempo (ADR-0008 observability requirement)."""
        book_id = "BOOK-OTEL-TEST"
        valuate_server.Valuate(_minimal_valuate_request(book_id=book_id))

        spans = span_exporter.get_finished_spans()
        valuate_spans = [s for s in spans if "Valuate" in s.name]
        assert valuate_spans, f"No Valuate span found. All spans: {[s.name for s in spans]}"

        attrs = valuate_spans[0].attributes or {}
        assert "book_id" in attrs, (
            f"book_id attribute missing from Valuate span. Attributes: {dict(attrs)}"
        )
        assert attrs["book_id"] == book_id

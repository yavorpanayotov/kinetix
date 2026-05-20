"""Proves an inbound W3C ``traceparent`` is read into the active span context
on the risk-engine (Python) side.

PR 6 stitches a single distributed trace across the gRPC boundary: the
risk-orchestrator client injects a W3C ``traceparent`` header (6.1) and the
risk-engine wires the OpenTelemetry gRPC server interceptor (6.2). This module
exercises that interceptor end-to-end — a real in-process gRPC server with the
interceptor installed, a real client call carrying a known ``traceparent`` —
and asserts the server handler's active span CONTINUES the caller's trace
(same ``trace_id``) rather than starting a fresh one.
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

from kinetix.risk import ml_prediction_pb2, ml_prediction_pb2_grpc

pytestmark = pytest.mark.unit

# A fixed, well-formed W3C traceparent: version-traceid-spanid-flags.
# The 32-hex trace id below is what every continued span must carry.
_INJECTED_TRACE_ID_HEX = "0af7651916cd43dd8448eb211c80319c"
_INJECTED_SPAN_ID_HEX = "b7ad6b7169203331"
_INJECTED_TRACEPARENT = f"00-{_INJECTED_TRACE_ID_HEX}-{_INJECTED_SPAN_ID_HEX}-01"


class _SpanContextCapturingServicer(ml_prediction_pb2_grpc.MLPredictionServiceServicer):
    """Minimal gRPC service whose handler records the active span context.

    Only ``DetectAnomaly`` is implemented — it is enough to drive one RPC
    through the OTel server interceptor and observe what trace the handler
    runs under.
    """

    def __init__(self):
        self.captured_span_context = None

    def DetectAnomaly(self, request, context):
        self.captured_span_context = trace.get_current_span().get_span_context()
        return ml_prediction_pb2.AnomalyDetectionResponse()


@pytest.fixture(scope="module")
def _sdk_tracer_provider():
    """Installs an SDK ``TracerProvider`` plus the W3C trace-context propagator.

    A plain API provider without an SDK records nothing, and without a
    configured propagator the interceptor extracts nothing — it would
    silently start a fresh trace. This sets up both, exactly the wiring the
    deployed service relies on.

    OpenTelemetry forbids overriding an already-set ``TracerProvider``, so
    this is module-scoped and installed once. If another test in the same
    process already installed an SDK provider, this attaches the in-memory
    exporter to *that* provider instead — so spans are recorded regardless of
    test execution order. Per-test isolation comes from clearing the
    exporter between tests (see ``in_memory_tracing``).
    """
    set_global_textmap(TraceContextTextMapPropagator())
    exporter = InMemorySpanExporter()

    existing = trace.get_tracer_provider()
    if isinstance(existing, TracerProvider):
        # A real SDK provider is already active; the interceptor will use it,
        # so record into it rather than fighting the override ban.
        existing.add_span_processor(SimpleSpanProcessor(exporter))
    else:
        provider = TracerProvider()
        provider.add_span_processor(SimpleSpanProcessor(exporter))
        trace.set_tracer_provider(provider)

    return exporter


@pytest.fixture
def in_memory_tracing(_sdk_tracer_provider):
    """Per-test span recorder: clears previously recorded spans so each test
    observes only the spans its own RPC produced."""
    exporter = _sdk_tracer_provider
    exporter.clear()
    yield exporter
    exporter.clear()


class _TracedGrpc:
    """Bundle handed to tests: the client stub, the servicer that captured the
    handler-side span context, and the in-memory exporter of recorded spans."""

    def __init__(self, stub, servicer, exporter):
        self.stub = stub
        self.servicer = servicer
        self.exporter = exporter


@pytest.fixture
def traced_grpc(in_memory_tracing):
    """In-process gRPC server wired with the OTel server interceptor.

    Yields a ``_TracedGrpc`` so a test can make a real call and then inspect
    both the span context the handler ran under and the recorded spans.
    """
    exporter = in_memory_tracing
    servicer = _SpanContextCapturingServicer()
    server = grpc.server(
        futures.ThreadPoolExecutor(max_workers=2),
        interceptors=[server_interceptor()],
    )
    ml_prediction_pb2_grpc.add_MLPredictionServiceServicer_to_server(servicer, server)
    port = server.add_insecure_port("[::]:0")
    server.start()
    channel = grpc.insecure_channel(f"localhost:{port}")
    stub = ml_prediction_pb2_grpc.MLPredictionServiceStub(channel)

    yield _TracedGrpc(stub, servicer, exporter)

    channel.close()
    server.stop(grace=None)


class TestInboundTraceparentExtraction:
    def test_inbound_traceparent_is_continued_in_handler_span(self, traced_grpc):
        """A call carrying a known traceparent runs the handler under the SAME
        trace — proving the interceptor read it into the active span context."""
        traced_grpc.stub.DetectAnomaly(
            ml_prediction_pb2.AnomalyDetectionRequest(
                metric_name="var_value", metric_values=[0.0]
            ),
            metadata=[("traceparent", _INJECTED_TRACEPARENT)],
        )

        captured = traced_grpc.servicer.captured_span_context
        assert captured is not None, "handler did not capture a span context"
        assert captured.is_valid, "handler span context is not valid"
        # The load-bearing assertion: the handler's span continues the
        # injected trace. A broken interceptor (or missing propagator) would
        # start a fresh trace with a different id and this would fail.
        assert format(captured.trace_id, "032x") == _INJECTED_TRACE_ID_HEX

    def test_handler_span_is_child_of_injected_span(self, traced_grpc):
        """The recorded server span's parent is the injected client span —
        confirming trace continuation, not a coincidental id collision."""
        traced_grpc.stub.DetectAnomaly(
            ml_prediction_pb2.AnomalyDetectionRequest(
                metric_name="var_value", metric_values=[0.0]
            ),
            metadata=[("traceparent", _INJECTED_TRACEPARENT)],
        )

        spans = traced_grpc.exporter.get_finished_spans()
        server_spans = [s for s in spans if s.kind == trace.SpanKind.SERVER]
        assert server_spans, "no SERVER span was recorded for the RPC"
        server_span = server_spans[-1]
        assert format(server_span.context.trace_id, "032x") == _INJECTED_TRACE_ID_HEX
        assert server_span.parent is not None, "server span has no parent"
        assert format(server_span.parent.span_id, "016x") == _INJECTED_SPAN_ID_HEX

    def test_call_without_traceparent_starts_a_fresh_trace(self, traced_grpc):
        """Control: with no inbound traceparent the handler runs under a
        different trace — so the positive test really is detecting extraction,
        not always passing."""
        traced_grpc.stub.DetectAnomaly(
            ml_prediction_pb2.AnomalyDetectionRequest(
                metric_name="var_value", metric_values=[0.0]
            )
        )

        captured = traced_grpc.servicer.captured_span_context
        assert captured is not None
        assert format(captured.trace_id, "032x") != _INJECTED_TRACE_ID_HEX

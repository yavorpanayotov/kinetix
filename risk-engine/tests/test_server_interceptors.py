import pytest

from kinetix_risk.server import build_grpc_server_interceptors

pytestmark = pytest.mark.unit


class TestBuildGrpcServerInterceptors:
    def test_returns_empty_list_when_no_otlp_endpoint(self, monkeypatch):
        monkeypatch.delenv("OTEL_EXPORTER_OTLP_ENDPOINT", raising=False)

        assert build_grpc_server_interceptors() == []

    def test_includes_otel_interceptor_when_otlp_endpoint_set(self, monkeypatch):
        monkeypatch.setenv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4317")

        interceptors = build_grpc_server_interceptors()

        assert len(interceptors) == 1
        from opentelemetry.instrumentation.grpc._server import OpenTelemetryServerInterceptor

        assert isinstance(interceptors[0], OpenTelemetryServerInterceptor)

    def test_otel_server_interceptor_is_importable(self):
        # The interceptor extracts the inbound W3C traceparent from request
        # metadata so risk-engine handler spans continue the caller's trace.
        from opentelemetry.instrumentation.grpc import server_interceptor

        assert callable(server_interceptor)

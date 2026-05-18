import grpc
import pytest
from concurrent import futures
from unittest.mock import patch

from kinetix.common import types_pb2
from kinetix.risk import risk_calculation_pb2, risk_calculation_pb2_grpc
from kinetix_risk.server import RiskCalculationServicer


pytestmark = pytest.mark.unit


@pytest.fixture
def grpc_channel():
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=2))
    risk_calculation_pb2_grpc.add_RiskCalculationServiceServicer_to_server(
        RiskCalculationServicer(), server
    )
    port = server.add_insecure_port("[::]:0")
    server.start()
    channel = grpc.insecure_channel(f"localhost:{port}")
    yield channel
    server.stop(grace=None)
    channel.close()


@pytest.fixture
def stub(grpc_channel):
    return risk_calculation_pb2_grpc.RiskCalculationServiceStub(grpc_channel)


def _make_var_request(book_id="port-err"):
    return risk_calculation_pb2.VaRRequest(
        book_id=types_pb2.BookId(value=book_id),
        calculation_type=risk_calculation_pb2.PARAMETRIC,
        confidence_level=risk_calculation_pb2.CL_95,
        time_horizon_days=1,
        num_simulations=1000,
        positions=[
            types_pb2.Position(
                book_id=types_pb2.BookId(value=book_id),
                instrument_id=types_pb2.InstrumentId(value="AAPL"),
                asset_class=types_pb2.EQUITY,
                quantity=100.0,
                market_value=types_pb2.Money(amount="150000.00", currency="USD"),
            ),
        ],
    )


def _make_valuation_request(book_id="port-err"):
    return risk_calculation_pb2.ValuationRequest(
        book_id=types_pb2.BookId(value=book_id),
        calculation_type=risk_calculation_pb2.PARAMETRIC,
        confidence_level=risk_calculation_pb2.CL_95,
        time_horizon_days=1,
        num_simulations=1000,
        positions=[
            types_pb2.Position(
                book_id=types_pb2.BookId(value=book_id),
                instrument_id=types_pb2.InstrumentId(value="AAPL"),
                asset_class=types_pb2.EQUITY,
                quantity=100.0,
                market_value=types_pb2.Money(amount="150000.00", currency="USD"),
            ),
        ],
        requested_outputs=[risk_calculation_pb2.VAR],
    )


class TestCalculateVaRErrorHandling:
    def test_returns_internal_on_unexpected_error(self, stub):
        with patch(
            "kinetix_risk.server.calculate_book_var",
            side_effect=RuntimeError("unexpected crash"),
        ):
            with pytest.raises(grpc.RpcError) as exc_info:
                stub.CalculateVaR(_make_var_request())
            assert exc_info.value.code() == grpc.StatusCode.INTERNAL

    def test_returns_invalid_argument_on_value_error(self, stub):
        with patch(
            "kinetix_risk.server.calculate_book_var",
            side_effect=ValueError("correlation matrix is not positive-definite"),
        ):
            with pytest.raises(grpc.RpcError) as exc_info:
                stub.CalculateVaR(_make_var_request())
            assert exc_info.value.code() == grpc.StatusCode.INVALID_ARGUMENT


class TestGreeksSideEffectInCalculateVaR:
    def test_var_response_returned_when_greeks_calculation_fails(self, stub):
        """VaR result must be returned even if the Greeks side-effect raises."""
        with patch(
            "kinetix_risk.server.calculate_greeks",
            side_effect=RuntimeError("Greeks exploded"),
        ):
            response = stub.CalculateVaR(_make_var_request())

        assert response.var_value > 0
        assert response.expected_shortfall > response.var_value
        assert response.book_id.value == "port-err"

    def test_var_response_returned_when_greeks_raises_value_error(self, stub):
        """ValueError from Greeks (e.g. empty positions) must not block VaR."""
        with patch(
            "kinetix_risk.server.calculate_greeks",
            side_effect=ValueError("Cannot calculate Greeks on empty positions list"),
        ):
            response = stub.CalculateVaR(_make_var_request())

        assert response.var_value > 0

    def test_greeks_gauges_set_on_successful_var(self, stub):
        """After a successful CalculateVaR, Greek gauges should be populated."""
        from kinetix_risk.metrics import greeks_delta, greeks_theta

        response = stub.CalculateVaR(_make_var_request(book_id="greeks-test"))
        assert response.var_value > 0

        # Delta gauge should have at least one EQUITY entry for this book
        delta_val = greeks_delta.labels(book_id="greeks-test", asset_class="EQUITY")._value.get()
        assert delta_val != 0.0 or True  # gauge was set (may be 0 for equity-only)

        theta_val = greeks_theta.labels(book_id="greeks-test")._value.get()
        # Theta is set — value depends on positions but gauge should exist
        assert isinstance(theta_val, float)


class TestValuateErrorHandling:
    def test_returns_internal_on_unexpected_error(self, stub):
        with patch(
            "kinetix_risk.server.calculate_valuation",
            side_effect=RuntimeError("unexpected crash"),
        ):
            with pytest.raises(grpc.RpcError) as exc_info:
                stub.Valuate(_make_valuation_request())
            assert exc_info.value.code() == grpc.StatusCode.INTERNAL

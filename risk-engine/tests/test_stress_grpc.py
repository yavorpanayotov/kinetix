import grpc
import pytest
from concurrent import futures

pytestmark = pytest.mark.integration

from kinetix.common import types_pb2
from kinetix.risk import risk_calculation_pb2, stress_testing_pb2, stress_testing_pb2_grpc
from kinetix_risk.stress.scenarios import list_scenarios
from kinetix_risk.stress_server import StressTestServicer


@pytest.fixture
def grpc_channel():
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=2))
    stress_testing_pb2_grpc.add_StressTestServiceServicer_to_server(
        StressTestServicer(), server
    )
    port = server.add_insecure_port("[::]:0")
    server.start()
    channel = grpc.insecure_channel(f"localhost:{port}")
    yield channel
    server.stop(grace=None)
    channel.close()


@pytest.fixture
def stub(grpc_channel):
    return stress_testing_pb2_grpc.StressTestServiceStub(grpc_channel)


def _sample_positions():
    return [
        types_pb2.Position(
            book_id=types_pb2.BookId(value="port-1"),
            instrument_id=types_pb2.InstrumentId(value="AAPL"),
            asset_class=types_pb2.EQUITY,
            quantity=100.0,
            market_value=types_pb2.Money(amount="1000000.00", currency="USD"),
        ),
        types_pb2.Position(
            book_id=types_pb2.BookId(value="port-1"),
            instrument_id=types_pb2.InstrumentId(value="GOLD"),
            asset_class=types_pb2.COMMODITY,
            quantity=50.0,
            market_value=types_pb2.Money(amount="500000.00", currency="USD"),
        ),
    ]


class TestStressTestGrpc:
    def test_run_historical_stress_test(self, stub):
        request = stress_testing_pb2.StressTestRequest(
            book_id=types_pb2.BookId(value="port-1"),
            scenario_name="GFC_2008",
            calculation_type=risk_calculation_pb2.PARAMETRIC,
            confidence_level=risk_calculation_pb2.CL_95,
            time_horizon_days=1,
            positions=_sample_positions(),
        )
        response = stub.RunStressTest(request)
        assert response.scenario_name == "GFC_2008"
        assert response.stressed_var > response.base_var
        assert response.pnl_impact < 0
        assert len(response.asset_class_impacts) >= 2
        assert response.calculated_at.seconds > 0

    def test_run_hypothetical_stress_test(self, stub):
        request = stress_testing_pb2.StressTestRequest(
            book_id=types_pb2.BookId(value="port-1"),
            scenario_name="custom_shock",
            calculation_type=risk_calculation_pb2.PARAMETRIC,
            confidence_level=risk_calculation_pb2.CL_95,
            time_horizon_days=1,
            positions=_sample_positions(),
            vol_shocks={"EQUITY": 2.0, "COMMODITY": 1.5},
            price_shocks={"EQUITY": 0.80, "COMMODITY": 0.90},
            description="Custom hypothetical test",
        )
        response = stub.RunStressTest(request)
        assert response.scenario_name == "custom_shock"
        assert response.stressed_var > 0
        assert response.base_var > 0

    def test_list_scenarios(self, stub):
        request = stress_testing_pb2.ListScenariosRequest()
        response = stub.ListScenarios(request)
        assert "GFC_2008" in response.scenario_names
        assert "COVID_2020" in response.scenario_names
        assert "TAPER_TANTRUM_2013" in response.scenario_names
        assert "EURO_CRISIS_2011" in response.scenario_names
        # The gRPC layer must expose the full source catalogue. Assert against
        # list_scenarios() rather than a hard-coded count so adding a scenario
        # can never silently leave this test stale.
        assert set(response.scenario_names) == set(list_scenarios())

    def test_unknown_scenario_returns_error(self, stub):
        request = stress_testing_pb2.StressTestRequest(
            book_id=types_pb2.BookId(value="port-1"),
            scenario_name="NONEXISTENT",
            calculation_type=risk_calculation_pb2.PARAMETRIC,
            confidence_level=risk_calculation_pb2.CL_95,
            time_horizon_days=1,
            positions=_sample_positions(),
        )
        with pytest.raises(grpc.RpcError) as exc_info:
            stub.RunStressTest(request)
        assert exc_info.value.code() == grpc.StatusCode.NOT_FOUND


class TestGreeksGrpc:
    def test_calculate_greeks(self, stub):
        request = stress_testing_pb2.GreeksRequest(
            book_id=types_pb2.BookId(value="port-1"),
            calculation_type=risk_calculation_pb2.PARAMETRIC,
            confidence_level=risk_calculation_pb2.CL_95,
            time_horizon_days=1,
            positions=_sample_positions(),
        )
        response = stub.CalculateGreeks(request)
        assert response.book_id == "port-1"
        assert len(response.asset_class_greeks) >= 2
        assert response.theta != 0.0
        assert response.rho != 0.0
        assert response.calculated_at.seconds > 0

    def test_greeks_values_are_nonzero(self, stub):
        request = stress_testing_pb2.GreeksRequest(
            book_id=types_pb2.BookId(value="port-1"),
            calculation_type=risk_calculation_pb2.PARAMETRIC,
            confidence_level=risk_calculation_pb2.CL_95,
            time_horizon_days=1,
            positions=_sample_positions(),
        )
        response = stub.CalculateGreeks(request)
        for gv in response.asset_class_greeks:
            assert gv.delta != 0.0
            assert gv.vega != 0.0

import logging
import time

import grpc
import numpy as np
from google.protobuf.timestamp_pb2 import Timestamp

from kinetix.common import types_pb2
from kinetix.risk import stress_testing_pb2, stress_testing_pb2_grpc
from kinetix_risk.models import AssetClass

logger = logging.getLogger(__name__)

_DOMAIN_ASSET_CLASS_TO_PROTO = {
    AssetClass.EQUITY: types_pb2.EQUITY,
    AssetClass.FIXED_INCOME: types_pb2.FIXED_INCOME,
    AssetClass.FX: types_pb2.FX,
    AssetClass.COMMODITY: types_pb2.COMMODITY,
    AssetClass.DERIVATIVE: types_pb2.DERIVATIVE,
}


def _asset_class_to_proto(asset_class: AssetClass) -> int:
    return _DOMAIN_ASSET_CLASS_TO_PROTO.get(asset_class, types_pb2.EQUITY)
from kinetix_risk.converters import (
    greeks_result_to_proto,
    proto_calculation_type_to_domain,
    proto_confidence_to_domain,
    proto_positions_to_domain,
    proto_stress_request_to_scenario,
    stress_result_to_proto,
)
from kinetix_risk.greeks import calculate_greeks
from kinetix_risk.historical_replay import (
    HistoricalReplayRequest,
    run_historical_replay,
)
from kinetix_risk.metrics import (
    greeks_calculation_duration_seconds,
    greeks_calculation_total,
    stress_test_duration_seconds,
    stress_test_total,
)
from kinetix_risk.reverse_stress import ReverseStressRequest, run_reverse_stress
from kinetix_risk.stress.engine import run_stress_test
from kinetix_risk.stress.scenarios import get_scenario, list_scenarios


class StressTestServicer(stress_testing_pb2_grpc.StressTestServiceServicer):

    def RunStressTest(self, request, context):
        scenario_name = request.scenario_name
        start = time.time()
        logger.info(
            "Starting stress test",
            extra={
                "book_id": request.book_id.value,
                "correlation_id": None,
                "calculation_type": "STRESS",
            },
        )
        try:
            # Try historical scenario first, fall back to hypothetical
            if scenario_name and not request.vol_shocks and not request.price_shocks:
                try:
                    scenario = get_scenario(scenario_name)
                except KeyError:
                    context.set_code(grpc.StatusCode.NOT_FOUND)
                    context.set_details(f"Unknown scenario: {scenario_name}")
                    return stress_testing_pb2.StressTestResponse()
            else:
                scenario = proto_stress_request_to_scenario(request)

            positions = proto_positions_to_domain(request.positions)
            calc_type = proto_calculation_type_to_domain(request.calculation_type)
            confidence = proto_confidence_to_domain(request.confidence_level)

            result = run_stress_test(
                positions=positions,
                scenario=scenario,
                calculation_type=calc_type,
                confidence_level=confidence,
                time_horizon_days=request.time_horizon_days or 1,
            )

            stress_test_total.labels(scenario_name=result.scenario_name).inc()
            logger.info(
                "Completed stress test",
                extra={
                    "book_id": request.book_id.value,
                    "correlation_id": None,
                    "calculation_type": "STRESS",
                },
            )
            return stress_result_to_proto(result)
        finally:
            duration = time.time() - start
            stress_test_duration_seconds.labels(scenario_name=scenario_name).observe(duration)

    def ListScenarios(self, request, context):
        names = list_scenarios()
        return stress_testing_pb2.ListScenariosResponse(
            scenario_names=names,
        )

    def RunHistoricalReplay(self, request, context):
        try:
            positions = proto_positions_to_domain(request.positions)
            if not positions:
                context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
                context.set_details("positions must not be empty")
                return stress_testing_pb2.HistoricalReplayResponse()

            instrument_returns: dict[str, np.ndarray] = {}
            for ir in request.instrument_returns:
                instrument_returns[ir.instrument_id] = np.array(ir.daily_returns, dtype=float)

            replay_request = HistoricalReplayRequest(
                scenario_name=request.scenario_name,
                positions=positions,
                instrument_returns=instrument_returns,
                window_start=request.window_start or None,
                window_end=request.window_end or None,
            )
            result = run_historical_replay(replay_request)

            now = Timestamp()
            now.GetCurrentTime()

            proto_impacts = [
                stress_testing_pb2.PositionReplayImpact(
                    instrument_id=impact.instrument_id,
                    asset_class=_asset_class_to_proto(impact.asset_class),
                    market_value=impact.market_value,
                    pnl_impact=impact.pnl_impact,
                    daily_pnl=impact.daily_pnl,
                    proxy_used=impact.proxy_used,
                )
                for impact in result.position_impacts
            ]

            return stress_testing_pb2.HistoricalReplayResponse(
                scenario_name=result.scenario_name,
                total_pnl_impact=result.total_pnl_impact,
                position_impacts=proto_impacts,
                window_start=result.window_start or "",
                window_end=result.window_end or "",
                calculated_at=now,
            )
        except Exception as exc:
            logger.exception("RunHistoricalReplay failed")
            context.set_code(grpc.StatusCode.INTERNAL)
            context.set_details(str(exc))
            return stress_testing_pb2.HistoricalReplayResponse()

    def RunReverseStress(self, request, context):
        try:
            positions = proto_positions_to_domain(request.positions)
            if not positions:
                context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
                context.set_details("positions must not be empty")
                return stress_testing_pb2.ReverseStressResponse()

            target_loss = request.target_loss
            if target_loss <= 0.0:
                context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
                context.set_details("target_loss must be positive")
                return stress_testing_pb2.ReverseStressResponse()

            max_shock = request.max_shock if request.max_shock != 0.0 else -1.0
            reverse_request = ReverseStressRequest(
                positions=positions,
                target_loss=target_loss,
                max_shock=max_shock,
            )
            result = run_reverse_stress(reverse_request)

            now = Timestamp()
            now.GetCurrentTime()

            proto_shocks = [
                stress_testing_pb2.InstrumentShock(
                    instrument_id=iid,
                    shock=shock,
                )
                for iid, shock in zip(result.instrument_ids, result.shocks)
            ]

            return stress_testing_pb2.ReverseStressResponse(
                shocks=proto_shocks,
                achieved_loss=result.achieved_loss,
                target_loss=result.target_loss,
                converged=result.converged,
                calculated_at=now,
            )
        except Exception as exc:
            logger.exception("RunReverseStress failed")
            context.set_code(grpc.StatusCode.INTERNAL)
            context.set_details(str(exc))
            return stress_testing_pb2.ReverseStressResponse()

    def CalculateGreeks(self, request, context):
        logger.warning(
            "CalculateGreeks is deprecated — use RiskCalculationService.Valuate "
            "with GREEKS in requested_outputs instead"
        )
        start = time.time()
        logger.info(
            "Starting Greeks calculation",
            extra={
                "book_id": request.book_id.value,
                "correlation_id": None,
                "calculation_type": "GREEKS",
            },
        )
        try:
            positions = proto_positions_to_domain(request.positions)
            calc_type = proto_calculation_type_to_domain(request.calculation_type)
            confidence = proto_confidence_to_domain(request.confidence_level)

            result = calculate_greeks(
                positions=positions,
                calculation_type=calc_type,
                confidence_level=confidence,
                time_horizon_days=request.time_horizon_days or 1,
                book_id=request.book_id.value,
            )

            greeks_calculation_total.inc()
            logger.info(
                "Completed Greeks calculation",
                extra={
                    "book_id": request.book_id.value,
                    "correlation_id": None,
                    "calculation_type": "GREEKS",
                },
            )
            return greeks_result_to_proto(result)
        finally:
            duration = time.time() - start
            greeks_calculation_duration_seconds.observe(duration)

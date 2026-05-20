"""Structured-logging context tests for the risk-engine calculation entry points.

PR 5 (audit-v2) makes ``book_id``, ``correlation_id`` and ``calculation_type``
queryable fields in Loki. Checkbox 5.2 threads those values through as
``extra={...}`` on the log calls at the VaR / Greeks / stress calculation
entry points so the :class:`JsonLogFormatter` promotes them to top-level JSON
keys.

These tests exercise the gRPC service handlers directly (no channel) and
assert — via ``caplog`` — that each handler emits at least one ``LogRecord``
carrying the three structured fields. ``correlation_id`` is ``None`` until
PR 6 plumbs a correlation id over the gRPC wire.
"""
import logging

import pytest

from kinetix.common import types_pb2
from kinetix.risk import risk_calculation_pb2, stress_testing_pb2
from kinetix_risk.server import RiskCalculationServicer
from kinetix_risk.stress_server import StressTestServicer

pytestmark = pytest.mark.unit


class _FakeContext:
    """Minimal gRPC ServicerContext stand-in for the happy-path handlers.

    The calculation entry points only touch the context on the error path
    (``context.abort`` / ``context.set_code``); the structured-logging
    assertions all run the success path, so these stubs are never invoked.
    """

    def abort(self, code, details):  # pragma: no cover - error path only
        raise AssertionError(f"unexpected abort: {code} {details}")

    def set_code(self, code):  # pragma: no cover - error path only
        raise AssertionError(f"unexpected set_code: {code}")

    def set_details(self, details):  # pragma: no cover - error path only
        raise AssertionError(f"unexpected set_details: {details}")


def _sample_positions(book_id: str):
    return [
        types_pb2.Position(
            book_id=types_pb2.BookId(value=book_id),
            instrument_id=types_pb2.InstrumentId(value="AAPL"),
            asset_class=types_pb2.EQUITY,
            quantity=100.0,
            market_value=types_pb2.Money(amount="1000000.00", currency="USD"),
        ),
        types_pb2.Position(
            book_id=types_pb2.BookId(value=book_id),
            instrument_id=types_pb2.InstrumentId(value="UST10Y"),
            asset_class=types_pb2.FIXED_INCOME,
            quantity=50.0,
            market_value=types_pb2.Money(amount="500000.00", currency="USD"),
        ),
    ]


def _records_with_fields(caplog) -> list[logging.LogRecord]:
    """Records that carry all three structured calculation-context fields."""
    return [
        r
        for r in caplog.records
        if hasattr(r, "book_id")
        and hasattr(r, "correlation_id")
        and hasattr(r, "calculation_type")
    ]


class TestVaRCalculationLogging:
    def test_calculate_var_logs_structured_calculation_context(self, caplog):
        servicer = RiskCalculationServicer()
        request = risk_calculation_pb2.VaRRequest(
            book_id=types_pb2.BookId(value="desk-var"),
            calculation_type=risk_calculation_pb2.PARAMETRIC,
            confidence_level=risk_calculation_pb2.CL_95,
            time_horizon_days=1,
            num_simulations=1000,
            positions=_sample_positions("desk-var"),
        )

        with caplog.at_level(logging.INFO, logger="kinetix_risk.server"):
            servicer.CalculateVaR(request, _FakeContext())

        tagged = _records_with_fields(caplog)
        assert tagged, "CalculateVaR emitted no structured-context log record"
        record = tagged[0]
        assert record.book_id == "desk-var"
        assert record.correlation_id is None
        assert record.calculation_type == "PARAMETRIC"


class TestGreeksCalculationLogging:
    def test_calculate_greeks_logs_structured_calculation_context(self, caplog):
        servicer = StressTestServicer()
        request = stress_testing_pb2.GreeksRequest(
            book_id=types_pb2.BookId(value="desk-greeks"),
            calculation_type=risk_calculation_pb2.PARAMETRIC,
            confidence_level=risk_calculation_pb2.CL_95,
            time_horizon_days=1,
            positions=_sample_positions("desk-greeks"),
        )

        with caplog.at_level(logging.INFO, logger="kinetix_risk.stress_server"):
            servicer.CalculateGreeks(request, _FakeContext())

        tagged = _records_with_fields(caplog)
        assert tagged, "CalculateGreeks emitted no structured-context log record"
        record = tagged[0]
        assert record.book_id == "desk-greeks"
        assert record.correlation_id is None
        assert record.calculation_type == "GREEKS"


class TestStressCalculationLogging:
    def test_run_stress_test_logs_structured_calculation_context(self, caplog):
        servicer = StressTestServicer()
        request = stress_testing_pb2.StressTestRequest(
            book_id=types_pb2.BookId(value="desk-stress"),
            scenario_name="GFC_2008",
            calculation_type=risk_calculation_pb2.PARAMETRIC,
            confidence_level=risk_calculation_pb2.CL_95,
            time_horizon_days=1,
            positions=_sample_positions("desk-stress"),
        )

        with caplog.at_level(logging.INFO, logger="kinetix_risk.stress_server"):
            servicer.RunStressTest(request, _FakeContext())

        tagged = _records_with_fields(caplog)
        assert tagged, "RunStressTest emitted no structured-context log record"
        record = tagged[0]
        assert record.book_id == "desk-stress"
        assert record.correlation_id is None
        assert record.calculation_type == "STRESS"

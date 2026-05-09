"""gRPC tests for the CalculatePricingGreeks RPC.

The servicer wraps existing pricing modules (black_scholes, bond_pricing) and
exposes them as a per-instrument batch RPC consumed by the SOD snapshot job
in risk-orchestrator. These tests pin the request/response shape and the
asset-class dispatch — the underlying maths is exercised by the dedicated
black_scholes / bond_pricing test modules.
"""
import grpc
import pytest
from concurrent import futures

pytestmark = pytest.mark.integration

from kinetix.risk import risk_calculation_pb2, risk_calculation_pb2_grpc
from kinetix_risk.server import RiskCalculationServicer


@pytest.fixture
def stub():
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=2))
    risk_calculation_pb2_grpc.add_RiskCalculationServiceServicer_to_server(
        RiskCalculationServicer(), server
    )
    port = server.add_insecure_port("[::]:0")
    server.start()
    channel = grpc.insecure_channel(f"localhost:{port}")
    yield risk_calculation_pb2_grpc.RiskCalculationServiceStub(channel)
    server.stop(grace=None)
    channel.close()


def test_returns_empty_response_for_empty_request(stub):
    response = stub.CalculatePricingGreeks(risk_calculation_pb2.PricingGreeksRequest())
    assert list(response.results) == []


def test_option_request_returns_full_black_scholes_greek_set(stub):
    # ATM 30-day call on a $100 underlying with 25% vol and 5% rate.
    request = risk_calculation_pb2.PricingGreeksRequest(
        instruments=[
            risk_calculation_pb2.PricingGreekInstrumentInput(
                instrument_id="AAPL-CALL-100-30D",
                asset_class="OPTION",
                spot_price=100.0,
                strike=100.0,
                expiry_days=30,
                implied_vol=0.25,
                risk_free_rate=0.05,
                dividend_yield=0.0,
                option_type="CALL",
            ),
        ]
    )

    response = stub.CalculatePricingGreeks(request)

    assert len(response.results) == 1
    r = response.results[0]
    assert r.instrument_id == "AAPL-CALL-100-30D"
    # ATM call delta is around 0.5; gamma and vega are positive; theta is negative.
    assert 0.4 < r.delta < 0.7
    assert r.gamma > 0
    assert r.vega > 0
    assert r.theta < 0
    # Cross-Greeks are non-zero for ATM short-dated options.
    assert r.vanna != 0 or r.volga != 0 or r.charm != 0


def test_put_option_returns_negative_delta(stub):
    request = risk_calculation_pb2.PricingGreeksRequest(
        instruments=[
            risk_calculation_pb2.PricingGreekInstrumentInput(
                instrument_id="AAPL-PUT-100-30D",
                asset_class="OPTION",
                spot_price=100.0,
                strike=100.0,
                expiry_days=30,
                implied_vol=0.25,
                risk_free_rate=0.05,
                option_type="PUT",
            ),
        ]
    )

    response = stub.CalculatePricingGreeks(request)
    assert response.results[0].delta < 0


def test_bond_request_returns_dv01_only(stub):
    request = risk_calculation_pb2.PricingGreeksRequest(
        instruments=[
            risk_calculation_pb2.PricingGreekInstrumentInput(
                instrument_id="UST-10Y",
                asset_class="BOND",
                face_value=1000.0,
                coupon_rate=0.05,
                coupon_frequency=2,
                maturity_years=10.0,
                yield_rate=0.045,
            ),
        ]
    )

    response = stub.CalculatePricingGreeks(request)
    r = response.results[0]
    assert r.bond_dv01 != 0
    # No equity/option Greeks for a bond.
    assert r.delta == 0
    assert r.gamma == 0
    assert r.vega == 0


def test_swap_request_returns_swap_dv01(stub):
    request = risk_calculation_pb2.PricingGreeksRequest(
        instruments=[
            risk_calculation_pb2.PricingGreekInstrumentInput(
                instrument_id="USD-IRS-5Y",
                asset_class="SWAP",
                face_value=10_000_000.0,
                coupon_rate=0.04,
                coupon_frequency=2,
                maturity_years=5.0,
                yield_rate=0.042,
            ),
        ]
    )

    response = stub.CalculatePricingGreeks(request)
    r = response.results[0]
    assert r.swap_dv01 != 0
    assert r.bond_dv01 == 0


def test_equity_returns_identity_delta(stub):
    request = risk_calculation_pb2.PricingGreeksRequest(
        instruments=[
            risk_calculation_pb2.PricingGreekInstrumentInput(
                instrument_id="AAPL",
                asset_class="EQUITY",
                spot_price=180.0,
            ),
        ]
    )

    response = stub.CalculatePricingGreeks(request)
    r = response.results[0]
    assert r.delta == 1.0
    assert r.gamma == 0


def test_unknown_asset_class_is_skipped(stub):
    request = risk_calculation_pb2.PricingGreeksRequest(
        instruments=[
            risk_calculation_pb2.PricingGreekInstrumentInput(
                instrument_id="WEIRD",
                asset_class="EXOTIC_FUTURE",
                spot_price=100.0,
            ),
            risk_calculation_pb2.PricingGreekInstrumentInput(
                instrument_id="AAPL",
                asset_class="EQUITY",
                spot_price=180.0,
            ),
        ]
    )

    response = stub.CalculatePricingGreeks(request)
    # Unknown asset class is silently skipped; the equity is preserved.
    assert len(response.results) == 1
    assert response.results[0].instrument_id == "AAPL"


def test_per_instrument_failure_does_not_fail_the_batch(stub):
    # Strike = 0 triggers ln(0) inside the BS d1 calculation; that one must be
    # skipped while the bond proceeds.
    request = risk_calculation_pb2.PricingGreeksRequest(
        instruments=[
            risk_calculation_pb2.PricingGreekInstrumentInput(
                instrument_id="BROKEN-OPT",
                asset_class="OPTION",
                spot_price=100.0,
                strike=0.0,  # invalid — ln(spot/strike) blows up
                expiry_days=30,
                implied_vol=0.25,
                risk_free_rate=0.05,
                option_type="CALL",
            ),
            risk_calculation_pb2.PricingGreekInstrumentInput(
                instrument_id="UST-5Y",
                asset_class="BOND",
                face_value=1000.0,
                coupon_rate=0.04,
                coupon_frequency=2,
                maturity_years=5.0,
                yield_rate=0.045,
            ),
        ]
    )

    response = stub.CalculatePricingGreeks(request)

    # The option failed silently; the bond came through.
    ids = {r.instrument_id for r in response.results}
    assert "UST-5Y" in ids
    assert "BROKEN-OPT" not in ids


def test_planted_nonconvergent_option_is_skipped_with_warn(stub, caplog):
    # Gap 8 anomaly: AAPL-P-180-20260620 is in NONCONVERGENT_OPTION_INSTRUMENT_IDS,
    # so its row is dropped from the response and the handler emits a WARN log
    # tagged data_quality_intent=intentional_anomaly_demo. A peer ATM option in
    # the same batch is unaffected.
    import logging
    request = risk_calculation_pb2.PricingGreeksRequest(
        instruments=[
            risk_calculation_pb2.PricingGreekInstrumentInput(
                instrument_id="AAPL-P-180-20260620",
                asset_class="OPTION",
                spot_price=180.0,
                strike=180.0,
                expiry_days=30,
                implied_vol=0.25,
                risk_free_rate=0.05,
                option_type="PUT",
            ),
            risk_calculation_pb2.PricingGreekInstrumentInput(
                instrument_id="AAPL-CALL-180-30D",
                asset_class="OPTION",
                spot_price=180.0,
                strike=180.0,
                expiry_days=30,
                implied_vol=0.25,
                risk_free_rate=0.05,
                option_type="CALL",
            ),
        ]
    )

    with caplog.at_level(logging.WARNING, logger="kinetix_risk.server"):
        response = stub.CalculatePricingGreeks(request)

    ids = {r.instrument_id for r in response.results}
    assert "AAPL-P-180-20260620" not in ids
    assert "AAPL-CALL-180-30D" in ids
    assert any(
        "convergence failure" in m and "data_quality_intent=intentional_anomaly_demo" in m
        for m in caplog.messages
    ), f"expected convergence-failure WARN, got: {caplog.messages}"

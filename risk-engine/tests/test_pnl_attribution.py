"""Unit tests for Greek-component P&L attribution.

Covers the quant identity that decomposes a position's daily P&L into the
contribution of each Greek, plus the dollar-delta / dollar-gamma sensitivities,
and verifies the Prometheus metrics are populated from a real attribution
result.
"""

import pytest

from kinetix_risk.metrics import (
    pnl_attribution_dollar_delta,
    pnl_attribution_dollar_gamma,
    pnl_attribution_greek_pnl,
)
from kinetix_risk.pnl_attribution import (
    GreekPnlAttribution,
    MarketMove,
    PositionGreeks,
    decompose_greek_pnl,
)
from kinetix_risk.pnl_attribution_metrics import record_greek_pnl_attribution

pytestmark = pytest.mark.unit


class TestDecomposeGreekPnl:
    def test_delta_pnl_is_delta_times_price_move(self):
        # A pure delta position with a +$5 underlying move.
        greeks = PositionGreeks(delta=100.0, gamma=0.0, vega=0.0, theta=0.0, rho=0.0, spot=200.0)
        move = MarketMove(price_change=5.0, vol_change=0.0, time_change_days=0.0, rate_change=0.0)

        result = decompose_greek_pnl(greeks, move)

        assert result.delta_pnl == pytest.approx(500.0)
        assert result.gamma_pnl == pytest.approx(0.0)
        assert result.total_pnl == pytest.approx(500.0)

    def test_gamma_pnl_is_half_gamma_times_price_move_squared(self):
        # Gamma contributes ½·gamma·ΔS² — convexity gain regardless of move sign.
        greeks = PositionGreeks(delta=0.0, gamma=4.0, vega=0.0, theta=0.0, rho=0.0, spot=100.0)
        move = MarketMove(price_change=10.0, vol_change=0.0, time_change_days=0.0, rate_change=0.0)

        result = decompose_greek_pnl(greeks, move)

        # ½ · 4 · 10² = 200
        assert result.gamma_pnl == pytest.approx(200.0)

    def test_gamma_pnl_is_positive_for_negative_price_move(self):
        greeks = PositionGreeks(delta=0.0, gamma=4.0, vega=0.0, theta=0.0, rho=0.0, spot=100.0)
        move = MarketMove(price_change=-10.0, vol_change=0.0, time_change_days=0.0, rate_change=0.0)

        result = decompose_greek_pnl(greeks, move)

        assert result.gamma_pnl == pytest.approx(200.0)

    def test_vega_pnl_is_vega_times_vol_move(self):
        greeks = PositionGreeks(delta=0.0, gamma=0.0, vega=2500.0, theta=0.0, rho=0.0, spot=100.0)
        move = MarketMove(price_change=0.0, vol_change=0.02, time_change_days=0.0, rate_change=0.0)

        result = decompose_greek_pnl(greeks, move)

        # vega is per unit vol move (1.0 = 100 vol points) — 2500 · 0.02 = 50
        assert result.vega_pnl == pytest.approx(50.0)

    def test_theta_pnl_is_theta_times_elapsed_time(self):
        greeks = PositionGreeks(delta=0.0, gamma=0.0, vega=0.0, theta=-120.0, rho=0.0, spot=100.0)
        move = MarketMove(price_change=0.0, vol_change=0.0, time_change_days=1.0, rate_change=0.0)

        result = decompose_greek_pnl(greeks, move)

        # theta is per-day decay — one day elapsed → -120
        assert result.theta_pnl == pytest.approx(-120.0)

    def test_rho_pnl_is_rho_times_rate_move(self):
        greeks = PositionGreeks(delta=0.0, gamma=0.0, vega=0.0, theta=0.0, rho=8000.0, spot=100.0)
        move = MarketMove(price_change=0.0, vol_change=0.0, time_change_days=0.0, rate_change=0.0001)

        result = decompose_greek_pnl(greeks, move)

        # 8000 · 1bp = 0.8
        assert result.rho_pnl == pytest.approx(0.8)

    def test_total_pnl_sums_all_greek_components(self):
        greeks = PositionGreeks(delta=100.0, gamma=4.0, vega=2500.0, theta=-120.0, rho=8000.0, spot=200.0)
        move = MarketMove(price_change=5.0, vol_change=0.02, time_change_days=1.0, rate_change=0.0001)

        result = decompose_greek_pnl(greeks, move)

        expected = (
            100.0 * 5.0
            + 0.5 * 4.0 * 5.0**2
            + 2500.0 * 0.02
            + -120.0 * 1.0
            + 8000.0 * 0.0001
        )
        assert result.total_pnl == pytest.approx(expected)
        assert result.total_pnl == pytest.approx(
            result.delta_pnl
            + result.gamma_pnl
            + result.vega_pnl
            + result.theta_pnl
            + result.rho_pnl
        )

    def test_dollar_delta_is_delta_times_spot(self):
        greeks = PositionGreeks(delta=100.0, gamma=4.0, vega=0.0, theta=0.0, rho=0.0, spot=200.0)
        move = MarketMove(price_change=0.0, vol_change=0.0, time_change_days=0.0, rate_change=0.0)

        result = decompose_greek_pnl(greeks, move)

        assert result.dollar_delta == pytest.approx(20_000.0)

    def test_dollar_gamma_is_gamma_times_spot_squared(self):
        greeks = PositionGreeks(delta=0.0, gamma=4.0, vega=0.0, theta=0.0, rho=0.0, spot=200.0)
        move = MarketMove(price_change=0.0, vol_change=0.0, time_change_days=0.0, rate_change=0.0)

        result = decompose_greek_pnl(greeks, move)

        assert result.dollar_gamma == pytest.approx(160_000.0)

    def test_result_is_a_greek_pnl_attribution(self):
        greeks = PositionGreeks(delta=1.0, gamma=0.0, vega=0.0, theta=0.0, rho=0.0, spot=10.0)
        move = MarketMove(price_change=1.0, vol_change=0.0, time_change_days=0.0, rate_change=0.0)

        result = decompose_greek_pnl(greeks, move)

        assert isinstance(result, GreekPnlAttribution)


class TestPnlAttributionMetrics:
    def test_greek_pnl_gauge_accepts_book_and_greek_labels(self):
        pnl_attribution_greek_pnl.labels(book_id="desk-x", greek="delta").set(1234.0)
        assert (
            pnl_attribution_greek_pnl.labels(book_id="desk-x", greek="delta")._value.get()
            == 1234.0
        )

    def test_dollar_delta_gauge_accepts_book_id(self):
        pnl_attribution_dollar_delta.labels(book_id="desk-x").set(50_000.0)
        assert pnl_attribution_dollar_delta.labels(book_id="desk-x")._value.get() == 50_000.0

    def test_dollar_gamma_gauge_accepts_book_id(self):
        pnl_attribution_dollar_gamma.labels(book_id="desk-x").set(75_000.0)
        assert pnl_attribution_dollar_gamma.labels(book_id="desk-x")._value.get() == 75_000.0

    def test_record_sets_every_greek_pnl_gauge_from_result(self):
        greeks = PositionGreeks(delta=100.0, gamma=4.0, vega=2500.0, theta=-120.0, rho=8000.0, spot=200.0)
        move = MarketMove(price_change=5.0, vol_change=0.02, time_change_days=1.0, rate_change=0.0001)
        result = decompose_greek_pnl(greeks, move)

        record_greek_pnl_attribution(result, book_id="desk-decomp")

        for greek in ("delta", "gamma", "vega", "theta", "rho"):
            recorded = pnl_attribution_greek_pnl.labels(
                book_id="desk-decomp", greek=greek,
            )._value.get()
            expected = getattr(result, f"{greek}_pnl")
            assert recorded == pytest.approx(expected)

    def test_record_sets_dollar_delta_and_dollar_gamma_gauges(self):
        greeks = PositionGreeks(delta=100.0, gamma=4.0, vega=0.0, theta=0.0, rho=0.0, spot=200.0)
        move = MarketMove(price_change=0.0, vol_change=0.0, time_change_days=0.0, rate_change=0.0)
        result = decompose_greek_pnl(greeks, move)

        record_greek_pnl_attribution(result, book_id="desk-dollar")

        assert pnl_attribution_dollar_delta.labels(
            book_id="desk-dollar",
        )._value.get() == pytest.approx(result.dollar_delta)
        assert pnl_attribution_dollar_gamma.labels(
            book_id="desk-dollar",
        )._value.get() == pytest.approx(result.dollar_gamma)

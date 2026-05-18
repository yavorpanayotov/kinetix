import pytest

from kinetix_risk.market_data_models import VolSurfacePoint, VolSurface, YieldCurveData
from kinetix_risk.market_data_consumer import consume_market_data


pytestmark = pytest.mark.unit


class TestVolSurfaceInterpolation:
    def test_vol_at_exact_point_returns_that_vol(self):
        surface = VolSurface(points=[
            VolSurfacePoint(strike=100.0, maturity_days=30, implied_vol=0.20),
            VolSurfacePoint(strike=100.0, maturity_days=60, implied_vol=0.22),
            VolSurfacePoint(strike=110.0, maturity_days=30, implied_vol=0.25),
            VolSurfacePoint(strike=110.0, maturity_days=60, implied_vol=0.27),
        ])
        assert surface.vol_at(100.0, 30) == pytest.approx(0.20)

    def test_vol_surface_provides_interpolated_vol(self):
        surface = VolSurface(points=[
            VolSurfacePoint(strike=100.0, maturity_days=30, implied_vol=0.20),
            VolSurfacePoint(strike=100.0, maturity_days=60, implied_vol=0.22),
            VolSurfacePoint(strike=110.0, maturity_days=30, implied_vol=0.25),
            VolSurfacePoint(strike=110.0, maturity_days=60, implied_vol=0.27),
        ])
        # Midpoint in both strike and maturity should bilinear-interpolate
        vol = surface.vol_at(105.0, 45)
        # Expected: average of all four corners for exact midpoint
        expected = (0.20 + 0.22 + 0.25 + 0.27) / 4.0
        assert vol == pytest.approx(expected, abs=1e-10)

    def test_vol_surface_extrapolates_to_nearest_for_out_of_range(self):
        surface = VolSurface(points=[
            VolSurfacePoint(strike=100.0, maturity_days=30, implied_vol=0.20),
            VolSurfacePoint(strike=110.0, maturity_days=60, implied_vol=0.27),
        ])
        # Below min strike and maturity, should clamp to nearest
        vol = surface.vol_at(90.0, 20)
        assert vol > 0.0

    def test_vol_surface_single_point(self):
        surface = VolSurface(points=[
            VolSurfacePoint(strike=100.0, maturity_days=30, implied_vol=0.20),
        ])
        assert surface.vol_at(100.0, 30) == pytest.approx(0.20)
        # Any query should return the single point
        assert surface.vol_at(200.0, 90) == pytest.approx(0.20)


class TestYieldCurveInterpolation:
    def test_yield_curve_provides_interpolated_rate(self):
        curve = YieldCurveData(tenors=[(30, 0.04), (90, 0.045), (180, 0.05)])
        # Midpoint between 30-day and 90-day tenors
        rate = curve.rate_at(60)
        expected = 0.04 + (0.045 - 0.04) * (60 - 30) / (90 - 30)
        assert rate == pytest.approx(expected, abs=1e-10)

    def test_yield_curve_exact_tenor_returns_that_rate(self):
        curve = YieldCurveData(tenors=[(30, 0.04), (90, 0.045)])
        assert curve.rate_at(30) == pytest.approx(0.04)
        assert curve.rate_at(90) == pytest.approx(0.045)

    def test_yield_curve_extrapolates_flat_below_min_tenor(self):
        curve = YieldCurveData(tenors=[(30, 0.04), (90, 0.045)])
        assert curve.rate_at(10) == pytest.approx(0.04)

    def test_yield_curve_extrapolates_flat_above_max_tenor(self):
        curve = YieldCurveData(tenors=[(30, 0.04), (90, 0.045)])
        assert curve.rate_at(365) == pytest.approx(0.045)


class TestMarketDataConsumerVolSurface:
    def test_consumes_volatility_surface_data(self):
        market_data = [
            {
                "data_type": "VOLATILITY_SURFACE",
                "instrument_id": "AAPL",
                "points": [
                    {"strike": 100.0, "maturity_days": 30, "implied_vol": 0.20},
                    {"strike": 100.0, "maturity_days": 60, "implied_vol": 0.22},
                    {"strike": 110.0, "maturity_days": 30, "implied_vol": 0.25},
                    {"strike": 110.0, "maturity_days": 60, "implied_vol": 0.27},
                ],
            }
        ]
        bundle = consume_market_data(market_data)
        assert "AAPL" in bundle.vol_surfaces
        surface = bundle.vol_surfaces["AAPL"]
        assert surface.vol_at(100.0, 30) == pytest.approx(0.20)


class TestMarketDataConsumerYieldCurve:
    def test_consumes_yield_curve_data(self):
        market_data = [
            {
                "data_type": "YIELD_CURVE",
                "currency": "USD",
                "tenors": [
                    {"days": 30, "rate": 0.04},
                    {"days": 90, "rate": 0.045},
                    {"days": 180, "rate": 0.05},
                ],
            }
        ]
        bundle = consume_market_data(market_data)
        assert "USD" in bundle.yield_curves
        curve = bundle.yield_curves["USD"]
        assert curve.rate_at(30) == pytest.approx(0.04)
        assert curve.rate_at(90) == pytest.approx(0.045)

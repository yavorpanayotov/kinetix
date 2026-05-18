import pytest

from kinetix_risk.market_data_models import YieldCurveData
from kinetix_risk.models import AssetClass, SwapPosition
from kinetix_risk.swap_pricing import swap_dv01, swap_pv


pytestmark = pytest.mark.unit


class TestSwapPricing:
    def _sample_swap(self):
        return SwapPosition(
            instrument_id="IRS-5Y",
            asset_class=AssetClass.DERIVATIVE,
            market_value=0.0,
            currency="USD",
            notional=10_000_000.0,
            fixed_rate=0.035,
            float_index="SOFR",
            float_spread=0.0,
            effective_date="2026-03-15",
            maturity_date="2031-03-15",
            pay_receive="PAY_FIXED",
        )

    def _flat_curve(self, rate=0.035):
        return YieldCurveData(tenors=[(365, rate), (1825, rate), (3650, rate)])

    def test_par_swap_near_zero_pv(self):
        swap = self._sample_swap()
        curve = self._flat_curve(rate=0.035)  # same as fixed rate
        pv = swap_pv(swap, curve)
        # At par, PV should be near zero
        assert abs(pv) < 50_000  # within 50k of zero for 10M notional

    def test_swap_pv_positive_when_rates_fall(self):
        swap = self._sample_swap()  # pays 3.5% fixed
        curve = self._flat_curve(rate=0.02)  # market rate fell to 2%
        pv = swap_pv(swap, curve)
        # Paying fixed 3.5% when market is 2% — bad for payer, PV should be negative
        assert pv < 0

    def test_swap_dv01_proportional_to_notional(self):
        swap_small = SwapPosition(
            instrument_id="IRS-5Y-S",
            asset_class=AssetClass.DERIVATIVE,
            market_value=0.0,
            currency="USD",
            notional=1_000_000.0,
            fixed_rate=0.035,
            float_index="SOFR",
            effective_date="2026-03-15",
            maturity_date="2031-03-15",
            pay_receive="PAY_FIXED",
        )
        swap_large = self._sample_swap()  # 10M notional
        curve = self._flat_curve()
        dv01_small = swap_dv01(swap_small, curve)
        dv01_large = swap_dv01(swap_large, curve)
        assert dv01_large > dv01_small
        assert abs(dv01_large / dv01_small - 10.0) < 1.0  # roughly 10x


class TestYieldCurveDataExtensions:
    def test_interpolate_delegates_to_rate_at(self):
        curve = YieldCurveData(tenors=[(365, 0.03), (730, 0.04)])
        assert curve.interpolate(365) == pytest.approx(0.03)
        assert curve.interpolate(730) == pytest.approx(0.04)

    def test_interpolate_midpoint(self):
        curve = YieldCurveData(tenors=[(0, 0.02), (1000, 0.04)])
        mid = curve.interpolate(500)
        assert mid == pytest.approx(0.03, abs=1e-6)

    def test_shift_returns_new_curve_with_all_rates_increased(self):
        curve = YieldCurveData(tenors=[(365, 0.03), (730, 0.04)])
        shifted = curve.shift(0.01)
        assert shifted.interpolate(365) == pytest.approx(0.04)
        assert shifted.interpolate(730) == pytest.approx(0.05)

    def test_shift_does_not_mutate_original(self):
        curve = YieldCurveData(tenors=[(365, 0.03)])
        _ = curve.shift(0.01)
        assert curve.interpolate(365) == pytest.approx(0.03)

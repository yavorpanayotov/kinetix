import pytest

from kinetix_risk.bond_pricing import bond_dv01, bond_modified_duration, bond_pv
from kinetix_risk.models import AssetClass, BondPosition


pytestmark = pytest.mark.unit


class TestBondPricing:
    def _par_bond(self):
        return BondPosition(
            instrument_id="UST-10Y",
            asset_class=AssetClass.FIXED_INCOME,
            market_value=1_000_000.0,
            currency="USD",
            face_value=1_000_000.0,
            coupon_rate=0.03,
            coupon_frequency=2,
            maturity_date="2036-03-15",
        )

    def test_zero_coupon_bond_pv(self):
        bond = BondPosition(
            instrument_id="ZCB",
            asset_class=AssetClass.FIXED_INCOME,
            market_value=900_000.0,
            currency="USD",
            face_value=1_000_000.0,
            coupon_rate=0.0,
            coupon_frequency=2,
            maturity_date="2036-03-15",
        )
        pv = bond_pv(bond, yield_rate=0.03)
        # PV of 1M discounted at 3% for ~10 years ≈ 744,093
        assert 700_000 < pv < 800_000

    def test_par_bond_pv_near_face_value(self):
        bond = self._par_bond()
        pv = bond_pv(bond, yield_rate=0.03)
        # At par yield, PV should be close to face value
        assert abs(pv - 1_000_000.0) < 5000

    def test_bond_dv01_is_positive(self):
        dv01 = bond_dv01(self._par_bond(), yield_rate=0.03)
        assert dv01 > 0

    def test_bond_modified_duration(self):
        md = bond_modified_duration(self._par_bond(), yield_rate=0.03)
        # 10-year bond should have duration roughly 8-9 years
        assert 6 < md < 12


class TestEffectiveDurationHelper:
    """bond_effective_duration() stitches caller-supplied bumped PVs into
    the standard formula. Pricer responsibility for option-aware bumps;
    this helper just verifies the algebra."""

    def test_basic_effective_duration_formula(self):
        from kinetix_risk.bond_pricing import bond_effective_duration

        # Linear: 1% bump moves price by 5% in each direction => ED = 5.
        ed = bond_effective_duration(
            pv_up=95.0,
            pv_down=105.0,
            pv_baseline=100.0,
            yield_bump=0.01,
        )
        assert ed == 5.0

    def test_zero_curvature_zero_duration(self):
        from kinetix_risk.bond_pricing import bond_effective_duration

        ed = bond_effective_duration(
            pv_up=100.0, pv_down=100.0, pv_baseline=100.0, yield_bump=0.01,
        )
        assert ed == 0.0

    def test_negative_baseline_raises(self):
        from kinetix_risk.bond_pricing import bond_effective_duration
        import pytest

        with pytest.raises(ValueError):
            bond_effective_duration(95.0, 105.0, pv_baseline=-1.0)

    def test_zero_baseline_raises(self):
        from kinetix_risk.bond_pricing import bond_effective_duration
        import pytest

        with pytest.raises(ValueError):
            bond_effective_duration(95.0, 105.0, pv_baseline=0.0)


class TestOptionAdjustedSpread:
    def test_oas_is_zero_when_market_matches_option_free(self):
        from kinetix_risk.bond_pricing import bond_option_adjusted_spread
        oas = bond_option_adjusted_spread(100.0, 100.0, yield_rate=0.03)
        assert oas == 0.0

    def test_oas_positive_when_market_below_option_free(self):
        from kinetix_risk.bond_pricing import bond_option_adjusted_spread
        oas = bond_option_adjusted_spread(98.0, 100.0, yield_rate=0.03)
        assert oas > 0

    def test_oas_negative_when_market_above_option_free(self):
        from kinetix_risk.bond_pricing import bond_option_adjusted_spread
        oas = bond_option_adjusted_spread(102.0, 100.0, yield_rate=0.03)
        assert oas < 0

    def test_oas_rejects_non_positive_market_price(self):
        from kinetix_risk.bond_pricing import bond_option_adjusted_spread
        import pytest
        with pytest.raises(ValueError):
            bond_option_adjusted_spread(0.0, 100.0, yield_rate=0.03)


class TestPortfolioDuration:
    def test_single_position_portfolio_duration_equals_its_duration(self):
        from kinetix_risk.bond_pricing import market_value_weighted_portfolio_duration
        assert market_value_weighted_portfolio_duration(
            durations=[7.5], market_values=[1_000_000.0],
        ) == 7.5

    def test_two_position_portfolio_duration_is_mv_weighted(self):
        from kinetix_risk.bond_pricing import market_value_weighted_portfolio_duration
        # 4-yr bond at $1M + 8-yr bond at $1M => weighted = 6.0
        d = market_value_weighted_portfolio_duration(
            durations=[4.0, 8.0], market_values=[1_000_000.0, 1_000_000.0],
        )
        assert d == 6.0

    def test_skewed_portfolio_duration(self):
        from kinetix_risk.bond_pricing import market_value_weighted_portfolio_duration
        # 4-yr bond at $1M + 8-yr bond at $9M => weighted = 7.6
        d = market_value_weighted_portfolio_duration(
            durations=[4.0, 8.0], market_values=[1_000_000.0, 9_000_000.0],
        )
        assert d == 7.6

    def test_length_mismatch_raises(self):
        from kinetix_risk.bond_pricing import market_value_weighted_portfolio_duration
        import pytest
        with pytest.raises(ValueError):
            market_value_weighted_portfolio_duration([4.0], [1.0, 2.0])

    def test_zero_total_mv_raises(self):
        from kinetix_risk.bond_pricing import market_value_weighted_portfolio_duration
        import pytest
        with pytest.raises(ValueError):
            market_value_weighted_portfolio_duration([4.0, 8.0], [0.0, 0.0])


class TestZSpread:
    @pytest.mark.unit
    def test_z_spread_recovers_zero_spread_when_market_matches_curve(self):
        """If the bond prices exactly at its zero-rate discount, the
        Z-spread should be 0."""
        from kinetix_risk.bond_pricing import bond_z_spread
        cash_flows = [(1.0, 5.0), (2.0, 5.0), (3.0, 105.0)]
        r = 0.05
        pv = sum(cf / (1 + r) ** t for t, cf in cash_flows)
        assert bond_z_spread(pv, cash_flows, base_zero_rate=r) == pytest.approx(0.0, abs=1e-6)

    @pytest.mark.unit
    def test_z_spread_positive_when_market_below_riskfree_pv(self):
        from kinetix_risk.bond_pricing import bond_z_spread
        cash_flows = [(1.0, 5.0), (2.0, 5.0), (3.0, 105.0)]
        r = 0.05
        rf_pv = sum(cf / (1 + r) ** t for t, cf in cash_flows)
        spread = bond_z_spread(rf_pv * 0.95, cash_flows, base_zero_rate=r)
        assert spread > 0

    @pytest.mark.unit
    def test_z_spread_negative_when_market_above_riskfree_pv(self):
        """Bond trades richer than the curve — implies negative spread
        (e.g. flight-to-quality premium)."""
        from kinetix_risk.bond_pricing import bond_z_spread
        cash_flows = [(1.0, 5.0), (2.0, 5.0), (3.0, 105.0)]
        r = 0.05
        rf_pv = sum(cf / (1 + r) ** t for t, cf in cash_flows)
        spread = bond_z_spread(rf_pv * 1.02, cash_flows, base_zero_rate=r)
        assert spread < 0

    @pytest.mark.unit
    def test_z_spread_rejects_empty_cash_flows(self):
        from kinetix_risk.bond_pricing import bond_z_spread
        with pytest.raises(ValueError):
            bond_z_spread(100.0, [], base_zero_rate=0.05)

    @pytest.mark.unit
    def test_z_spread_rejects_non_positive_market_price(self):
        from kinetix_risk.bond_pricing import bond_z_spread
        with pytest.raises(ValueError):
            bond_z_spread(0.0, [(1.0, 5.0)], base_zero_rate=0.05)


class TestKeyRateDuration:
    @pytest.mark.unit
    def test_basic_key_rate_duration_formula(self):
        from kinetix_risk.bond_pricing import bond_key_rate_duration
        krd = bond_key_rate_duration(
            pv_up=99.5, pv_down=100.5, pv_baseline=100.0, yield_bump=0.0001,
        )
        # (100.5 - 99.5) / (2 * 100 * 0.0001) = 1.0 / 0.02 = 50
        assert krd == pytest.approx(50.0)

    @pytest.mark.unit
    def test_zero_curvature_zero_key_rate_duration(self):
        from kinetix_risk.bond_pricing import bond_key_rate_duration
        krd = bond_key_rate_duration(100.0, 100.0, 100.0)
        assert krd == 0.0

    @pytest.mark.unit
    def test_negative_baseline_raises(self):
        from kinetix_risk.bond_pricing import bond_key_rate_duration
        with pytest.raises(ValueError):
            bond_key_rate_duration(99.5, 100.5, -1.0)

    @pytest.mark.unit
    def test_non_positive_bump_raises(self):
        from kinetix_risk.bond_pricing import bond_key_rate_duration
        with pytest.raises(ValueError):
            bond_key_rate_duration(99.5, 100.5, 100.0, yield_bump=0.0)

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

import numpy as np

from kinetix_risk.models import (
    AssetClass, AssetClassImpact, StressScenario, StressTestResult,
)

import pytest

pytestmark = pytest.mark.unit


class TestStressScenario:
    def test_scenario_has_required_fields(self):
        scenario = StressScenario(
            name="Test",
            description="A test scenario",
            vol_shocks={AssetClass.EQUITY: 2.0},
            correlation_override=None,
            price_shocks={AssetClass.EQUITY: 0.6},
        )
        assert scenario.name == "Test"
        assert scenario.description == "A test scenario"
        assert AssetClass.EQUITY in scenario.vol_shocks
        assert scenario.correlation_override is None
        assert AssetClass.EQUITY in scenario.price_shocks

    def test_vol_shocks_are_multiplicative(self):
        scenario = StressScenario(
            name="Vol test",
            description="Vol shocks should be multiplicative",
            vol_shocks={AssetClass.EQUITY: 3.0, AssetClass.COMMODITY: 2.0},
            correlation_override=None,
            price_shocks={},
        )
        # 3.0 means triple the vol, 1.0 means no change
        assert scenario.vol_shocks[AssetClass.EQUITY] == 3.0
        assert scenario.vol_shocks[AssetClass.COMMODITY] == 2.0

    def test_price_shocks_applied_to_exposures(self):
        scenario = StressScenario(
            name="Price test",
            description="Price shocks should be multiplicative",
            vol_shocks={},
            correlation_override=None,
            price_shocks={AssetClass.EQUITY: 0.6, AssetClass.COMMODITY: 0.7},
        )
        base_equity = 1_000_000.0
        base_commodity = 500_000.0
        stressed_equity = base_equity * scenario.price_shocks[AssetClass.EQUITY]
        stressed_commodity = base_commodity * scenario.price_shocks[AssetClass.COMMODITY]
        assert stressed_equity == 600_000.0
        assert stressed_commodity == 350_000.0


class TestStressTestResult:
    def test_result_has_all_fields(self):
        impacts = [
            AssetClassImpact(AssetClass.EQUITY, 1_000_000.0, 600_000.0, -400_000.0),
            AssetClassImpact(AssetClass.COMMODITY, 500_000.0, 350_000.0, -150_000.0),
        ]
        result = StressTestResult(
            scenario_name="GFC_2008",
            base_var=100_000.0,
            stressed_var=300_000.0,
            pnl_impact=-550_000.0,
            asset_class_impacts=impacts,
        )
        assert result.scenario_name == "GFC_2008"
        assert result.base_var == 100_000.0
        assert result.stressed_var == 300_000.0
        assert result.pnl_impact == -550_000.0
        assert len(result.asset_class_impacts) == 2

    def test_pnl_impact_is_stressed_minus_base(self):
        impacts = [
            AssetClassImpact(AssetClass.EQUITY, 1_000_000.0, 600_000.0, -400_000.0),
        ]
        base_exposure = sum(i.base_exposure for i in impacts)
        stressed_exposure = sum(i.stressed_exposure for i in impacts)
        pnl_impact = stressed_exposure - base_exposure
        assert pnl_impact == -400_000.0
        result = StressTestResult(
            scenario_name="test",
            base_var=100_000.0,
            stressed_var=200_000.0,
            pnl_impact=pnl_impact,
            asset_class_impacts=impacts,
        )
        assert result.pnl_impact == stressed_exposure - base_exposure

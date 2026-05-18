from kinetix_risk.models import (
    DrcResult,
    FrtbResult,
    FrtbRiskClass,
    RiskClassCharge,
    RraoResult,
    SbmResult,
    SensitivityInput,
)

import pytest

pytestmark = pytest.mark.unit


class TestFrtbRiskClass:
    def test_seven_risk_classes_exist(self):
        assert len(FrtbRiskClass) == 7

    def test_risk_class_values_match_names(self):
        for rc in FrtbRiskClass:
            assert rc.value == rc.name


class TestSensitivityInput:
    def test_has_required_fields(self):
        si = SensitivityInput(
            risk_class=FrtbRiskClass.EQUITY,
            delta=100.0,
            vega=50.0,
            curvature=10.0,
        )
        assert si.risk_class == FrtbRiskClass.EQUITY
        assert si.delta == 100.0
        assert si.vega == 50.0
        assert si.curvature == 10.0


class TestFrtbResult:
    def test_total_is_sum_of_components(self):
        sbm = SbmResult(risk_class_charges=[], total_sbm_charge=100.0)
        drc = DrcResult(gross_jtd=50.0, hedge_benefit=10.0, net_drc=40.0)
        rrao = RraoResult(exotic_notional=1000.0, other_notional=500.0, total_rrao=15.0)
        result = FrtbResult(
            book_id="port-1",
            sbm=sbm,
            drc=drc,
            rrao=rrao,
            total_capital_charge=155.0,
        )
        assert result.total_capital_charge == result.sbm.total_sbm_charge + result.drc.net_drc + result.rrao.total_rrao

    def test_result_has_all_sections(self):
        sbm = SbmResult(
            risk_class_charges=[
                RiskClassCharge(
                    risk_class=FrtbRiskClass.EQUITY,
                    delta_charge=10.0,
                    vega_charge=5.0,
                    curvature_charge=1.0,
                    total_charge=16.0,
                ),
            ],
            total_sbm_charge=16.0,
        )
        drc = DrcResult(gross_jtd=20.0, hedge_benefit=5.0, net_drc=15.0)
        rrao = RraoResult(exotic_notional=100.0, other_notional=50.0, total_rrao=1.05)
        result = FrtbResult(
            book_id="port-1",
            sbm=sbm,
            drc=drc,
            rrao=rrao,
            total_capital_charge=32.05,
        )
        assert result.sbm is not None
        assert result.drc is not None
        assert result.rrao is not None
        assert result.book_id == "port-1"

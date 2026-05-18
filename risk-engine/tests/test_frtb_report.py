import csv
import io
import xml.etree.ElementTree as ET

from kinetix_risk.frtb.report import generate_csv_report, generate_xbrl_report
from kinetix_risk.models import (
    DrcResult,
    FrtbResult,
    FrtbRiskClass,
    RiskClassCharge,
    RraoResult,
    SbmResult,
)

import pytest

pytestmark = pytest.mark.unit


def _sample_frtb_result() -> FrtbResult:
    charges = [
        RiskClassCharge(rc, 100.0, 50.0, 10.0, 160.0)
        for rc in FrtbRiskClass
    ]
    return FrtbResult(
        book_id="port-1",
        sbm=SbmResult(risk_class_charges=charges, total_sbm_charge=1120.0),
        drc=DrcResult(gross_jtd=500.0, hedge_benefit=50.0, net_drc=450.0),
        rrao=RraoResult(exotic_notional=10000.0, other_notional=5000.0, total_rrao=105.0),
        total_capital_charge=1675.0,
    )


class TestCsvReport:
    def test_csv_has_header_row(self):
        content = generate_csv_report(_sample_frtb_result())
        lines = content.strip().split("\n")
        assert "Component" in lines[0]
        assert "Risk Class" in lines[0]

    def test_csv_has_seven_risk_class_rows(self):
        content = generate_csv_report(_sample_frtb_result())
        reader = csv.reader(io.StringIO(content))
        rows = list(reader)
        sbm_rows = [r for r in rows if r[0] == "SbM"]
        assert len(sbm_rows) == 7

    def test_csv_has_drc_row(self):
        content = generate_csv_report(_sample_frtb_result())
        reader = csv.reader(io.StringIO(content))
        rows = list(reader)
        drc_rows = [r for r in rows if r[0] == "DRC"]
        assert len(drc_rows) == 1

    def test_csv_has_total_row(self):
        content = generate_csv_report(_sample_frtb_result())
        reader = csv.reader(io.StringIO(content))
        rows = list(reader)
        total_rows = [r for r in rows if r[0] == "Total"]
        assert len(total_rows) == 1
        assert "1675.00" in total_rows[0][-1]

    def test_csv_parseable(self):
        content = generate_csv_report(_sample_frtb_result())
        reader = csv.reader(io.StringIO(content))
        rows = list(reader)
        # Header + 7 SbM + DRC + RRAO + Total = 11
        assert len(rows) == 11


class TestXbrlReport:
    def test_xbrl_is_valid_xml(self):
        content = generate_xbrl_report(_sample_frtb_result())
        root = ET.fromstring(content)
        assert root is not None

    def test_xbrl_has_root_element(self):
        content = generate_xbrl_report(_sample_frtb_result())
        root = ET.fromstring(content)
        assert root.tag == "FRTBReport"

    def test_xbrl_has_sbm_section(self):
        content = generate_xbrl_report(_sample_frtb_result())
        root = ET.fromstring(content)
        sbm = root.find("SensitivitiesBasedMethod")
        assert sbm is not None
        rc_charges = sbm.findall("RiskClassCharge")
        assert len(rc_charges) == 7

    def test_xbrl_has_drc_section(self):
        content = generate_xbrl_report(_sample_frtb_result())
        root = ET.fromstring(content)
        drc = root.find("DefaultRiskCharge")
        assert drc is not None
        assert drc.find("NetDRC") is not None

    def test_xbrl_has_total_capital_charge(self):
        content = generate_xbrl_report(_sample_frtb_result())
        root = ET.fromstring(content)
        total = root.find("TotalCapitalCharge")
        assert total is not None
        assert total.text == "1675.00"

import pytest

from kinetix_risk.frtb.credit_quality import (
    CREDIT_QUALITY_DEFAULT_PROBS,
    SENIORITY_LGD,
    CreditRating,
    Seniority,
    maturity_weight,
)
from kinetix_risk.frtb.drc_enhanced import calculate_enhanced_drc
from kinetix_risk.models import AssetClass, CreditPositionRisk, EnhancedDrcResult, PositionRisk


pytestmark = pytest.mark.unit


class TestCreditQualityDefaultProbabilities:
    def test_credit_quality_step_returns_correct_default_prob(self):
        expected = {
            CreditRating.AAA: 0.0003,
            CreditRating.AA_PLUS: 0.0005,
            CreditRating.AA: 0.0007,
            CreditRating.AA_MINUS: 0.001,
            CreditRating.A_PLUS: 0.0015,
            CreditRating.A: 0.002,
            CreditRating.A_MINUS: 0.003,
            CreditRating.BBB_PLUS: 0.005,
            CreditRating.BBB: 0.007,
            CreditRating.BBB_MINUS: 0.01,
            CreditRating.BB_PLUS: 0.015,
            CreditRating.BB: 0.02,
            CreditRating.BB_MINUS: 0.03,
            CreditRating.B_PLUS: 0.04,
            CreditRating.B: 0.06,
            CreditRating.B_MINUS: 0.10,
            CreditRating.CCC: 0.15,
            CreditRating.CC: 0.30,
            CreditRating.C: 0.50,
            CreditRating.D: 1.0,
            CreditRating.UNRATED: 0.015,
        }
        for rating, prob in expected.items():
            assert CREDIT_QUALITY_DEFAULT_PROBS[rating] == pytest.approx(prob), (
                f"Default probability for {rating.value} should be {prob}"
            )

    def test_all_ratings_have_default_probabilities(self):
        for rating in CreditRating:
            assert rating in CREDIT_QUALITY_DEFAULT_PROBS, (
                f"Missing default probability for {rating.value}"
            )


class TestSeniorityLgd:
    def test_senior_secured_lgd_is_25_percent(self):
        assert SENIORITY_LGD[Seniority.SENIOR_SECURED] == pytest.approx(0.25)

    def test_senior_unsecured_lgd_is_45_percent(self):
        assert SENIORITY_LGD[Seniority.SENIOR_UNSECURED] == pytest.approx(0.45)

    def test_subordinated_lgd_is_75_percent(self):
        assert SENIORITY_LGD[Seniority.SUBORDINATED] == pytest.approx(0.75)

    def test_equity_lgd_is_100_percent(self):
        assert SENIORITY_LGD[Seniority.EQUITY] == pytest.approx(1.0)


class TestMaturityWeight:
    def test_maturity_weight_increases_with_tenor(self):
        w1 = maturity_weight(1.0)
        w3 = maturity_weight(3.0)
        w5 = maturity_weight(5.0)
        assert w1 < w3 < w5

    def test_maturity_weight_has_floor_at_0_25(self):
        assert maturity_weight(0.0) == pytest.approx(0.25)
        assert maturity_weight(0.5) == pytest.approx(0.25)

    def test_maturity_weight_caps_at_1_0(self):
        assert maturity_weight(5.0) == pytest.approx(1.0)
        assert maturity_weight(10.0) == pytest.approx(1.0)

    def test_maturity_weight_scales_linearly(self):
        # maturity_weight = max(0.25, min(maturity / 5, 1.0))
        assert maturity_weight(2.5) == pytest.approx(0.5)


class TestEnhancedDrcJtdCalculation:
    def test_jtd_uses_maturity_adjusted_formula(self):
        positions = [
            CreditPositionRisk(
                instrument_id="BOND_A",
                asset_class=AssetClass.FIXED_INCOME,
                market_value=1_000_000.0,
                currency="USD",
                credit_rating="BBB",
                seniority="SENIOR_UNSECURED",
                maturity_years=5.0,
                sector="FINANCIALS",
            ),
        ]
        result = calculate_enhanced_drc(positions)
        # JTD = |1_000_000| * 0.007 (BBB) * 0.45 (senior unsecured) * 1.0 (5yr maturity weight)
        expected_jtd = 1_000_000.0 * 0.007 * 0.45 * 1.0
        assert result.gross_jtd == pytest.approx(expected_jtd)

    def test_jtd_with_short_maturity_reduces_charge(self):
        long_maturity = [
            CreditPositionRisk(
                instrument_id="BOND_A",
                asset_class=AssetClass.FIXED_INCOME,
                market_value=1_000_000.0,
                currency="USD",
                credit_rating="BBB",
                seniority="SENIOR_UNSECURED",
                maturity_years=5.0,
                sector="FINANCIALS",
            ),
        ]
        short_maturity = [
            CreditPositionRisk(
                instrument_id="BOND_B",
                asset_class=AssetClass.FIXED_INCOME,
                market_value=1_000_000.0,
                currency="USD",
                credit_rating="BBB",
                seniority="SENIOR_UNSECURED",
                maturity_years=1.0,
                sector="FINANCIALS",
            ),
        ]
        result_long = calculate_enhanced_drc(long_maturity)
        result_short = calculate_enhanced_drc(short_maturity)
        assert result_short.gross_jtd < result_long.gross_jtd


class TestSectorConcentration:
    def test_sector_concentration_charge_applied(self):
        # One sector dominates (>50% of gross JTD), surcharge is applied
        positions = [
            CreditPositionRisk(
                instrument_id="BOND_A",
                asset_class=AssetClass.FIXED_INCOME,
                market_value=900_000.0,
                currency="USD",
                credit_rating="BBB",
                seniority="SENIOR_UNSECURED",
                maturity_years=5.0,
                sector="FINANCIALS",
            ),
            CreditPositionRisk(
                instrument_id="BOND_B",
                asset_class=AssetClass.FIXED_INCOME,
                market_value=100_000.0,
                currency="USD",
                credit_rating="BBB",
                seniority="SENIOR_UNSECURED",
                maturity_years=5.0,
                sector="ENERGY",
            ),
        ]
        result = calculate_enhanced_drc(positions)
        assert result.sector_concentration_charge > 0

    def test_no_sector_concentration_when_balanced(self):
        # Two equal sectors, neither exceeds 50%
        positions = [
            CreditPositionRisk(
                instrument_id="BOND_A",
                asset_class=AssetClass.FIXED_INCOME,
                market_value=500_000.0,
                currency="USD",
                credit_rating="BBB",
                seniority="SENIOR_UNSECURED",
                maturity_years=5.0,
                sector="FINANCIALS",
            ),
            CreditPositionRisk(
                instrument_id="BOND_B",
                asset_class=AssetClass.FIXED_INCOME,
                market_value=500_000.0,
                currency="USD",
                credit_rating="BBB",
                seniority="SENIOR_UNSECURED",
                maturity_years=5.0,
                sector="ENERGY",
            ),
        ]
        result = calculate_enhanced_drc(positions)
        assert result.sector_concentration_charge == pytest.approx(0.0)


class TestEnhancedDrcBackwardCompatibility:
    def test_enhanced_drc_backward_compatible_with_basic(self):
        # Plain PositionRisk (non-Credit) should fall back to basic DRC behavior
        positions = [
            PositionRisk("US10Y", AssetClass.FIXED_INCOME, 1_000_000.0, "USD"),
        ]
        result = calculate_enhanced_drc(positions)
        assert isinstance(result, EnhancedDrcResult)
        assert result.net_drc > 0
        assert result.gross_jtd > 0


class TestEnhancedDrcMixedPortfolio:
    def test_enhanced_drc_with_mixed_seniority_positions(self):
        positions = [
            CreditPositionRisk(
                instrument_id="BOND_SENIOR",
                asset_class=AssetClass.FIXED_INCOME,
                market_value=1_000_000.0,
                currency="USD",
                credit_rating="A",
                seniority="SENIOR_SECURED",
                maturity_years=3.0,
                sector="FINANCIALS",
            ),
            CreditPositionRisk(
                instrument_id="BOND_SUB",
                asset_class=AssetClass.FIXED_INCOME,
                market_value=1_000_000.0,
                currency="USD",
                credit_rating="A",
                seniority="SUBORDINATED",
                maturity_years=3.0,
                sector="FINANCIALS",
            ),
        ]
        result = calculate_enhanced_drc(positions)
        # Subordinated should contribute more JTD due to higher LGD
        assert result.jtd_by_seniority["SUBORDINATED"] > result.jtd_by_seniority["SENIOR_SECURED"]

    def test_jtd_by_rating_is_populated(self):
        positions = [
            CreditPositionRisk(
                instrument_id="BOND_A",
                asset_class=AssetClass.FIXED_INCOME,
                market_value=1_000_000.0,
                currency="USD",
                credit_rating="AAA",
                seniority="SENIOR_UNSECURED",
                maturity_years=5.0,
                sector="FINANCIALS",
            ),
            CreditPositionRisk(
                instrument_id="BOND_B",
                asset_class=AssetClass.FIXED_INCOME,
                market_value=1_000_000.0,
                currency="USD",
                credit_rating="BBB",
                seniority="SENIOR_UNSECURED",
                maturity_years=5.0,
                sector="ENERGY",
            ),
        ]
        result = calculate_enhanced_drc(positions)
        assert "AAA" in result.jtd_by_rating
        assert "BBB" in result.jtd_by_rating
        # BBB has higher default probability than AAA
        assert result.jtd_by_rating["BBB"] > result.jtd_by_rating["AAA"]

    def test_enhanced_drc_net_includes_sector_concentration(self):
        # net_drc = gross_jtd - hedge_benefit + sector_concentration_charge
        positions = [
            CreditPositionRisk(
                instrument_id="BOND_A",
                asset_class=AssetClass.FIXED_INCOME,
                market_value=1_000_000.0,
                currency="USD",
                credit_rating="BBB",
                seniority="SENIOR_UNSECURED",
                maturity_years=5.0,
                sector="FINANCIALS",
            ),
        ]
        result = calculate_enhanced_drc(positions)
        expected_net = result.gross_jtd - result.hedge_benefit + result.sector_concentration_charge
        assert result.net_drc == pytest.approx(expected_net)

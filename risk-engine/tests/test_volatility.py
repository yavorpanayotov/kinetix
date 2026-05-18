import numpy as np
import pytest

from kinetix_risk.models import AssetClass
from kinetix_risk.volatility import get_volatility, get_correlation_matrix, get_sub_correlation_matrix


pytestmark = pytest.mark.unit


class TestDefaultVolatilities:
    def test_equity_volatility(self):
        assert get_volatility(AssetClass.EQUITY) == pytest.approx(0.20)

    def test_fixed_income_volatility(self):
        assert get_volatility(AssetClass.FIXED_INCOME) == pytest.approx(0.06)

    def test_fx_volatility(self):
        assert get_volatility(AssetClass.FX) == pytest.approx(0.10)

    def test_commodity_volatility(self):
        assert get_volatility(AssetClass.COMMODITY) == pytest.approx(0.25)

    def test_derivative_volatility(self):
        assert get_volatility(AssetClass.DERIVATIVE) == pytest.approx(0.30)

    def test_all_asset_classes_have_volatility(self):
        for ac in AssetClass:
            assert get_volatility(ac) > 0


class TestCorrelationMatrix:
    def test_full_matrix_is_symmetric(self):
        m = get_correlation_matrix()
        np.testing.assert_array_almost_equal(m, m.T)

    def test_full_matrix_diagonal_is_ones(self):
        m = get_correlation_matrix()
        np.testing.assert_array_almost_equal(np.diag(m), np.ones(5))

    def test_full_matrix_is_positive_definite(self):
        m = get_correlation_matrix()
        eigenvalues = np.linalg.eigvalsh(m)
        assert all(ev > 0 for ev in eigenvalues)

    def test_sub_matrix_for_single_asset_class(self):
        sub = get_sub_correlation_matrix([AssetClass.EQUITY])
        assert sub.shape == (1, 1)
        assert sub[0, 0] == pytest.approx(1.0)

    def test_sub_matrix_for_two_asset_classes(self):
        sub = get_sub_correlation_matrix([AssetClass.EQUITY, AssetClass.FIXED_INCOME])
        assert sub.shape == (2, 2)
        assert sub[0, 1] == pytest.approx(-0.20)
        assert sub[1, 0] == pytest.approx(-0.20)

    def test_sub_matrix_preserves_positive_definiteness(self):
        sub = get_sub_correlation_matrix(
            [AssetClass.EQUITY, AssetClass.COMMODITY, AssetClass.FX]
        )
        eigenvalues = np.linalg.eigvalsh(sub)
        assert all(ev > 0 for ev in eigenvalues)

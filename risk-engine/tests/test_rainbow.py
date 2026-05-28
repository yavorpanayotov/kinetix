"""Tests for the best-of-two rainbow option pricer."""

import pytest

from kinetix_risk.rainbow import best_of_two_call_price


@pytest.mark.unit
def test_expired_option_pays_intrinsic_max():
    """At T=0 the option pays max(max(S1, S2) - K, 0)."""
    p = best_of_two_call_price(
        spot1=110.0, spot2=120.0, strike=100.0,
        time_to_expiry_years=0.0, risk_free_rate=0.03,
        vol1=0.20, vol2=0.20, correlation=0.0,
    )
    assert p == 20.0


@pytest.mark.unit
def test_expired_out_of_money_is_zero():
    p = best_of_two_call_price(
        spot1=80.0, spot2=90.0, strike=100.0,
        time_to_expiry_years=0.0, risk_free_rate=0.03,
        vol1=0.20, vol2=0.20, correlation=0.0,
    )
    assert p == 0.0


@pytest.mark.unit
def test_best_of_is_at_least_as_valuable_as_each_single_call():
    """The best-of-two should always be at least the more-valuable
    single-asset call. Sanity check: zero correlation."""
    from kinetix_risk.rainbow import _bs_call
    spot1, spot2, K, T, r = 100.0, 100.0, 100.0, 0.5, 0.03
    vol1, vol2 = 0.20, 0.25
    c1 = _bs_call(spot1, K, T, r, vol1)
    c2 = _bs_call(spot2, K, T, r, vol2)
    p = best_of_two_call_price(spot1, spot2, K, T, r, vol1, vol2, correlation=0.0)
    assert p >= max(c1, c2)


@pytest.mark.unit
def test_rho_plus_one_approaches_single_call():
    """At rho = +1 the assets move together so best-of-two ~= max(C1, C2)."""
    spot1, spot2, K, T, r = 100.0, 100.0, 100.0, 0.5, 0.03
    vol1, vol2 = 0.20, 0.25
    p = best_of_two_call_price(spot1, spot2, K, T, r, vol1, vol2, correlation=1.0)
    from kinetix_risk.rainbow import _bs_call
    c2 = _bs_call(spot2, K, T, r, vol2)  # higher vol => more valuable
    assert abs(p - c2) < 1e-9


@pytest.mark.unit
def test_rho_minus_one_approaches_sum_of_calls():
    """At rho = -1 the assets diverge so best-of approaches the sum."""
    spot1, spot2, K, T, r = 100.0, 100.0, 100.0, 0.5, 0.03
    vol1, vol2 = 0.20, 0.20
    p = best_of_two_call_price(spot1, spot2, K, T, r, vol1, vol2, correlation=-1.0)
    from kinetix_risk.rainbow import _bs_call
    c_sum = _bs_call(spot1, K, T, r, vol1) + _bs_call(spot2, K, T, r, vol2)
    assert abs(p - c_sum) < 1e-9


@pytest.mark.unit
def test_invalid_correlation_raises():
    with pytest.raises(ValueError):
        best_of_two_call_price(100.0, 100.0, 100.0, 0.5, 0.03, 0.2, 0.2, correlation=1.01)

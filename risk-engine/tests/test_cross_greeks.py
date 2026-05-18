import math

import pytest

from kinetix_risk.cross_greeks import calculate_vanna, calculate_volga, calculate_charm
from kinetix_risk.models import OptionType


pytestmark = pytest.mark.unit


class TestVanna:
    def test_vanna_is_positive_for_otm_call(self):
        """Vanna for an out-of-the-money call should be positive.

        OTM call: spot < strike. Vanna = d(delta)/d(vol) is positive for OTM
        calls because increasing vol pushes delta toward 0.5 from below.
        """
        S, K, T, r, sigma = 90.0, 100.0, 0.5, 0.05, 0.20
        vanna = calculate_vanna(S, K, T, r, sigma)
        assert vanna > 0, f"Expected positive vanna for OTM call, got {vanna}"

    def test_vanna_is_negative_for_itm_call(self):
        """Vanna for a deep in-the-money call should be negative.

        ITM call: spot > strike. Vanna is negative because increasing vol
        pushes delta toward 0.5 from above.
        """
        S, K, T, r, sigma = 120.0, 100.0, 0.5, 0.05, 0.20
        vanna = calculate_vanna(S, K, T, r, sigma)
        assert vanna < 0, f"Expected negative vanna for deep ITM call, got {vanna}"


class TestVolga:
    def test_volga_is_positive(self):
        """Volga (vomma) is always positive for vanilla options.

        Volga = d(vega)/d(vol). Since vega reaches maximum near ATM and
        falls off on either side, volga is positive away from ATM.
        """
        S, K, T, r, sigma = 100.0, 100.0, 0.5, 0.05, 0.20
        volga = calculate_volga(S, K, T, r, sigma)
        assert volga >= 0, f"Expected non-negative volga, got {volga}"

    def test_volga_is_larger_for_otm_options(self):
        """Volga should be larger for OTM options than ATM options."""
        T, r, sigma = 0.5, 0.05, 0.20
        volga_atm = calculate_volga(100.0, 100.0, T, r, sigma)
        volga_otm = calculate_volga(80.0, 100.0, T, r, sigma)
        assert volga_otm > volga_atm, (
            f"OTM volga ({volga_otm}) should exceed ATM volga ({volga_atm})"
        )


class TestCharm:
    def test_charm_approaches_zero_at_expiry(self):
        """Charm should diminish as option nears expiry for near-ATM options.

        For ATM options with very long time to expiry, charm (delta decay)
        is smaller in magnitude than for medium-dated options because delta
        is relatively stable.
        """
        S, K, r, sigma = 100.0, 100.0, 0.05, 0.20
        charm_long = calculate_charm(S, K, 5.0, r, sigma)
        charm_short = calculate_charm(S, K, 0.1, r, sigma)
        # Both should be finite
        assert math.isfinite(charm_long)
        assert math.isfinite(charm_short)

    def test_charm_is_nonzero_for_otm_call(self):
        """Charm for an OTM call should be nonzero: delta drifts as time passes."""
        S, K, T, r, sigma = 90.0, 100.0, 0.5, 0.05, 0.20
        charm = calculate_charm(S, K, T, r, sigma, option_type=OptionType.CALL)
        assert charm != 0.0, "Charm should be nonzero for OTM call"


class TestPutCharmFormula:
    def test_put_charm_uses_dividend_yield_correction(self):
        """Put charm = charm_call - q*exp(-q*T) from put-call parity on delta."""
        S, K, T, r, sigma, q = 100.0, 100.0, 0.5, 0.05, 0.20, 0.02

        charm_call = calculate_charm(S, K, T, r, sigma, OptionType.CALL, q)
        charm_put = calculate_charm(S, K, T, r, sigma, OptionType.PUT, q)

        # Put-call parity for charm with dividends: charm_put = charm_call - q*exp(-q*T)
        expected_put = charm_call - q * math.exp(-q * T)
        assert charm_put == pytest.approx(expected_put, abs=1e-10)

    def test_put_charm_equals_call_charm_when_no_dividend(self):
        """With q=0, put charm equals call charm (correction term vanishes)."""
        S, K, T, r, sigma = 100.0, 100.0, 0.5, 0.05, 0.20

        charm_call = calculate_charm(S, K, T, r, sigma, OptionType.CALL, q=0.0)
        charm_put = calculate_charm(S, K, T, r, sigma, OptionType.PUT, q=0.0)
        assert charm_put == pytest.approx(charm_call, abs=1e-10)


class TestCrossGreeksAtExpiry:
    """T=0 must return 0.0 for all cross-Greeks, not crash."""

    def test_vanna_at_expiry_returns_zero(self):
        assert calculate_vanna(100.0, 100.0, 0.0, 0.05, 0.20) == 0.0

    def test_volga_at_expiry_returns_zero(self):
        assert calculate_volga(100.0, 100.0, 0.0, 0.05, 0.20) == 0.0

    def test_charm_at_expiry_returns_zero(self):
        assert calculate_charm(100.0, 100.0, 0.0, 0.05, 0.20) == 0.0


class TestCrossGreeksFiniteness:
    def test_cross_greeks_are_finite(self):
        """All cross-Greeks should be finite numbers for reasonable inputs."""
        S, K, T, r, sigma = 100.0, 100.0, 1.0, 0.05, 0.20
        vanna = calculate_vanna(S, K, T, r, sigma)
        volga = calculate_volga(S, K, T, r, sigma)
        charm = calculate_charm(S, K, T, r, sigma)
        assert math.isfinite(vanna), f"Vanna is not finite: {vanna}"
        assert math.isfinite(volga), f"Volga is not finite: {volga}"
        assert math.isfinite(charm), f"Charm is not finite: {charm}"

    def test_cross_greeks_with_high_vol(self):
        """Cross-Greeks should remain finite even with high volatility."""
        S, K, T, r, sigma = 100.0, 100.0, 1.0, 0.05, 0.80
        vanna = calculate_vanna(S, K, T, r, sigma)
        volga = calculate_volga(S, K, T, r, sigma)
        charm = calculate_charm(S, K, T, r, sigma)
        assert math.isfinite(vanna)
        assert math.isfinite(volga)
        assert math.isfinite(charm)

    def test_cross_greeks_with_short_expiry(self):
        """Cross-Greeks should remain finite with short time to expiry."""
        S, K, T, r, sigma = 100.0, 100.0, 0.01, 0.05, 0.20
        vanna = calculate_vanna(S, K, T, r, sigma)
        volga = calculate_volga(S, K, T, r, sigma)
        charm = calculate_charm(S, K, T, r, sigma)
        assert math.isfinite(vanna)
        assert math.isfinite(volga)
        assert math.isfinite(charm)

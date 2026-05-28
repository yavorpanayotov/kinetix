"""Tests for the near-expiry gamma spike in Black-Scholes.

For an ATM option, gamma blows up as time-to-expiry shrinks — the
limiting case is a Dirac spike at the strike. This is the property
behind "gamma-scalping" P&L profiles in the last day of an option's
life. The test pins down that ATM gamma is materially larger when
expiry is close than when expiry is far.
"""

import pytest

from kinetix_risk.black_scholes import bs_gamma
from kinetix_risk.models import OptionPosition, OptionType, AssetClass


def _atm_option(expiry_days: int) -> OptionPosition:
    return OptionPosition(
        instrument_id="OPT-1",
        underlying_id="UND",
        option_type=OptionType.CALL,
        strike=100.0,
        expiry_days=expiry_days,
        spot_price=100.0,
        implied_vol=0.20,
        asset_class=AssetClass.EQUITY,
    )


@pytest.mark.unit
def test_gamma_spikes_as_expiry_approaches_zero():
    """ATM gamma at 1-day-to-expiry is much larger than at 90-day."""
    long_dated = bs_gamma(_atm_option(90))
    short_dated = bs_gamma(_atm_option(1))
    # The 1-day ATM gamma should be ~9x the 90-day ATM gamma (sqrt(90)).
    assert short_dated > long_dated * 5


@pytest.mark.unit
def test_gamma_strictly_positive_at_atm():
    assert bs_gamma(_atm_option(30)) > 0


@pytest.mark.unit
def test_gamma_decreases_monotonically_with_expiry_atm():
    """Strictly: gamma(7d) > gamma(30d) > gamma(90d) for ATM."""
    g_7 = bs_gamma(_atm_option(7))
    g_30 = bs_gamma(_atm_option(30))
    g_90 = bs_gamma(_atm_option(90))
    assert g_7 > g_30 > g_90

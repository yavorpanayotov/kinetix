"""Black-Scholes option pricing and Greeks.

References
----------
Hull, J. C. (2018). *Options, Futures, and Other Derivatives*
    (10th ed.). Pearson. — canonical reference for the Black-Scholes
    PDE derivation, the closed-form solutions for European calls and
    puts, and the standard Greeks (delta, gamma, vega, theta, rho).
Gatheral, J. (2006). *The Volatility Surface: A Practitioner's Guide*.
    Wiley. — covers the second-order Greeks (vanna, volga, charm) and
    the practitioner's use of the implied-vol surface.
Black, F., & Scholes, M. (1973). The Pricing of Options and Corporate
    Liabilities. *Journal of Political Economy*, 81(3), 637-654. —
    the original paper.
"""

import math

from scipy.stats import norm

from kinetix_risk.models import OptionPosition, OptionType


def _is_expired(option: OptionPosition) -> bool:
    return option.expiry_days <= 0


def _intrinsic_value(option: OptionPosition) -> float:
    if option.option_type == OptionType.CALL:
        return max(0.0, option.spot_price - option.strike)
    else:
        return max(0.0, option.strike - option.spot_price)


def _d1(option: OptionPosition) -> float:
    """Black-Scholes d1.

    .. math::

        d_1 = \\frac{\\ln(S/K) + (r - q + \\frac{1}{2}\\sigma^2) T}{\\sigma \\sqrt{T}}

    Derivation: starts from the Black-Scholes PDE under the risk-neutral
    measure with continuous dividend yield ``q``. The numerator is the
    expected log-moneyness of the underlying at expiry under the
    risk-neutral drift ``r - q + 0.5 σ²``; the denominator is the
    standard deviation of log-moneyness over horizon ``T``. ``d1``
    appears as the input to ``N(.)`` for the *delta* (= ``e^(-qT) N(d1)``
    for a call) and is the upper limit of the integral that gives the
    call's expected payoff.

    See Hull (2018) Chapter 13 for the full derivation.
    """
    S = option.spot_price
    K = option.strike
    r = option.risk_free_rate
    q = option.dividend_yield
    T = option.expiry_days / 365.0
    vol = option.implied_vol
    return (math.log(S / K) + (r - q + 0.5 * vol ** 2) * T) / (vol * math.sqrt(T))


def _d2(option: OptionPosition) -> float:
    """Black-Scholes d2 = d1 - σ√T.

    .. math::

        d_2 = d_1 - \\sigma \\sqrt{T}

    Derivation: ``d2`` is the input to ``N(.)`` for the *probability the
    option finishes in the money* under the risk-neutral measure
    (``N(d2)`` for a call). The relationship ``d2 = d1 - σ√T`` falls
    out of the change-of-numéraire that converts the asset-measure
    expectation in d1's role into the money-market-measure expectation
    in d2's role. Equivalently, d2 is the expected log-moneyness less
    the half-vol-squared "Itô correction" that gets baked into d1.
    """
    T = option.expiry_days / 365.0
    return _d1(option) - option.implied_vol * math.sqrt(T)


def bs_price(option: OptionPosition) -> float:
    if _is_expired(option):
        return _intrinsic_value(option)
    S = option.spot_price
    K = option.strike
    r = option.risk_free_rate
    q = option.dividend_yield
    T = option.expiry_days / 365.0
    d1 = _d1(option)
    d2 = _d2(option)
    if option.option_type == OptionType.CALL:
        return S * math.exp(-q * T) * norm.cdf(d1) - K * math.exp(-r * T) * norm.cdf(d2)
    else:
        return K * math.exp(-r * T) * norm.cdf(-d2) - S * math.exp(-q * T) * norm.cdf(-d1)


def bs_delta(option: OptionPosition) -> float:
    if _is_expired(option):
        if option.option_type == OptionType.CALL:
            return 1.0 if option.spot_price > option.strike else 0.0
        else:
            return -1.0 if option.spot_price < option.strike else 0.0
    q = option.dividend_yield
    T = option.expiry_days / 365.0
    d1 = _d1(option)
    if option.option_type == OptionType.CALL:
        return float(math.exp(-q * T) * norm.cdf(d1))
    else:
        return float(math.exp(-q * T) * (norm.cdf(d1) - 1.0))


def bs_gamma(option: OptionPosition) -> float:
    if _is_expired(option):
        return 0.0
    S = option.spot_price
    q = option.dividend_yield
    T = option.expiry_days / 365.0
    vol = option.implied_vol
    d1 = _d1(option)
    return float(math.exp(-q * T) * norm.pdf(d1) / (S * vol * math.sqrt(T)))


def bs_vega(option: OptionPosition) -> float:
    if _is_expired(option):
        return 0.0
    S = option.spot_price
    q = option.dividend_yield
    T = option.expiry_days / 365.0
    d1 = _d1(option)
    return float(S * math.exp(-q * T) * norm.pdf(d1) * math.sqrt(T))


def bs_theta(option: OptionPosition) -> float:
    if _is_expired(option):
        return 0.0
    S = option.spot_price
    K = option.strike
    r = option.risk_free_rate
    q = option.dividend_yield
    T = option.expiry_days / 365.0
    vol = option.implied_vol
    d1 = _d1(option)
    d2 = _d2(option)
    common = -(S * math.exp(-q * T) * norm.pdf(d1) * vol) / (2.0 * math.sqrt(T))
    if option.option_type == OptionType.CALL:
        return float(common + q * S * math.exp(-q * T) * norm.cdf(d1) - r * K * math.exp(-r * T) * norm.cdf(d2))
    else:
        return float(common - q * S * math.exp(-q * T) * norm.cdf(-d1) + r * K * math.exp(-r * T) * norm.cdf(-d2))


def bs_rho(option: OptionPosition) -> float:
    if _is_expired(option):
        return 0.0
    K = option.strike
    r = option.risk_free_rate
    T = option.expiry_days / 365.0
    d2 = _d2(option)
    if option.option_type == OptionType.CALL:
        return float(K * T * math.exp(-r * T) * norm.cdf(d2))
    else:
        return float(-K * T * math.exp(-r * T) * norm.cdf(-d2))


def bs_exact_rho(option: OptionPosition) -> float:
    """Closed-form rho, identical output to bs_rho.

    Exists as a named entry point for callers that want to make
    the closed-form-vs-bumped-approximation comparison explicit in
    their own code (the test file pins down that the closed form
    agrees with a numerical bump to high precision).
    """
    return bs_rho(option)


def bs_rho_numerical(option: OptionPosition, bump: float = 1e-4) -> float:
    """Numerical (centred-difference) rho — sensitivity of price to a
    1.0 (i.e. 100 percentage points) rate move, computed by bumping
    the rate up and down by [bump] and dividing by 2*bump.

    Used as the reference value in the bs_exact_rho-vs-numerical
    test; the closed form should agree to within a small numerical
    tolerance for any reasonable bump.
    """
    from dataclasses import replace
    up = replace(option, risk_free_rate=option.risk_free_rate + bump)
    down = replace(option, risk_free_rate=option.risk_free_rate - bump)
    return (bs_price(up) - bs_price(down)) / (2.0 * bump)


def bs_vanna(option: OptionPosition) -> float:
    from kinetix_risk.cross_greeks import calculate_vanna
    T = option.expiry_days / 365.0
    return calculate_vanna(option.spot_price, option.strike, T, option.risk_free_rate, option.implied_vol, option.dividend_yield)


def bs_vanna_put(option: OptionPosition) -> float:
    """Put-side vanna = d^2 Price / d sigma d spot.

    By put-call parity (the put-call price difference is independent
    of vol), vanna is identical for puts and calls — the second-order
    cross sensitivity has no parity offset. This named entry point
    delegates to bs_vanna().
    """
    return bs_vanna(option)


def bs_volga(option: OptionPosition) -> float:
    from kinetix_risk.cross_greeks import calculate_volga
    T = option.expiry_days / 365.0
    return calculate_volga(option.spot_price, option.strike, T, option.risk_free_rate, option.implied_vol, option.dividend_yield)


def bs_gamma_decay(option: OptionPosition, bump_days: int = 1) -> float:
    """Pure time decay of gamma — change in gamma over one day, holding
    spot/vol/rate fixed.

    Charm captures d(delta)/d(time); gamma_decay captures the analogous
    second-order quantity d(gamma)/d(time). It tells the trader how
    much their delta-hedge frequency will spike tomorrow vs today —
    short-gamma positions get progressively harder to manage as
    expiry approaches.

    Computed by bumping expiry by 1 day and re-evaluating gamma; not a
    closed form because gamma's time derivative crosses zero at the
    ATM-spike point and a finite-difference signal is more interpretable
    for the dashboard.
    """
    from dataclasses import replace
    if option.expiry_days <= bump_days:
        return 0.0
    bumped = replace(option, expiry_days=option.expiry_days - bump_days)
    return bs_gamma(bumped) - bs_gamma(option)


def bs_volga_put(option: OptionPosition) -> float:
    """Put-side volga = d^2 Price / d sigma^2.

    By put-call parity (price difference is linear in spot/strike,
    independent of vol), volga is the SAME for puts and calls.
    The function exists as a named entry point so callers reading
    "put volga" find it where they expect; internally it delegates
    to bs_volga().
    """
    return bs_volga(option)


def bs_charm(option: OptionPosition) -> float:
    from kinetix_risk.cross_greeks import calculate_charm
    T = option.expiry_days / 365.0
    return calculate_charm(option.spot_price, option.strike, T, option.risk_free_rate, option.implied_vol, option.option_type, option.dividend_yield)


def bs_greeks(option: OptionPosition) -> dict:
    return {
        "price": bs_price(option),
        "delta": bs_delta(option),
        "gamma": bs_gamma(option),
        "vega": bs_vega(option),
        "theta": bs_theta(option),
        "rho": bs_rho(option),
        "vanna": bs_vanna(option),
        "volga": bs_volga(option),
        "charm": bs_charm(option),
    }

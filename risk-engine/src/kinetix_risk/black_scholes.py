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
    S = option.spot_price
    K = option.strike
    r = option.risk_free_rate
    q = option.dividend_yield
    T = option.expiry_days / 365.0
    vol = option.implied_vol
    return (math.log(S / K) + (r - q + 0.5 * vol ** 2) * T) / (vol * math.sqrt(T))


def _d2(option: OptionPosition) -> float:
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


def bs_vanna(option: OptionPosition) -> float:
    from kinetix_risk.cross_greeks import calculate_vanna
    T = option.expiry_days / 365.0
    return calculate_vanna(option.spot_price, option.strike, T, option.risk_free_rate, option.implied_vol, option.dividend_yield)


def bs_volga(option: OptionPosition) -> float:
    from kinetix_risk.cross_greeks import calculate_volga
    T = option.expiry_days / 365.0
    return calculate_volga(option.spot_price, option.strike, T, option.risk_free_rate, option.implied_vol, option.dividend_yield)


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

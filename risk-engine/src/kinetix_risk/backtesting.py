import math

import numpy as np
from scipy.stats import chi2

from kinetix_risk.models import BacktestResult, TrafficLightZone


def run_backtest(
    daily_var_predictions: list[float],
    daily_pnl: list[float],
    confidence_level: float = 0.99,
) -> BacktestResult:
    if len(daily_var_predictions) != len(daily_pnl):
        raise ValueError("daily_var_predictions and daily_pnl must have the same length")
    if not daily_var_predictions:
        raise ValueError("Cannot run backtest on empty data")

    total_days = len(daily_var_predictions)
    expected_violation_rate = 1.0 - confidence_level

    violations = []
    for i in range(total_days):
        actual_loss = -daily_pnl[i]
        if actual_loss > daily_var_predictions[i]:
            violations.append({
                "day_index": i,
                "var_value": daily_var_predictions[i],
                "actual_pnl": daily_pnl[i],
            })

    violation_count = len(violations)
    violation_rate = violation_count / total_days if total_days > 0 else 0.0

    kupiec_stat, kupiec_pval = _kupiec_pof_test(
        total_days, violation_count, expected_violation_rate,
    )
    kupiec_passed = kupiec_pval > 0.05

    christoffersen_stat, christoffersen_pval = _christoffersen_independence_test(
        daily_var_predictions, daily_pnl,
    )
    christoffersen_passed = christoffersen_pval > 0.05

    zone = _traffic_light_zone(violation_count)

    return BacktestResult(
        total_days=total_days,
        violation_count=violation_count,
        violation_rate=violation_rate,
        expected_violation_rate=expected_violation_rate,
        kupiec_statistic=kupiec_stat,
        kupiec_p_value=kupiec_pval,
        kupiec_pass=kupiec_passed,
        christoffersen_statistic=christoffersen_stat,
        christoffersen_p_value=christoffersen_pval,
        christoffersen_pass=christoffersen_passed,
        traffic_light_zone=zone,
        violations=violations,
    )


def _kupiec_pof_test(
    total_days: int,
    violation_count: int,
    expected_rate: float,
) -> tuple[float, float]:
    """Kupiec (1995) proportion-of-failures (POF) test statistic.

    Tests the null hypothesis that the observed violation rate matches
    the model's *expected* violation rate (1 − confidence_level).
    Uses the likelihood-ratio statistic:

    .. math::

        LR_{POF} = -2 \\ln\\!\\left(
            \\frac{(1-p)^{T-n} \\, p^{n}}
                 {(1-\\hat p)^{T-n} \\, \\hat p^{n}}
        \\right)

    where ``T`` is total_days, ``n`` is violation_count, ``p`` is the
    expected violation rate, and ``\\hat p = n/T`` is the empirical
    rate. Under the null, ``LR_POF`` is asymptotically
    chi-squared with 1 degree of freedom; a p-value below 0.05
    rejects the model.

    Edge cases: when ``n = 0`` or ``n = T`` the empirical rate is
    clipped slightly away from 0/1 so the log-likelihood stays finite
    (those cases reliably fail the test on any reasonable expected
    rate, but the statistic must be a finite number for downstream
    reporting).

    Reference: Kupiec, P. (1995). Techniques for Verifying the
    Accuracy of Risk Measurement Models. *Journal of Derivatives*,
    3(2), 73-84.
    """
    n = violation_count
    t = total_days
    p = expected_rate

    if n == 0:
        observed_rate = 1e-10
    elif n == t:
        observed_rate = 1.0 - 1e-10
    else:
        observed_rate = n / t

    lr = -2.0 * (
        n * math.log(p) + (t - n) * math.log(1.0 - p)
        - n * math.log(observed_rate) - (t - n) * math.log(1.0 - observed_rate)
    )

    p_value = 1.0 - chi2.cdf(lr, df=1)
    return lr, float(p_value)


def _christoffersen_independence_test(
    daily_var_predictions: list[float],
    daily_pnl: list[float],
) -> tuple[float, float]:
    """Christoffersen (1998) test for independence of VaR violations.

    The Kupiec POF test only checks the *unconditional* violation
    rate — it accepts a model that prints the right number of
    violations but clusters them all in a single week. Christoffersen
    adds a Markov-chain conditional check: given that yesterday was a
    violation, what's the probability today is a violation? If the
    violations are truly independent, that probability equals the
    overall violation rate. If violations cluster, today's
    probability conditioned on yesterday's violation is much higher.

    Implementation: encode each day as an indicator (1 = violation,
    0 = clean), count the four transition pairs (n00, n01, n10, n11),
    fit a first-order Markov chain, and compare the likelihood under
    the conditional model to the likelihood under the i.i.d. model
    via a chi-squared LR statistic with 1 d.o.f.

    Reference: Christoffersen, P. F. (1998). Evaluating Interval
    Forecasts. *International Economic Review*, 39(4), 841-862.
    """
    n = len(daily_var_predictions)
    indicators = []
    for i in range(n):
        actual_loss = -daily_pnl[i]
        indicators.append(1 if actual_loss > daily_var_predictions[i] else 0)

    # Build transition counts: n_ij = count of (i -> j) transitions
    n00 = n01 = n10 = n11 = 0
    for i in range(len(indicators) - 1):
        prev, curr = indicators[i], indicators[i + 1]
        if prev == 0 and curr == 0:
            n00 += 1
        elif prev == 0 and curr == 1:
            n01 += 1
        elif prev == 1 and curr == 0:
            n10 += 1
        else:
            n11 += 1

    # Under independence: pi_01 = pi_11 = pi (unconditional probability)
    total_transitions = n00 + n01 + n10 + n11
    if total_transitions == 0:
        return 0.0, 1.0

    # Conditional probabilities
    row0 = n00 + n01
    row1 = n10 + n11

    if row0 == 0 or row1 == 0:
        return 0.0, 1.0

    pi_01 = n01 / row0 if row0 > 0 else 0.0
    pi_11 = n11 / row1 if row1 > 0 else 0.0

    # Unconditional probability
    pi = (n01 + n11) / total_transitions

    if pi <= 0.0 or pi >= 1.0:
        return 0.0, 1.0

    # Avoid log(0) in degenerate cases
    if pi_01 <= 0.0 or pi_01 >= 1.0 or pi_11 <= 0.0 or pi_11 >= 1.0:
        return 0.0, 1.0

    # Log-likelihood under independence (H0)
    ll_0 = (n00 + n10) * math.log(1.0 - pi) + (n01 + n11) * math.log(pi)

    # Log-likelihood under dependence (H1)
    ll_1 = 0.0
    if n00 > 0:
        ll_1 += n00 * math.log(1.0 - pi_01)
    if n01 > 0:
        ll_1 += n01 * math.log(pi_01)
    if n10 > 0:
        ll_1 += n10 * math.log(1.0 - pi_11)
    if n11 > 0:
        ll_1 += n11 * math.log(pi_11)

    lr_ind = -2.0 * (ll_0 - ll_1)
    p_value = 1.0 - chi2.cdf(lr_ind, df=1)
    return lr_ind, float(p_value)


def _traffic_light_zone(violation_count: int) -> TrafficLightZone:
    if violation_count <= 4:
        return TrafficLightZone.GREEN
    elif violation_count <= 9:
        return TrafficLightZone.YELLOW
    else:
        return TrafficLightZone.RED

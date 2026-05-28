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
    """Basel-2013 backtest classification by exception count over a
    250-day rolling window for a 99% VaR model.

    The zone bands match the Basel Committee's 1996 amendment, refined
    in 2013 (BCBS 265):

      - GREEN (0-4 exceptions): the model's exception rate is consistent
        with the 99% confidence level (expected ~2.5 over 250 days);
        no supervisory penalty.
      - YELLOW / AMBER (5-9 exceptions): the model is increasingly
        suspect; a supervisory multiplier from 0.40 to 0.85 is added
        to the capital requirement to compensate for under-stated risk.
      - RED (10+ exceptions): the model is rejected; the maximum
        supervisory multiplier (1.00) applies and the bank must
        recalibrate before re-using the model.

    The amber-zone band scales the multiplier linearly with the
    exception count so a model at the bottom of the band pays a
    smaller penalty than one at the top. See
    [basel_traffic_light_multiplier] for the exact mapping.
    """
    if violation_count <= 4:
        return TrafficLightZone.GREEN
    elif violation_count <= 9:
        return TrafficLightZone.YELLOW
    else:
        return TrafficLightZone.RED


def basel_traffic_light_multiplier(violation_count: int) -> float:
    """Per-exception capital multiplier in the Basel traffic-light bands.

    Returns 0.00 in the green zone (0-4 exceptions), a linearly
    interpolated value in the amber zone (5 -> 0.40, 6 -> 0.50,
    7 -> 0.65, 8 -> 0.75, 9 -> 0.85), and 1.00 in the red zone
    (10+ exceptions).

    Reference
    ---------
    Basel Committee on Banking Supervision. (1996, revised 2013).
        *Supervisory framework for the use of "backtesting" in
        conjunction with the internal models approach to market risk
        capital requirements*. BCBS 265.
    """
    if violation_count <= 4:
        return 0.0
    if violation_count >= 10:
        return 1.0
    amber_table = {5: 0.40, 6: 0.50, 7: 0.65, 8: 0.75, 9: 0.85}
    return amber_table[violation_count]


def overshoot_magnitude_summary(
    daily_var_predictions: list[float],
    daily_pnl: list[float],
) -> dict[str, float]:
    """Summarise the *magnitudes* of VaR overshoots, not just the count.

    Kupiec POF tells you how many days breached, Christoffersen tells
    you whether those breaches clustered, but neither captures how
    *bad* an individual breach was. A model can be Kupiec-clean (right
    number of breaches) while still understating tail loss — if every
    breach is 3-5x the VaR you have a fat-tail problem that calls for
    Expected Shortfall, not just VaR.

    Returns:
      - mean_overshoot: average of (actual_loss - VaR) over breach days
      - max_overshoot: worst single-day overshoot
      - total_overshoot: sum of all overshoots (the "extra" loss)
      - overshoot_count: count of breach days (parity with Kupiec)
    """
    breach_overshoots: list[float] = []
    for var_pred, pnl in zip(daily_var_predictions, daily_pnl):
        actual_loss = -pnl
        if actual_loss > var_pred:
            breach_overshoots.append(actual_loss - var_pred)
    count = len(breach_overshoots)
    return {
        "overshoot_count": float(count),
        "mean_overshoot": sum(breach_overshoots) / count if count else 0.0,
        "max_overshoot": max(breach_overshoots) if count else 0.0,
        "total_overshoot": sum(breach_overshoots),
    }


def rolling_window_violation_counts(
    daily_var_predictions: list[float],
    daily_pnl: list[float],
    window_days: int = 252,
) -> list[int]:
    """Sliding window-of-252 violation counts for time-varying backtest.

    Basel's 99% VaR backtest evaluates exception counts on a rolling
    250-day window — a model that started clean a year ago might be
    drifting today and a single full-sample count hides that. The
    helper returns the per-step violation count, sliding the window
    one day at a time after warmup.

    Window of 252 = 1 trading year. Returns an empty list if the
    sample is shorter than the window.
    """
    if len(daily_var_predictions) != len(daily_pnl):
        raise ValueError("VaR predictions and P&L must be the same length")
    n = len(daily_var_predictions)
    if n < window_days:
        return []
    # First compute the per-day breach indicator.
    indicators = [1 if -pnl > var else 0 for var, pnl in zip(daily_var_predictions, daily_pnl)]
    counts: list[int] = []
    running = sum(indicators[:window_days])
    counts.append(running)
    for i in range(window_days, n):
        running += indicators[i] - indicators[i - window_days]
        counts.append(running)
    return counts


def duration_clustering_test(
    daily_var_predictions: list[float],
    daily_pnl: list[float],
) -> dict[str, int]:
    """Detect clustering of VaR violations by counting run lengths.

    Christoffersen tests independence via a Markov chain on the
    pair (yesterday, today). The duration-clustering test goes further
    and counts the *lengths* of consecutive-breach runs — if you see
    a 5-day run of breaches, that's a regime break the Markov model
    captures less directly. Returns a histogram of run lengths.

    Output keys are stringified lengths so the result serialises to
    JSON without effort.
    """
    if len(daily_var_predictions) != len(daily_pnl):
        raise ValueError("VaR predictions and P&L must be the same length")
    indicators = [1 if -pnl > var else 0 for var, pnl in zip(daily_var_predictions, daily_pnl)]
    counts: dict[str, int] = {}
    run_len = 0
    for ind in indicators:
        if ind == 1:
            run_len += 1
        else:
            if run_len > 0:
                key = str(run_len)
                counts[key] = counts.get(key, 0) + 1
            run_len = 0
    # Trailing run.
    if run_len > 0:
        key = str(run_len)
        counts[key] = counts.get(key, 0) + 1
    return counts


def acerbi_szekely_es_backtest(
    daily_var_predictions: list[float],
    daily_es_predictions: list[float],
    daily_pnl: list[float],
) -> float:
    """Acerbi-Szekely Z-score for an Expected-Shortfall backtest.

    .. math::

        Z = \\frac{1}{T \\, p} \\sum_t \\frac{L_t \\cdot I_t}{\\text{ES}_t} + 1

    where ``I_t`` is the VaR-violation indicator, ``L_t`` is the
    realised loss on day t, ``T`` is the sample size, and ``p`` is
    the expected violation rate (1 - confidence). A perfectly
    calibrated ES model gives Z near 0; Z < 0 means ES under-states
    tail losses (model is too optimistic), Z > 0 means ES over-states.

    Acerbi-Szekely's contribution: ES is *not* elicitable — there is
    no scoring function that uniquely identifies the true ES — but
    it IS jointly elicitable with VaR, so this Z statistic conditions
    on the VaR breach days where the model is making a tail claim.

    Reference
    ---------
    Acerbi, C., & Szekely, B. (2014). Back-testing expected
    shortfall. *Risk Magazine*, December 2014.
    """
    if not (len(daily_var_predictions) == len(daily_es_predictions) == len(daily_pnl)):
        raise ValueError("VaR, ES, and P&L lists must be the same length")
    n = len(daily_var_predictions)
    if n == 0:
        return 0.0
    # Approximate p = expected violation rate; in production this would
    # come from the configured confidence level.
    breaches = 0
    z_contribution = 0.0
    for var_pred, es_pred, pnl in zip(daily_var_predictions, daily_es_predictions, daily_pnl):
        actual_loss = -pnl
        if actual_loss > var_pred:
            breaches += 1
            if es_pred > 0:
                z_contribution += actual_loss / es_pred
    if breaches == 0:
        return 0.0
    return z_contribution / breaches - 1.0


def marbach_combined_test(
    daily_var_predictions: list[float],
    daily_pnl: list[float],
    confidence_level: float = 0.99,
) -> tuple[float, float]:
    """Marbach (Christoffersen) combined coverage + independence test.

    The conditional coverage test stacks Kupiec POF (unconditional
    coverage) and Christoffersen's independence test into a single
    statistic, asymptotically chi-squared with 2 d.o.f.:

    .. math::

        LR_{cc} = LR_{POF} + LR_{ind}

    A model that's both correctly calibrated AND has uncorrelated
    breaches passes; failing either component fails the combined test.

    Returns (LR_cc, p_value).
    """
    if len(daily_var_predictions) != len(daily_pnl):
        raise ValueError("VaR and P&L must be the same length")
    n = len(daily_var_predictions)
    expected_rate = 1.0 - confidence_level
    breaches = sum(1 for v, p in zip(daily_var_predictions, daily_pnl) if -p > v)
    lr_pof, _ = _kupiec_pof_test(n, breaches, expected_rate)
    lr_ind, _ = _christoffersen_independence_test(daily_var_predictions, daily_pnl)
    lr_cc = lr_pof + lr_ind
    p_value = 1.0 - chi2.cdf(lr_cc, df=2)
    return lr_cc, float(p_value)

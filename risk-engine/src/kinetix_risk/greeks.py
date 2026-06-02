from collections import defaultdict

from kinetix_risk.models import (
    AssetClass, CalculationType, ConfidenceLevel, GreeksResult, PositionRisk,
)
from kinetix_risk.portfolio_risk import calculate_book_var
from kinetix_risk.volatility import VolatilityProvider

PRICE_BUMP = 0.01       # 1% price bump for delta/gamma
VOL_BUMP = 0.01         # 1 percentage point vol bump for vega
RATE_BUMP = 0.0001      # 1 basis point for rho
TIME_BUMP_DAYS = 1      # 1-day bump for charm
DEFAULT_RISK_FREE_RATE = 0.05


def _bump_positions(positions: list[PositionRisk], asset_class: AssetClass, bump: float) -> list[PositionRisk]:
    result = []
    for pos in positions:
        if pos.asset_class == asset_class:
            result.append(PositionRisk(
                instrument_id=pos.instrument_id,
                asset_class=pos.asset_class,
                market_value=pos.market_value * (1 + bump),
                currency=pos.currency,
            ))
        else:
            result.append(pos)
    return result


def _bump_vol_provider(
    base_provider: VolatilityProvider,
    asset_classes: set[AssetClass],
    bumped_ac: AssetClass,
    bump: float,
) -> VolatilityProvider:
    """Return a volatility provider equal to ``base_provider`` for every asset
    class present, except ``bumped_ac`` whose vol is shifted by ``bump``.

    Starting from the base provider (rather than DEFAULT_VOLATILITIES) keeps
    vega/vanna/volga measured relative to the same vols the base VaR used.
    """
    vols = {ac: base_provider(ac) for ac in asset_classes}
    vols[bumped_ac] = base_provider(bumped_ac) + bump
    return VolatilityProvider.from_dict(vols)


def _var_value(positions, calculation_type, confidence_level, time_horizon_days,
               volatility_provider=None, risk_free_rate: float = 0.0,
               correlation_matrix=None, historical_returns=None,
               correlation_method: str | None = None, seed: int | None = None,
               num_simulations: int = 10_000) -> float:
    return calculate_book_var(
        positions, calculation_type, confidence_level, time_horizon_days,
        num_simulations=num_simulations,
        volatility_provider=volatility_provider,
        correlation_matrix=correlation_matrix,
        risk_free_rate=risk_free_rate,
        historical_returns=historical_returns,
        correlation_method=correlation_method,
        seed=seed,
    ).var_value


def calculate_greeks(
    positions: list[PositionRisk],
    calculation_type: CalculationType,
    confidence_level: ConfidenceLevel,
    time_horizon_days: int,
    book_id: str = "",
    base_var_value: float | None = None,
    volatility_provider: VolatilityProvider | None = None,
    correlation_matrix=None,
    historical_returns=None,
    correlation_method: str | None = None,
    seed: int | None = None,
    num_simulations: int = 10_000,
) -> GreeksResult:
    if not positions:
        raise ValueError("Cannot calculate Greeks on empty positions list")

    # Model inputs forwarded to EVERY bumped VaR so the finite differences are
    # taken against the same market-data model that produced the base VaR.
    # Without this the bumped runs fall back to the static default model and the
    # constant model gap swamps the bump, degenerating the Greeks into a
    # near-constant value uniform across asset classes (kx-ohul).
    model_kwargs = dict(
        correlation_matrix=correlation_matrix,
        historical_returns=historical_returns,
        correlation_method=correlation_method,
        seed=seed,
        num_simulations=num_simulations,
    )

    base_var = base_var_value if base_var_value is not None else _var_value(
        positions, calculation_type, confidence_level, time_horizon_days,
        volatility_provider=volatility_provider, **model_kwargs,
    )

    # Find which asset classes are present
    asset_classes_present: set[AssetClass] = set()
    for pos in positions:
        asset_classes_present.add(pos.asset_class)

    delta: dict[AssetClass, float] = {}
    gamma: dict[AssetClass, float] = {}
    vega: dict[AssetClass, float] = {}
    vanna: dict[AssetClass, float] = {}
    volga: dict[AssetClass, float] = {}
    charm: dict[AssetClass, float] = {}

    # The base volatility provider used for the base VaR. Vol bumps start from
    # this provider (not DEFAULT_VOLATILITIES) so vega/vanna/volga are taken
    # relative to the same vols the base VaR used.
    base_vol_provider = volatility_provider or VolatilityProvider.static()

    for ac in sorted(asset_classes_present, key=lambda a: a.value):
        # Delta: (VaR_up - VaR_base) / bump
        positions_up = _bump_positions(positions, ac, PRICE_BUMP)
        var_up = _var_value(positions_up, calculation_type, confidence_level, time_horizon_days,
                            volatility_provider=base_vol_provider, **model_kwargs)
        delta[ac] = (var_up - base_var) / PRICE_BUMP

        # Gamma: (VaR_up - 2*VaR_base + VaR_down) / bump^2
        positions_down = _bump_positions(positions, ac, -PRICE_BUMP)
        var_down = _var_value(positions_down, calculation_type, confidence_level, time_horizon_days,
                              volatility_provider=base_vol_provider, **model_kwargs)
        gamma[ac] = (var_up - 2 * base_var + var_down) / (PRICE_BUMP ** 2)

        # Vega: bump vol by +1pp from the base vols, (VaR_bumped - VaR_base) / vol_bump
        base_vol = base_vol_provider(ac)
        vol_provider = _bump_vol_provider(base_vol_provider, asset_classes_present, ac, VOL_BUMP)
        var_vol_up = _var_value(positions, calculation_type, confidence_level, time_horizon_days,
                                volatility_provider=vol_provider, **model_kwargs)
        vega[ac] = (var_vol_up - base_var) / VOL_BUMP

        # Vanna: d(delta)/d(vol) — bump price AND vol, compute cross-partial
        # vanna = (VaR(S+,vol+) - VaR(S+,vol_base) - VaR(S_base,vol+) + VaR_base) / (price_bump * vol_bump)
        positions_up_vol = _bump_positions(positions, ac, PRICE_BUMP)
        var_up_vol_up = _var_value(positions_up_vol, calculation_type, confidence_level, time_horizon_days,
                                   volatility_provider=vol_provider, **model_kwargs)
        vanna[ac] = (var_up_vol_up - var_up - var_vol_up + base_var) / (PRICE_BUMP * VOL_BUMP)

        # Volga: d(vega)/d(vol) — second derivative w.r.t. vol
        # volga = (VaR(vol+2) - 2*VaR(vol+) + VaR_base) / vol_bump^2
        vol_provider_2 = _bump_vol_provider(base_vol_provider, asset_classes_present, ac, 2 * VOL_BUMP)
        var_vol_up_2 = _var_value(positions, calculation_type, confidence_level, time_horizon_days,
                                   volatility_provider=vol_provider_2, **model_kwargs)
        volga[ac] = (var_vol_up_2 - 2 * var_vol_up + base_var) / (VOL_BUMP ** 2)

        # Charm: d(delta)/d(time) — how delta changes with time horizon
        if time_horizon_days > TIME_BUMP_DAYS:
            var_up_t_minus = _var_value(positions_up, calculation_type, confidence_level,
                                        time_horizon_days - TIME_BUMP_DAYS,
                                        volatility_provider=base_vol_provider, **model_kwargs)
            var_base_t_minus = _var_value(positions, calculation_type, confidence_level,
                                          time_horizon_days - TIME_BUMP_DAYS,
                                          volatility_provider=base_vol_provider, **model_kwargs)
            delta_t_minus = (var_up_t_minus - var_base_t_minus) / PRICE_BUMP
            charm[ac] = (delta[ac] - delta_t_minus) / TIME_BUMP_DAYS
        else:
            var_up_t_plus = _var_value(positions_up, calculation_type, confidence_level,
                                       time_horizon_days + TIME_BUMP_DAYS,
                                       volatility_provider=base_vol_provider, **model_kwargs)
            var_base_t_plus = _var_value(positions, calculation_type, confidence_level,
                                         time_horizon_days + TIME_BUMP_DAYS,
                                         volatility_provider=base_vol_provider, **model_kwargs)
            delta_t_plus = (var_up_t_plus - var_base_t_plus) / PRICE_BUMP
            charm[ac] = (delta_t_plus - delta[ac]) / TIME_BUMP_DAYS

    # Theta: VaR with (time_horizon - 1) minus VaR_base
    if time_horizon_days > 1:
        var_t_minus_1 = _var_value(positions, calculation_type, confidence_level, time_horizon_days - 1,
                                   volatility_provider=base_vol_provider, **model_kwargs)
    else:
        # For 1-day horizon, compute with 2-day to show time sensitivity
        var_t_plus_1 = _var_value(positions, calculation_type, confidence_level, time_horizon_days + 1,
                                  volatility_provider=base_vol_provider, **model_kwargs)
        var_t_minus_1 = var_t_plus_1  # theta = VaR(t+1) - VaR(t)

    theta = var_t_minus_1 - base_var

    # Rho: bump risk-free rate by 1bp and measure VaR change
    var_base_rate = _var_value(positions, calculation_type, confidence_level, time_horizon_days,
                               volatility_provider=base_vol_provider,
                               risk_free_rate=DEFAULT_RISK_FREE_RATE, **model_kwargs)
    var_bumped_rate = _var_value(positions, calculation_type, confidence_level, time_horizon_days,
                                 volatility_provider=base_vol_provider,
                                 risk_free_rate=DEFAULT_RISK_FREE_RATE + RATE_BUMP, **model_kwargs)
    rho = (var_bumped_rate - var_base_rate) / RATE_BUMP

    return GreeksResult(
        book_id=book_id,
        delta=delta,
        gamma=gamma,
        vega=vega,
        theta=theta,
        rho=rho,
        vanna=vanna,
        volga=volga,
        charm=charm,
    )

# Risk Methodology

The quantitative core of Kinetix lives in the [Python risk engine](https://github.com/panayotovk/kinetix/tree/main/risk-engine), exposed to the rest of the platform via gRPC ([ADR-0003](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0003-grpc-for-python-integration.md), [ADR-0024](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0024-unified-valuation-rpc.md)).

A guiding rule: **the risk engine is a pure function** ([ADR-0029](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0029-discovery-valuation-two-phase-contract.md)). Given the same `(positions, market_data, seed)`, it produces the same outputs — no hidden lookups, no clock dependencies, no caches that bleed across runs. This is what makes runs replayable bit-for-bit from a manifest ([ADR-0018](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0018-run-reproducibility-via-manifests.md)).

## Value at Risk

Three methodologies are implemented side by side. Method selection is driven by the regime classifier (see below) and per-book configuration.

### Parametric (Delta-Normal)

- Assumes returns ~ multivariate normal, factor sensitivities are linear
- Closed-form: VaR = √(δᵀ Σ δ) · z_α · √h
- Fast, used in Light pre-trade what-if and as a sanity baseline
- File: `var_parametric.py`

### Historical Simulation

- Empirical, no distributional assumption
- Square-root-of-time scaling for horizons beyond the source window (Basel)
- Tail estimated by the empirical quantile of historical P&L vectors applied to current portfolio
- File: `var_historical.py`

### Monte Carlo

- 10,000 paths by default, with **antithetic variates** for variance reduction (`x` and `-x` paired)
- Cholesky-decomposed correlation for joint shocks across factors
- Pluggable distribution per asset class (Gaussian, Student-t for fat tails)
- Deterministic given the seed in the unified valuation RPC
- File: `var_monte_carlo.py`

### Expected Shortfall

- CVaR at 97.5% per Basel FRTB
- File: `expected_shortfall.py`

### Cross-book VaR

- Multi-book aggregation with explicit correlation matrices
- Hierarchy roll-up across Firm → Division → Desk → Book ([ADR-0023](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0023-hierarchical-limit-management.md))
- Marginal contribution and risk-budgeting decomposition for CRO-style reports
- Files: `cross_book_var.py`, `ScheduledCrossBookVaRCalculator.kt`

## Greeks

### Analytical Black-Scholes-Merton

- Delta, Gamma, Vega, Theta, Rho with continuous dividend yield
- File: `black_scholes.py`

### Cross-Greeks

- Vanna (∂²V / ∂S ∂σ), Volga (∂²V / ∂σ²), Charm (∂²V / ∂S ∂t) — analytical BSM
- File: `greeks.py`

### Portfolio aggregation

- Greeks aggregated per asset class, then unified into portfolio-level exposures
- Foundation for hedge optimisation and P&L attribution

## P&L Attribution

Intraday and EOD P&L decomposed into Greek contributions:

```
P&L = Δ · dS + ½Γ · dS² + ν · dσ + Θ · dt + ρ · dr + Unexplained
```

- **Greek source:** pricing Greeks from `SodGreekSnapshot`, not VaR Greeks ([ADR-0032](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0032-intraday-pnl-greek-source.md)) — VaR Greeks are conditional on scenario distribution and would attribute P&L incorrectly
- **Brinson decomposition:** allocation vs. selection contribution by sector/asset class — `brinson.py`
- **Files:** `attribution_server.py`, `PnLAttributionDeriver.kt`

## Factor Model

Five systematic factors:

1. Equity beta (broad market)
2. Rates duration (DV01-weighted)
3. Credit spread
4. FX delta (USD-weighted)
5. Vol exposure (net vega)

Loadings are estimated via OLS on rolling returns and via analytical decomposition for instruments with closed-form sensitivities. Factor VaR is decomposed back to position-level contributions for concentration warnings.

- Files: `factor_model.py`, `factor_server.py`

## Bond and Swap Pricing

### Bond pricing — DV01, key rate durations

- Discount-curve based PV
- Internal KRD: 4 tenor buckets (2Y, 5Y, 10Y, 30Y) — fast for intraday risk
- FRTB GIRR: 12 tenor buckets per Basel for capital calculation
- Two-grid design documented in [ADR-0028](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0028-key-rate-duration-tenor-buckets.md)
- Files: `bond_pricing.py`, `key_rate_duration.py`

### Swap pricing

- Interest rate swap PV from discount and forward curves
- Sensitivity to each curve node returned for risk roll-up
- File: `swap_pricing.py`

## Stress Testing

### Historical Replay

- Apply actual crisis-period returns to the current portfolio
- Library includes: GFC 2008, COVID 2020, Taper Tantrum 2013, Euro Crisis 2011
- File: `historical_replay.py`

### Reverse Stress Testing

- Given a target loss, find the smallest factor shock that produces it
- Implementation: minimum-norm SLSQP solver in SciPy
- Used for stress narrative ("what conditions would cost us £50M?")
- File: `reverse_stress.py`

### Custom Scenarios

- Multi-factor parametric shocks with correlation override
- Liquidity stress factors layered on top
- Scenario governance with approval workflow (draft → pending approval → approved → retired) — see `scenario-lifecycle.allium`
- Files: `stress_server.py`, regulatory-service scenario pipeline

## Backtesting

VaR backtests evaluated quarterly per regulator expectations.

- **Kupiec POF (Proportion of Failures):** log-likelihood ratio against the null of correct unconditional coverage
- **Christoffersen Independence:** tests whether exceptions cluster (a model that's right on average but bunches errors is still failing)
- **Combined:** Christoffersen conditional coverage (Kupiec + independence)
- **Basel traffic light:** Green / Yellow / Red zones based on exception count over 250 days
- File: `backtesting.py`

## Regime Detection

A rule-based classifier maps observed market conditions to a discrete regime:

- `NORMAL`
- `ELEVATED_VOLATILITY`
- `CRISIS`
- `RECOVERY`

Signals: rolling realised vol, correlation breakdown, term-structure inversion, credit spread widening, liquidity proxies.

Transitions are **debounced** — a single noisy print should not flip risk parameters. Adaptive parameters per regime auto-adjust calculation method, confidence level, and time horizon.

Behaviour on degraded inputs ([ADR-0034](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0034-regime-degraded-signal-policy.md)): when some signals are unavailable, a transition fires only if **all available signals agree**. Otherwise the classifier holds and emits a `risk.anomalies` event flagging the degradation.

Early-warning signals are surfaced to the UI at 80% of regime-transition thresholds.

- Files: `regime_detector.py`, `ScheduledRegimeDetector.kt`

## Counterparty Risk

### Exposure

- Gross exposure (notional)
- Net exposure (post-netting under ISDA/GMRA agreements)
- Net-net exposure (post-collateral, including initial and variation margin)

### Potential Future Exposure

- Monte Carlo simulation across tenor buckets
- Reported at 95th and 99th percentiles
- File: `credit_exposure.py`

### CVA

- Discrete approximation: Σₜ DF(t) · EE(t) · PD(t) · LGD
- PDs from CDS-implied curves where available; Basel II tables as fallback
- File: `credit_exposure.py`

### Wrong-way risk

- Sector-match taxonomy per [ADR-0031](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0031-wrong-way-risk-sector-taxonomy.md): exposure to a counterparty's sector is flagged as WWR
- Replaces a less-strict counterparty-only heuristic from earlier versions

### SA-CCR

- Standardised Approach to Counterparty Credit Risk per Basel III
- EAD = α · (RC + PFE), with α = 1.4
- Asset-class-specific PFE multipliers and supervisory factors
- Files: `sa_ccr.py`, `sa_ccr_server.py`

## Liquidity Risk

- Liquidity-adjusted VaR with liquidity-spread shocks per tenor and asset class
- Concentration monitoring across instruments and counterparties
- Stressed-liquidation analysis: time-to-liquidate under stress assumptions
- Files: `liquidity.py`, `liquidity_server.py`

## Hedge Optimisation

- Constrained optimiser: minimise target Greek exposure (or VaR) subject to position-size, instrument-availability, and cost constraints
- Cost model includes bid-ask, market impact, financing
- Returns a ranked list of suggested hedges with marginal-risk-per-cost
- File: `hedge_optimizer.py`

## Machine Learning

ML services are auxiliary — they inform alerts and dashboards but do not gate regulatory or hard-limit decisions.

- **Anomaly detection** (`ml/anomaly_detector.py`) — Isolation Forest over price and vol streams; outputs flagged as `risk.anomalies`
- **Volatility forecasting** (`ml/vol_predictor.py`) — LSTM (PyTorch); used as a sanity overlay against EWMA
- **Credit PD** (`ml/credit_model.py`) — neural-net classifier for non-CDS counterparties

## Reproducibility

Every risk run captures:

- Input position set (with version)
- All market data used (prices, curves, vol surfaces, correlations)
- Monte Carlo seed
- Risk-engine code version (git SHA)
- Model version per asset class
- Output hash

This is sufficient to replay any run bit-for-bit ([ADR-0018](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0018-run-reproducibility-via-manifests.md)) — the foundation for backtesting on historical portfolios, "compare two runs" tooling, and regulator-grade audit.

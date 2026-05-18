# FRTB Capital

Kinetix implements the **Fundamental Review of the Trading Book — Standardised Approach** (FRTB-SA) for market risk capital. The Internal Models Approach (IMA) is not implemented.

The total FRTB-SA capital charge is the sum of three components:

```
FRTB_SA = SBM + DRC + RRAO
```

- **SBM** — Sensitivities-Based Method (delta, vega, curvature)
- **DRC** — Default Risk Charge
- **RRAO** — Residual Risk Add-On

Computed in the Python risk engine, persisted by [regulatory-service](https://github.com/panayotovk/kinetix/tree/main/regulatory-service), and exposed to the UI via the Regulatory tab with CSV and XBRL export.

## SBM — Sensitivities-Based Method

Source: `risk-engine/src/kinetix_risk/frtb/sbm.py`

Three sub-charges:

1. **Delta** — linear sensitivities aggregated across risk factors using Basel correlations
2. **Vega** — option vega sensitivities to implied vol bucketed by underlying and maturity
3. **Curvature** — measures non-linear option PV change under prescribed up/down shocks

For each risk class (`GIRR`, `CSR non-securitisation`, `CSR securitisation`, `Equity`, `Commodity`, `FX`), the SBM aggregates bucket-level positions and bucket-level capital:

```
K_bucket = √(Σᵢ WSᵢ² + Σᵢ Σⱼ≠ᵢ ρᵢⱼ · WSᵢ · WSⱼ)
K_class  = √(Σ_b K_b² + Σ_b Σ_c≠b γ_bc · S_b · S_c)
```

Where:

- `WS_i = RWᵢ · sᵢ` — weighted sensitivity
- `ρ_ij` — intra-bucket correlation per Basel tables
- `γ_bc` — inter-bucket correlation per Basel tables
- `S_b` — sum of weighted sensitivities in bucket `b`

A **high-correlation** and **low-correlation** scenario must be computed (correlations multiplied by 1.25 and 0.75, capped at 1.0); the SBM-SA charge is the maximum of the three scenarios.

### GIRR

Source: `risk-engine/src/kinetix_risk/frtb/risk_weights.py`, `frtb/girr_correlations.py`

- 12 tenor buckets: 0.25Y, 0.5Y, 1Y, 2Y, 3Y, 5Y, 10Y, 15Y, 20Y, 30Y plus inflation and cross-currency
- Risk weights per Basel tables
- Tenor correlations parameterised by `θ` (per Basel)
- Curvature uses prescribed parallel shifts

A separate 4-tenor internal KRD grid (2Y/5Y/10Y/30Y) is used for intraday risk where computation speed matters more than capital precision — see [ADR-0028](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0028-key-rate-duration-tenor-buckets.md).

### Equity

- Buckets by market cap (large/small) and economy type (advanced/emerging) and sector
- Per-bucket risk weights for delta and vega
- Repo rate sensitivities included

### Commodity

- 11 buckets per Basel commodity taxonomy
- Risk weights vary by commodity type (energy, metals, agriculturals)
- Tenor structure within bucket

### FX

- USD numéraire (configurable for non-USD reporting)
- Bilateral pairs vs. each major currency
- Curvature for FX options

### Credit spread

- Non-securitisation: 18 buckets by sector × credit quality
- Securitisation correlation trading: separate bucket grid

## DRC — Default Risk Charge

Source: `risk-engine/src/kinetix_risk/frtb/drc.py`, `frtb/drc_enhanced.py`

DRC captures jump-to-default risk that the SBM cannot — a sudden default by an issuer.

### Calculation

```
JTD_long  = Σ (notional · LGD - market_value)  per issuer
JTD_short = Σ (notional · LGD - market_value)  per issuer (sign-flipped)
DRC       = Σ_bucket [ max(Σ JTD_long, 0) - WtS · |Σ JTD_short| ]
```

Where:

- `LGD` — loss given default, by seniority (60% senior, 75% subordinated)
- `WtS` — hedge benefit ratio across long and short positions in the same bucket
- Bucket = credit rating × sector
- Maturity weighting applied to non-securitisation positions (≥ 3 months)

### Enhanced DRC

The enhanced module adds:

- Rating-based PDs (with rating migration)
- Sector-specific concentration adjustments
- Separation of investment-grade / high-yield / unrated buckets

### Files

- `frtb/drc.py` — core DRC
- `frtb/drc_enhanced.py` — enhanced calculation for credit positions

## RRAO — Residual Risk Add-On

Source: `risk-engine/src/kinetix_risk/frtb/rrao.py`

A flat charge on notional for exotic instruments whose risk is not fully captured by SBM:

- **Exotic underlying (1.0%):** weather derivatives, mortality-linked, longevity, etc.
- **Other residual risk (0.1%):** path-dependent, multi-asset baskets, behavioural options

The notional definition follows Basel (effective notional adjusted for amortisation).

## Orchestration

Source: `risk-engine/src/kinetix_risk/frtb/calculator.py`

The FRTB calculator orchestrates the three components:

```
1. Discover positions in scope (trading book)
2. Compute sensitivities (delta, vega, curvature) for each position
3. Bucket sensitivities per Basel taxonomy
4. SBM:  delta + vega + curvature with 3-scenario correlation
5. DRC:  JTD aggregation with hedge benefits and maturity weighting
6. RRAO: notional sum × 0.1% / 1.0% per residual category
7. Total = SBM + DRC + RRAO
```

## Persistence and reporting

- Results persisted by `regulatory-service` in PostgreSQL (`FrtbCalculationRepository.kt`, `FrtbResultResponse.kt`)
- One-click compute from the **Regulatory** UI tab
- Templated CSV and XBRL export for regulator submission
- Submission workflow gated by four-eyes approval (preparer ≠ approver) — see [Audit and Compliance](Audit-and-Compliance)

## Tests

- Property-based tests with Hypothesis for correlation aggregation (commutativity under bucket reordering, monotonicity in risk weight)
- Golden-output fixtures against worked examples from the Basel framework
- Acceptance tests in `regulatory-service` exercise the full pipeline against Testcontainers Postgres

## What is not implemented

- **IMA (Internal Models Approach):** not in scope — the platform commits to SA reporting
- **Counterparty Credit Risk capital (CVA risk capital framework):** SA-CCR is implemented for EAD but the SA-CVA / BA-CVA capital frameworks are not. Counterparty exposure is reported via the [Counterparty Risk dashboard](Risk-Methodology#counterparty-risk).

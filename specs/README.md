# Allium Specifications

## What is Allium and why we use it

Allium is a domain-specific language for capturing system behaviour as executable specifications. An Allium spec describes entities, value types, rules, events, triggers, surfaces, and contracts in a precise, machine-readable form that humans can read like a design document and tools can analyse like code. In Kinetix, the `specs/*.allium` files are the source of truth for what the platform does: services, schemas, and tests are kept aligned with them through the `/distill` (extract spec from code), `/weed` (detect spec-code drift), and `/propagate` (generate tests from specs) workflow. This lets engineers and AI agents collaborate from one shared model — when behaviour changes, the spec changes first and the implementation and tests follow. See [`docs/HOW_IT_WAS_BUILT.md`](../docs/HOW_IT_WAS_BUILT.md) for how Allium fits into the broader AI-assisted-development workflow.

## Core

- core.allium: Core domain types shared across the Kinetix platform.
- market-data.allium: Market data ingestion, storage, and distribution: prices, rates, volatility, correlations.
- positions.allium: Position management, mark-to-market, realized P&L, and portfolio aggregation.
- reference-data.allium: Instruments, organizational hierarchy, and reference data.

## Trading

- discovery-valuation.allium: Discovery-Valuation Two-Phase Contract and Instrument Type Dependency Registry.
- execution.allium: Order and execution management: FIX protocol integration, order lifecycle, fill processing, position reconciliation, and execution cost analysis.
- hedge.allium: Hedge recommendation engine: constrained optimisation to suggest trades that minimise a target Greek or VaR, with cost estimation and what-if validation handoff.
- intraday-pnl.allium: Streaming intraday P&L: real-time position-level and book-level P&L with Greek attribution against frozen SOD state.
- limits.allium: Limit management hierarchy, pre-trade checks, and temporary increases.
- trading.allium: Trade booking, lifecycle management (amend, cancel), and event publishing.

## Risk

- counterparty-risk.allium: Counterparty and credit risk: PFE, CVA, netting sets, wrong-way risk, and collateral tracking.
- factor-model.allium: Factor-based risk decomposition: systematic risk factors, factor loadings, VaR attribution by factor, and P&L attribution by factor.
- hierarchy-risk.allium: Multi-desk risk aggregation: hierarchy drill-down, VaR budgeting, marginal contribution analysis, and CRO report generation.
- liquidity.allium: Liquidity risk: instrument liquidity metadata, position-level liquidity metrics, liquidity-adjusted VaR, concentration monitoring, and stressed liquidity analysis.
- regime.allium: Market regime detection: classify market conditions, adapt VaR parameters, surface early warnings, and dynamically select risk models.
- risk-models.allium: Quantitative risk models: VaR methods, Black-Scholes pricing, Greeks, bond/swap pricing, stress testing, and FRTB regulatory capital.
- risk.allium: Risk calculation: VaR, Greeks, cross-book aggregation, stress testing, P&L attribution, EOD promotion, and calculation job lifecycle.
- scenario-lifecycle.allium: Scenario lifecycle: status state machine, approval workflow, governance audit events, versioning, and update semantics for stress scenarios.
- scenarios.allium: Historical scenario replay, reverse stress testing, and scenario library management with versioning and governance.

## Regulatory

- audit.allium: Hash-chained, immutable audit trail for all significant system events: trade lifecycle, risk calculations, model governance, limit changes, scenario approvals, regulatory submissions, and access denials.
- eod-close.allium: Automatic End-of-Day (EOD) Run & Promotion: scheduled trigger at market close, idempotent per-book VaR calculation, and automatic promotion to OFFICIAL_EOD with full audit trail.
- regulatory.allium: Model governance, backtesting records, stress testing governance, regulatory submissions with four-eyes approval, and FRTB storage.

## Operations

- alert-escalation.allium: Alert escalation workflow: timeout-driven promotion, severity escalation, multi-channel delivery routing, scheduled polling, audit trail, and debounce semantics for the notification-service escalation pipeline.
- alerts.allium: Alert rules engine: rule evaluation, deduplication, auto-resolution, suggested actions, delivery routing, and alert acknowledgement.

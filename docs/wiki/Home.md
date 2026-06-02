# Kinetix Wiki

**Institutional-grade portfolio risk management platform.**

Kinetix covers the full risk lifecycle for a multi-asset trading desk — trade capture, hierarchical pre-trade limits, mark-to-market, live intraday P&L with Greek attribution, VaR/ES across three methodologies, options and rates pricing, scenario and reverse-stress testing, regime-adaptive risk parameters, counterparty exposure with PFE and CVA, FRTB Standardised Approach capital, model governance with four-eyes approval, and a SHA-256 hash-chained audit trail.

This wiki is a living reference for engineers, quants, risk managers, and operators working on the platform. For the canonical README and per-service code, see the [main repository](https://github.com/panayotovk/kinetix).

## Wiki contents

- **[Architecture](Architecture)** — service topology, data flows, communication patterns
- **[Kafka Topology](Kafka-Topology)** — producers → topics → consumers, DLQs, partition keys
- **[Services](Services)** — per-service responsibilities, ownership boundaries, tech inside each box
- **[Risk Methodology](Risk-Methodology)** — quant methods deep dive: VaR/ES, Greeks, factor model, stress, regime detection
- **[FRTB Capital](FRTB-Capital)** — Standardised Approach: SBM, DRC, RRAO; bucket correlations; reporting
- **[Audit and Compliance](Audit-and-Compliance)** — hash-chained audit, four-eyes governance, retention, replay
- **[Observability](Observability)** — `correlationId` join key, dashboards-as-code, how support tracks an event
- **[AI Features](AI-Features)** — Claude Agent SDK integration: v1 explainers (VaR, Reports), v2 Copilot foundation (MCP, citations, policy guard)
- **[Local Development](Local-Development)** — quick start, dev loops, troubleshooting, common gotchas
- **[Testing Strategy](Testing-Strategy)** — test pyramid, Testcontainers patterns, property-based and mutation testing
- **[ADR Index](ADR-Index)** — all 36 architecture decisions with summaries and triggers

## Platform at a glance

| | |
|---|---|
| **Backend** | 12 Kotlin/Ktor microservices on JVM 21 |
| **Risk engine** | Python 3.12 — NumPy, SciPy, PyTorch — 11 gRPC services |
| **AI** | Python `ai-insights-service` (FastAPI) on the Claude Agent SDK; in-process MCP server; citation-enforced narratives |
| **Frontend** | React 19 + TypeScript, 11 trader/risk tabs |
| **Datastores** | PostgreSQL 17 / TimescaleDB (database-per-service), Redis 7 |
| **Messaging** | Apache Kafka 3.9 (KRaft) — 20 production topics, per-topic DLQs |
| **Schema** | 173 Flyway migrations across 11 service schemas |
| **Behavioural specs** | 24 Allium v3 specifications |
| **Architecture decisions** | 36 ADRs |
| **Tests** | 915 across Kotlin (Kotest), Python (pytest), UI (Vitest + Playwright) |
| **Observability** | Prometheus, Grafana, Loki, Tempo, OpenTelemetry |

## Engineering principles

1. **Reproducibility over convenience.** Every risk calculation captures inputs as a run manifest. Any VaR result can be replayed bit-for-bit.
2. **Hard boundaries between services.** Each service owns its database. Cross-service data flows through Kafka or HTTP — never a shared schema.
3. **Pure-function calculators.** The Python risk engine is stateless. The orchestrator owns all market-data discovery and fetching. Inputs in, results out.
4. **Tamper-evident by default.** Critical events (trades, model decisions, scenario approvals, EOD promotions) flow through a hash-chained audit trail.
5. **Specs are contracts.** 24 Allium specifications encode behavioural invariants. Code that diverges from a spec is a bug — in code or in the spec.
6. **ADRs are guardrails, not history.** Every ADR has imperative `Rules:` that the code must satisfy and an `Applies when:` trigger list.

## Where to go next

- A new contributor should start with the [main README](https://github.com/panayotovk/kinetix), then [Local Development](Local-Development), then the project's `CLAUDE.md` for conventions.
- A risk reviewer should jump to [Risk Methodology](Risk-Methodology) and [FRTB Capital](FRTB-Capital).
- An architect should read [Architecture](Architecture) and the [ADR Index](ADR-Index).
- An auditor should start with [Audit and Compliance](Audit-and-Compliance).

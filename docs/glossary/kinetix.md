# Kinetix System Glossary

Terms, patterns, and concepts specific to the Kinetix platform and how it implements financial risk management.

---

## Trade Lifecycle

| Term | Definition |
|------|-----------|
| **Trade Booking** | Initial entry of a trade into the position-service. Creates a position (or updates an existing one) and emits a `TradeEvent` to Kafka. |
| **Trade Status** | `LIVE` (active), `AMENDED` (modified), `CANCELLED` (terminated). Only `LIVE` trades count toward positions and limits. |
| **Trade Type** | `BUY` or `SELL`. Determines how quantity and P&L are applied to the position. |
| **Trade Amend** | `PUT /api/trades/{id}` — modifies a live trade's quantity or price. The original trade is marked `AMENDED`, a new `LIVE` trade is created, and the position is recalculated. |
| **Trade Cancel** | `DELETE /api/trades/{id}` — terminates a live trade. The trade is marked `CANCELLED`, and its contribution to the position (quantity, realised P&L) is reversed. |
| **TradeEvent** | Kafka message on the `trades.lifecycle` topic capturing a trade action. `TradeEventType` values: `NEW`, `AMEND`, `CANCEL`. Carries a `correlationId` for cross-service tracing. |
| **TradeLifecycleService** | Position-service component responsible for amend and cancel operations, including P&L reversal and position recalculation. |

## Position Model

| Term | Definition |
|------|-----------|
| **Position** | Net holding of an instrument within a book. Aggregated from all `LIVE` trades. Stores quantity, average price, market value, unrealised P&L, and realised P&L. |
| **InstrumentType** | Sealed interface hierarchy in the `common` module with 11 typed subtypes (CashEquity, GovernmentBond, CorporateBond, FxSpot, FxForward, EquityOption, EquityFuture, CommodityFuture, CommodityOption, FxOption, InterestRateSwap). Each subtype has typed attributes (e.g. `OptionAttributes`, `BondAttributes`). |
| **Instrument Master** | Reference-data-service table storing instrument definitions with type-specific attributes in a JSONB column. |
| **Realised P&L** | Computed at trade execution time by `applyTrade()` in position-service. Uses FIFO-style calculation against average entry price. |
| **Position Grid** | UI table on the Positions tab. Supports pagination (50 rows/page), column visibility toggles, and CSV export. |
| **Position Notes** | Free-text annotations attached to an individual position, managed in the UI via `usePositionNotes` — lets traders record context or decisions around a holding. |

## Limit Management

| Term | Definition |
|------|-----------|
| **Limit Hierarchy** | Logical hierarchy `FIRM -> DIVISION -> DESK -> BOOK / TRADER / COUNTERPARTY`, encoded as flat `LimitLevel` enum values. Cascade enforcement (a child cannot breach its parent) is implemented in `LimitHierarchyService`, which walks parent limits up the tree at pre-trade check time. |
| **LimitCheckService** | Position-service component that validates pre-trade limits before booking. Checks position, notional, and concentration limits. The hierarchical variant is `HierarchyBasedPreTradeCheckService`, which delegates to `LimitHierarchyService`. |
| **Position Limit** | Maximum quantity of a single instrument. |
| **Notional Limit** | Maximum exposure in monetary terms (quantity * price). |
| **Concentration Limit** | Maximum percentage of portfolio value in a single instrument. |
| **Counterparty Limit** | Maximum aggregate exposure to a single counterparty across all netting sets. |
| **Intraday Limit** | Tighter threshold enforced during trading hours. |
| **Overnight Limit** | Threshold for positions held past end-of-day. |
| **Temporary Limit Increase** | Time-bounded exception with an expiration timestamp. Auto-expires and reverts to the base limit. |
| **Limit Warning** | Triggered when exposure reaches a configurable threshold (default 80%) of the limit. |
| **Limit Breach** | Exposure exceeds the limit. Trade is rejected pre-trade; existing breaches are flagged for remediation. |

## Order Execution & FIX

| Term | Definition |
|------|-----------|
| **FIX-Gateway** | Kotlin service that bridges FIX-protocol order flow with gRPC. Exposes `PlaceOrder`, `CancelOrder`, and `IsVenueOpen`; runs a QuickFIX/J Initiator per venue; persists session state to Postgres; publishes execution reports to Kafka. gRPC server on port 9105. |
| **Trading Venue** | An exchange the platform routes orders to. `VenueSessionRegistry` configures FIX 4.4 sessions for NYSE, NASDAQ, LSE, TSE, and HKEX; `VenueCutoffRegistry` holds each venue's trading-hour cutoffs (used by `IsVenueOpen`). |
| **FIX Session State** | Per-venue sender/target sequence numbers persisted in the `fix_session_state` table. `FixSessionManager` recovers or initialises this on startup so sessions resume without sequence gaps. Inbound/outbound business messages are logged to `fix_message_log`. |
| **Order Placement** | UI workflow (`useOrderPlacement`) that submits an order and tracks its outcome: `success` (PENDING_NEW acknowledgement), `failed` (retryable), `duplicate` (blocked while an RPC is in flight), or `rejected` (terminal, no retry). |
| **Execution Report** | Inbound FIX message reporting order status (ack, fill, cancel, reject). `InboundFixHandler` parses it; `ExecutionReportPublisher` republishes it as an `ExecutionReportEvent` on the `execution.reports` topic, consumed by position-service to book the resulting trade. |
| **Risk Break** | A FIX order-flow risk anomaly — e.g. a ghost fill (an execution report with no matching outbound order). Published by position-service as a `RiskBreakEvent` on the `risk.breaks` topic, keyed by order id for deterministic dedup. |
| **Round-Trip Latency** | Order acknowledgement latency tracked by `PendingNewCorrelator`, which correlates each outbound order with its first inbound execution report. |
| **FIX Session Event** | Connectivity event from a FIX trading session (logon, logout, gap-fill, sequence-reset). Published by position-service on `fix.session.events` for operational monitoring. |

## Risk Calculation Pipeline

| Term | Definition |
|------|-----------|
| **Risk-Orchestrator** | Kotlin service that coordinates risk calculations. Fetches positions (via HTTP), enriches them with instrument data, and dispatches to the risk-engine via gRPC. |
| **Risk-Engine** | Python service (`risk-engine/src/kinetix_risk/`) that performs all quantitative calculations: VaR, Greeks, Monte Carlo, stress testing, FRTB capital, counterparty risk. Communicates via gRPC using proto definitions. |
| **Position Resolver** | `position_resolver.py` — converts proto position messages into typed Python position objects (BondPosition, OptionPosition, FuturePosition, etc.) based on instrument type. |
| **Deterministic VaR** | When seed > 0, Monte Carlo produces repeatable results. Seed = 0 is non-deterministic. Controlled via the `seed` field in the gRPC request. |
| **VaR Cache** | Interface (`VaRCache`) with two implementations: `RedisVaRCache` (shared, uses Lettuce client) and `InMemoryVaRCache` (per-instance fallback). `LatestVaRCache` is a typealias for `InMemoryVaRCache` used to hold the most recent result per book. Keyed by portfolio + calculation parameters. |
| **Cross-Book VaR Cache** | Parallel cache (`CrossBookVaRCache` / `InMemoryCrossBookVaRCache`) for multi-book aggregate VaR, populated by `CrossBookVaRCalculationService` and `ScheduledCrossBookVaRCalculator`. |
| **RiskResultEvent** | Kafka message on the `risk.results` topic. Consumed by notification-service (WebSocket push), position-service (snapshot storage), and ai-insights-service (intraday push alerts). Reconciliation breaks reuse this schema with `calculationType = "RECONCILIATION_BREAK"`. |

## Event Architecture

| Term | Definition |
|------|-----------|
| **TradeEvent** | Published by position-service when a trade is booked, amended, or cancelled. |
| **PriceEvent** | Published by price-service when new market data is ingested. |
| **RiskResultEvent** | Published by risk-orchestrator after a risk calculation completes. |
| **ExecutionReportEvent** | Published by fix-gateway on `execution.reports` when an inbound FIX execution report arrives; consumed by position-service. |
| **RetryableConsumer** | Common-module wrapper for Kafka consumers. Provides exponential backoff retry (base delay * 2^attempt) with configurable max retries (default 3) before routing to a DLQ. |
| **DLQ (Dead Letter Queue)** | Dedicated Kafka topic, named by `RetryableConsumer` as `<source-topic>.dlq` (e.g. `trades.lifecycle.dlq`, `price.updates.dlq`, `risk.results.dlq`). Receives messages that failed all retry attempts. Audit-service exposes a `DlqReplayService` for investigation and re-publication. |
| **Correlation ID** | UUID assigned at the source of an event (e.g. trade booking) and propagated through all downstream events and service calls. Enables cross-service tracing. |

## Audit Trail

| Term | Definition |
|------|-----------|
| **Hash Chain** | Each audit event's hash is computed from its own data plus the previous event's hash (SHA-256). This creates a tamper-evident chain — modifying any past event invalidates all subsequent hashes. |
| **AuditHasher** | Audit-service component that computes and verifies hash chains. |
| **Audit Event Types** | `TRADE_BOOKED`, `TRADE_AMENDED`, `TRADE_CANCELLED`, `RISK_CALCULATED`, `LIMIT_BREACHED`, `SCENARIO_APPROVED`, `MODEL_APPROVED`, `SUBMISSION_PREPARED`, `SUBMISSION_APPROVED`. |
| **Governance Audit Topic** | `governance.audit` — Kafka topic that any service can publish governance-relevant actions to (e.g. approvals, rejections, role changes). Consumed by audit-service's `GovernanceAuditEventConsumer`, which folds these into the same hash-chained `audit_events` store. Publishers exist in gateway, regulatory-service, risk-orchestrator, and notification-service. |
| **Verify Endpoint** | `GET /api/audit/verify` — walks the hash chain and reports any integrity violations. |
| **Retention Policy** | Audit data retained for 7 years (`audit_events`, enforced by `add_retention_policy('audit_events', INTERVAL '7 years')`). Yield curves and rates: 7 years (2555 days). Prices: 2 years. Valuation jobs: 1 year (extended in V17 migration to align with audit). Alert events: 1 year. All enforced by TimescaleDB `add_retention_policy`. |

## Model Governance

| Term | Definition |
|------|-----------|
| **Model Version** | Regulatory-service tracks all risk model releases with version, description, approval status, and approval chain. |
| **Model Approval Workflow** | `DRAFT -> PENDING_REVIEW -> APPROVED / REJECTED`. Requires four-eyes principle (preparer != approver). |
| **Regulatory Submission** | `DRAFT -> PREPARED -> SUBMITTED -> ACCEPTED / REJECTED`. The prepare and submit steps must be performed by different users (four-eyes). |
| **Backtest Result** | Stored output of Kupiec POF and Christoffersen tests, linked to a specific model version and time window. |

## Stress Testing

| Term | Definition |
|------|-----------|
| **Scenario Category** | `HISTORICAL` (based on past events like "Equity Crash 2020") or `CUSTOM` (user-defined shocks). |
| **Scenario Approval** | Regulatory scenarios require four-eyes sign-off before use in official reporting. Managed via the regulatory-service. |
| **Stress Limit Breach** | When a stressed risk metric (e.g. stressed VaR) exceeds a scenario-specific threshold. |
| **Stress Window** | A named historical stress period (e.g. "2020-analog", "2022-analog") baked into the demo `RegimeCalendar` so historical-VaR queries automatically span it. Surfaced in the UI via `useStressWindows`. |
| **Historical Replay** | Risk-engine module `historical_replay.py` (`run_historical_replay`) that applies historical — or asset-class proxy — daily returns to static positions, producing per-position and aggregate P&L impacts. |

## Counterparty Risk (Kinetix-specific)

| Term | Definition |
|------|-----------|
| **Netting Agreement Types** | `ISDA_2002`, `ISDA_1992`, `GMRA` (Global Master Repurchase Agreement). Stored in reference-data-service. |
| **Counterparty Risk View** | UI panel aggregating exposure by counterparty across netting sets, showing PFE, EPE, CVA, and netting benefit. |
| **Wrong-Way Risk Flag** | Heuristic indicator when a counterparty's sector correlates with the exposure direction. |

## Cross-Book & Aggregate Risk

| Term | Definition |
|------|-----------|
| **Cross-Book VaR** | Aggregate VaR across multiple books, computed by `CrossBookVaRCalculationService` in risk-orchestrator and `cross_book_var.py` in risk-engine. Results published on `risk.cross-book-results` and cached via `CrossBookVaRCache`. |
| **Scheduled VaR Calculator** | Background job (`ScheduledVaRCalculator`) in risk-orchestrator that triggers periodic VaR recalculation per book, independent of trade-driven recalcs. `ScheduledCrossBookVaRCalculator` is the cross-book equivalent. |
| **Hierarchy Risk View** | Aggregated risk across the firm/division/desk/book hierarchy, served by `HierarchyRiskService`. The UI Hierarchy Navigator drills down through the same tree. |

## Intraday & EoD Workflows

| Term | Definition |
|------|-----------|
| **Intraday P&L** | Continuously updated P&L stream computed by `IntradayPnlService` in risk-orchestrator, published on `risk.pnl.intraday`, and consumed by gateway for the trader UI. Attributes P&L against frozen SoD Greeks, falling back to VaR sensitivities when analytical pricing Greeks are unavailable. |
| **Intraday VaR Timeline** | Time-series of VaR snapshots within the trading day, served by `IntradayVaRTimelineService`. |
| **SoD (Start-of-Day) Snapshot** | Captured by `SodSnapshotService` and `ScheduledSodSnapshotJob`. Provides the immutable opening positions and risk state used as the baseline for intraday P&L attribution. |
| **EoD (End-of-Day) Promotion** | `EodPromotionService` finalises the day's official risk numbers and publishes them on `risk.official-eod`. |
| **Official EoD Run** | The blessed end-of-day risk and P&L computation, distinct from intraday recalculations. Becomes the next day's SoD baseline. |

## P&L Attribution

| Term | Definition |
|------|-----------|
| **P&L Attribution** | Decomposition of a book's daily P&L into Greek components (delta, gamma, vega, theta, rho) plus an unexplained residual. Derived by `PnLAttributionDeriver` in risk-orchestrator; modelled as `PnlAttribution` (book level) and `PositionPnlAttribution` (per position). |
| **Pricing Greeks** | Analytical, closed-form Greeks (delta, gamma, vega, theta, rho, vanna, volga, charm for options; DV01 for bonds; identity delta for linear instruments) computed by the risk-engine `CalculatePricingGreeks` gRPC method — distinct from VaR-derived sensitivities. Called from risk-orchestrator via `PricingGreeksClient`. |
| **SoD Greek Snapshot** | Analytical Greeks frozen at start-of-day (`SodGreekSnapshotRepository`), used as the immutable baseline for intraday P&L attribution. |
| **Unexplained P&L** | The attribution residual — P&L not accounted for by first- and second-order Greek terms. Captures slippage, fees, model error, and higher-order Greeks. |

## Rebalancing & What-If Analysis

| Term | Definition |
|------|-----------|
| **What-If Analysis** | Evaluates how a set of hypothetical trades would change a portfolio's Greeks and risk metrics (VaR, ES) against the current baseline. Backed by `WhatIfAnalysisService` in risk-orchestrator and the UI `useWhatIf` hook (form state persisted to sessionStorage). |
| **Rebalancing What-If** | `RebalancingWhatIfService` analyses a set of `RebalancingTrade`s — computing marginal VaR/ES contributions per trade, applying them hypothetically, recalculating Greeks, and estimating execution cost. Returns base vs. rebalanced VaR plus per-trade contributions. Driven from the UI via `useRebalancing`. |

## Hedging & Recommendations

| Term | Definition |
|------|-----------|
| **Hedge Recommendation** | Suggested trade(s) to neutralise a specified Greek exposure. Produced by `HedgeRecommendationService` (risk-orchestrator) using `AnalyticalHedgeCalculator` and the risk-engine `hedge_optimizer.py`. Surfaced in the UI via `HedgeRecommendationPanel`. |
| **Hedge Optimizer** | Risk-engine module (`hedge_optimizer.py`) that solves for the hedge instrument mix that minimises a chosen risk metric subject to constraints. |

## Reconciliation

| Term | Definition |
|------|-----------|
| **Prime Broker Reconciliation** | `PrimeBrokerReconciliationService` compares internal positions against PB statements. Critical breaks (notional > $10K) raise a `RECONCILIATION_BREAK` alert published as a `RiskResultEvent` on the `risk.results` topic. |

## Anomaly Detection & Market Regime

| Term | Definition |
|------|-----------|
| **Risk Anomaly** | Statistical outlier in risk metrics (e.g. unexpected VaR jump). Published on `risk.anomalies` and consumed by notification-service for alerting. |
| **Market Regime** | Classification of current market conditions. The risk-engine `regime_detector.py` is a rule-based classifier producing a `MarketRegime` of `NORMAL`, `ELEVATED_VOL`, `CRISIS`, or `RECOVERY` from `RegimeSignals` (realised vol, cross-asset correlation, credit spreads, P&L volatility). Risk-orchestrator persists regime state (`MarketRegimeRepository`, `AdaptiveRegimeParameterProvider`) and publishes changes on `risk.regime.changes`; the UI exposes this through `useMarketRegime`. |
| **Adaptive Regime Parameters** | Risk model parameters (e.g. EWMA lambda, correlation half-life) that adjust automatically based on the detected market regime. |

## Liquidity Risk

| Term | Definition |
|------|-----------|
| **Liquidity Risk Service** | `LiquidityRiskService` in risk-orchestrator computes liquidation horizons, market-impact estimates, and concentration metrics for positions. |
| **Liquidity Concentration Alert** | Triggered when a single instrument or counterparty exceeds a liquidity-tier-specific threshold. Published on `risk.results` via `KafkaLiquidityConcentrationAlertPublisher`. |
| **LiquidityRiskEvent** | Schema (`common/.../LiquidityRiskEvent.kt`) for the future `liquidity.risk.results` topic carrying per-position liquidity assessments. |

## AI Insights & Copilot

| Term | Definition |
|------|-----------|
| **AI-Insights-Service** | Python (FastAPI) service that generates LLM-backed explanations for risk surfaces. Routes through the Claude Agent SDK using the host's Claude Code subscription — no per-token API billing. Falls back to deterministic canned responses when `DEMO_MODE` is set. |
| **Copilot** | Umbrella name for Kinetix's AI assistant surface: inline "Explain" insights, conversational chat, the daily Morning Brief, and intraday push alerts — all served by ai-insights-service. |
| **Insight Kinds** | `var` (VaR explanation), `report` (regulatory report commentary), `brief` (daily morning brief), and `chat` (multi-turn conversational Q&A). Exposed under `/api/v1/insights/*`; `chat` streams over Server-Sent Events. |
| **Morning Brief** | A Copilot-generated summary of the day's risk and trading outlook. Pre-generated at 06:30 UTC by the brief scheduler, retrievable via `GET /api/v1/insights/brief/today`, and surfaced in the UI notification strip on first inbox open of the day. |
| **MCP Server (`kinetix-copilot`)** | A FastMCP (Model Context Protocol) server inside ai-insights-service exposing ~10 read-only tools (positions, limits, VaR, Greeks, P&L attribution, stress scenarios, alert thresholds, etc.) so AI agents can pull live Kinetix data. |
| **Intraday Push Alerts** | Threshold-driven alerts pushed to the Copilot inbox. `IntradayKafkaConsumer` consumes `risk.results` and `risk.regime.changes`; `IntradayThresholdEvaluator` fires `VAR_BREACH`, `POSITION_DELTA`, and `LIMIT_UTILISATION` alerts with per-(book, alert-type) cooldown de-duplication. |
| **Copilot Alert Thresholds** | Configuration table (`copilot_alert_thresholds`, risk-orchestrator) scoping alert thresholds by `GLOBAL` / `BOOK` / `USER`, with a per-alert-type `threshold_value` and `cooldown_minutes`. |
| **Command Palette** | UI Cmd+K / Ctrl+K launcher for tabs, sub-tabs, books, instruments, and scenarios. In Copilot mode, free-form questions that match no command are routed to the chat endpoint and answered inline with streaming narrative and citations. |
| **AI Insight Panel** | Reusable UI slide-over (`AIInsightPanel`) rendering AI-generated insights — buffered narrative + bullets, or streamed chat chunks with citations. Used by the VaR dashboard "Explain" button and the Reports tab AI commentary. |
| **Notification Strip / Copilot Inbox** | Collapsible UI bar that summarises notifications (severity chips + unread count) and expands into a scrollable inbox; surfaces the Morning Brief and intraday push alerts. |
| **Copilot Context** | UI plumbing (`CopilotContextProvider` / `useCopilotContext`) that captures the active tab, selected book, and active scenario as `page_context`, so AI requests know where the user is without prop-drilling. |

## Demo & Test Tooling

| Term | Definition |
|------|-----------|
| **Demo-Orchestrator** | Kotlin service that drives demo data flows on a schedule: seeds limits (`LimitSeedJob`), simulates trades during trading hours (`SimulatedTraderJob`), captures SoD baselines (`SodBaselineCaptureJob`), and promotes the official EoD (`EodPromotionJob`). Observes `risk.official-eod` via `OfficialEodConsumer`. |
| **Demo Book Profile** | Per-book demo configuration (`equity-growth`, `fx-main`, `commodity`) defining instruments, trade probabilities, and notional ranges used by the simulated trader. |
| **Tape Replay** | Demo mode in which historical market data ("the tape") is replayed on a loop. The UI shows `LIVE` (production feed), `ACTIVE` (replaying), or `FROZEN` (static) via `useTapeReplayStatus` and the header `TapeReplayIndicator`. |
| **Active Scenario** | The currently loaded demo scenario (`multi-asset`, `equity-ls`, `options-book`, `stress`, `regulatory`). The UI polls `useActiveScenario` to detect operator-triggered resets and surfaces it via the `ScenarioIndicator` badge. |
| **Load-Tests** | Gatling-based load suite (`GatewaySimulation`, `StressTestSimulation`) exercising gateway health, portfolio listing, and VaR endpoints under ramping and sustained user load. |
| **Smoke-Tests** | Kotest suite validating end-to-end trading, risk calculation, infrastructure, UI routing, and rolling-update behaviour against a live deployed stack. |

## Infrastructure

| Term | Definition |
|------|-----------|
| **API Gateway** | Kotlin/Ktor service aggregating backend service calls for the UI. All UI HTTP requests route through the gateway. |
| **Notification Service** | Consumes `risk.results`, `risk.cross-book-results`, `risk.anomalies`, `risk.regime.changes`, `limits.breaches`, `price.updates`, and `trades.lifecycle` Kafka topics and pushes updates to the UI via WebSocket. |
| **TimescaleDB** | PostgreSQL extension used for time-series tables (prices, valuation jobs, audit events, risk snapshots). Provides automatic partitioning, compression, and retention policies. |
| **Continuous Aggregate** | TimescaleDB materialised view that pre-computes summaries. Kinetix uses hourly VaR summaries and daily P&L summaries. |
| **Flyway Migration** | SQL schema versioning. Migrations run inside PostgreSQL transactions — `CREATE INDEX CONCURRENTLY` and similar transaction-incompatible statements must not be used. |
| **Circuit Breaker** | Three-state pattern (CLOSED -> OPEN -> HALF_OPEN) wrapping HTTP clients. Opens after consecutive failures (default 5), resets after a timeout (default 30s). Prevents cascading failures between services. |
| **WebSocket Auto-Reconnect** | UI reconnection with exponential backoff, max 20 attempts. Displays a "reconnecting" banner during disconnection. |

## UI Concepts

| Term | Definition |
|------|-----------|
| **Dark Mode** | Class-based Tailwind theme toggle. Persisted to localStorage via the `useTheme` hook. |
| **Column Visibility Toggles** | Gear dropdown on the position grid allowing users to show/hide columns. Selection persisted to localStorage. |
| **CSV Export** | Available on all data tabs (positions, risk, P&L, alerts). Uses a shared `exportToCsv` utility. |
| **Workspace Customisation** | Layout and preference persistence via localStorage. Users can configure which panels are visible and their arrangement. |
| **Data Quality Indicator** | Traffic-light indicator showing staleness of market data feeds. Green = fresh, amber = stale, red = disconnected. |
| **Alert Rules** | User-defined thresholds (e.g. "notify if VaR exceeds $1M"). Deletion requires confirmation via ConfirmDialog. |
| **Multi-Portfolio Picker** | UI component allowing selection of multiple portfolios for aggregate risk/P&L views. |
| **Sub-Tab Bar** | Styled inner-tab navigation (`SubTabBar`) for sections within a parent tab — e.g. Trades -> Blotter / Place / Cost / Reconciliation, Risk -> Dashboard / Intraday / Market Data — with count badges. |
| **Stale Panel Wrapper** | UI wrapper (`StalePanelWrapper`) that tints a panel amber and renders provenance timestamps (computed-at, source-as-of) when its data is stale, so operators can judge freshness. |
| **Trader Selector** | Toolbar dropdown filtering positions, P&L, and risk views to a single trader. Trader reference data is loaded via `useTraders`, backed by the `trader_lookup` gRPC contract. |
| **Snapshot Compare** | Risk-dashboard control that compares the current VaR against a time-shifted point (-15m, -1h, EOD yesterday) and shows the numeric delta, reusing the already-loaded intraday VaR timeline. |
| **Yield Curve View** | UI panel showing the per-currency yield curve, loaded via `useYieldCurve`. |

## Kafka Topics

| Topic | Publisher | Consumers |
|-------|-----------|-----------|
| `trades.lifecycle` | position-service | risk-orchestrator, audit-service, notification-service |
| `price.updates` | price-service | risk-orchestrator, position-service, notification-service |
| `execution.reports` | fix-gateway (`KafkaExecutionReportPublisher`) | position-service (`ExecutionReportConsumer`) |
| `risk.results` | risk-orchestrator (`KafkaRiskResultPublisher`, plus budget / factor / liquidity alert publishers); position-service (reconciliation breaks) | notification-service, position-service, ai-insights-service (`IntradayKafkaConsumer`) |
| `risk.cross-book-results` | risk-orchestrator (`KafkaCrossBookRiskResultPublisher`) | notification-service |
| `risk.pnl.intraday` | risk-orchestrator (`KafkaIntradayPnlPublisher`) | gateway (`KafkaIntradayPnlConsumer`) |
| `risk.official-eod` | risk-orchestrator (`KafkaOfficialEodPublisher`) | demo-orchestrator (`OfficialEodConsumer`), downstream EoD consumers |
| `risk.regime.changes` | risk-orchestrator (`KafkaRegimeEventPublisher`) | notification-service (`MarketRegimeEventConsumer`), ai-insights-service (`IntradayKafkaConsumer`) |
| `risk.anomalies` | risk-orchestrator | notification-service (`AnomalyEventConsumer`) |
| `risk.audit` | risk-orchestrator (`KafkaRiskAuditPublisher`) | audit-service |
| `risk.breaks` | position-service (`KafkaRiskBreakPublisher`) | (operational — FIX order risk breaks; no consumer yet) |
| `limits.breaches` | position-service (`KafkaLimitBreachEventPublisher`) | notification-service (`LimitBreachEventConsumer`) |
| `governance.audit` | gateway, regulatory-service, risk-orchestrator, notification-service | audit-service (`GovernanceAuditEventConsumer`) |
| `correlation.matrices` | correlation-service (`KafkaCorrelationPublisher`) | (market-data fan-out; no Kafka consumer) |
| `volatility.surfaces` | volatility-service (`KafkaVolatilityPublisher`) | (market-data fan-out; no Kafka consumer) |
| `rates.yield-curves`, `rates.risk-free`, `rates.forwards` | rates-service (`KafkaRatesPublisher`) | (market-data fan-out; no Kafka consumer) |
| `reference-data.dividends`, `reference-data.credit-spreads` | reference-data-service (`KafkaReferenceDataPublisher`) | (reference-data fan-out; no Kafka consumer) |
| `liquidity.risk.results` | (schema defined: `LiquidityRiskEvent`; publisher pending — liquidity alerts currently go to `risk.results`) | notification-service liquidity extractor (pending) |
| `fix.session.events` | position-service (`KafkaFIXSessionEventPublisher`) | (operational monitoring) |
| `*.dlq` | `RetryableConsumer` (any topic above) | audit-service `DlqReplayService` for investigation/replay |

> Note: `audit-service` does not publish a Kafka topic — it persists audit events to the `audit_events` TimescaleDB hypertable with hash-chained integrity. The market-data and reference-data topics (`correlation.matrices`, `volatility.surfaces`, `rates.*`, `reference-data.*`) are published for downstream fan-out and observability; risk-orchestrator currently sources that data over HTTP/gRPC rather than Kafka.

## gRPC Contracts

| Service | Proto File(s) | Purpose |
|---------|--------------|---------|
| risk-engine | `proto/src/main/proto/kinetix/risk/*.proto` — `risk_calculation.proto` (VaR, Greeks, `CalculatePricingGreeks`), `stress_testing.proto`, `counterparty_risk.proto`, `liquidity.proto`, `attribution.proto`, `ml_prediction.proto`, `regulatory_reporting.proto`, `market_data_dependencies.proto` | VaR, Greeks, analytical pricing Greeks, stress testing, FRTB, counterparty and liquidity risk, P&L attribution, ML prediction |
| fix-gateway | `proto/src/main/proto/kinetix/execution/fix_gateway.proto` | `PlaceOrder`, `CancelOrder`, `IsVenueOpen` — order routing to FIX venues |
| reference-data-service | `proto/src/main/proto/kinetix/referencedata/trader_lookup.proto` | Trader reference-data lookup |

Positions are sent to the risk-engine as proto messages enriched with `instrument_type` and type-specific attribute fields, which the Python `position_resolver.py` converts into typed position objects for calculation.

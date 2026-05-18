# ADR-0004: Use Apache Kafka for Asynchronous Messaging

## Status
Accepted

## Context
Services need asynchronous communication for event-driven workflows: market data distribution, trade lifecycle events, risk calculation results, and audit trail ingestion. Options: Apache Kafka, RabbitMQ, Redis Streams, NATS.

## Decision
Use Apache Kafka 3.9.0 in KRaft mode (no ZooKeeper dependency).

## Applies when
- Producing or consuming an async event between services.
- Adding a new Kafka topic, changing partition count, or changing a key schema.
- Choosing between Kafka, an HTTP call, and a gRPC call for service-to-service communication.

## Rules
- **DO** publish a new topic by adding it to `infra/kafka/create-topics.sh` so all environments provision it idempotently. Don't rely on auto-create.
- **DO** define event payloads as `@Serializable` data classes in `common/` (or proto where Python is a consumer). One event type per file.
- **DO** wrap consumers in `RetryableConsumer` (ADR-0014) so transient failures retry and poison messages route to `<topic>.dlq`.
- **DO** add a DLQ topic for every consumer-bearing topic, named `<topic>.dlq`, and provision it in `create-topics.sh`.
- **DO** propagate `correlationId` end-to-end (ADR-0022) on every event payload.
- **DO** pick partition keys that preserve per-entity ordering (e.g. `clOrdID`, `bookId`, `instrumentId`). Don't partition by random UUIDs unless ordering is genuinely irrelevant.
- **DON'T** use Kafka for synchronous request/reply (use gRPC or HTTP). Don't invent reply-topic correlation patterns.
- **DON'T** add a new topic for a one-off use case before checking whether an existing core topic (`trades.lifecycle`, `price.updates`, `risk.results`) already covers it.
- **DON'T** rename a topic in-place; create the new topic, dual-publish, switch consumers, then deprecate the old one.

## Consequences

### Positive
- Durable, replayable event streams — critical for audit trail reconstruction and reprocessing failed calculations
- High throughput for market data tick distribution (millions of messages/second)
- Built-in partitioning for parallel consumption
- Consumer groups allow independent scaling of consumers per topic
- KRaft mode eliminates ZooKeeper operational overhead

### Negative
- Higher operational complexity than RabbitMQ
- Higher latency for individual messages compared to RabbitMQ (milliseconds vs microseconds) — acceptable for our use cases
- Heavier resource footprint in local dev

### Kafka Topics (20 total)

**Core:**
- `trades.lifecycle` — Trade events (consumers: risk-orchestrator, audit-service)
- `price.updates` — Price updates (consumers: risk-orchestrator, position-service)
- `risk.results` — Completed risk calculations (consumers: notification-service)

**Risk:**
- `risk.anomalies` — Anomaly detection events (consumers: notification-service)
- `risk.audit` — Risk run audit events (provisioned via `infra/kafka/create-topics.sh`)
- `risk.pnl.intraday` — Intraday P&L events
- `risk.regime.changes` — Market regime change events

**Rates:**
- `rates.yield-curves` — Yield curve snapshots
- `rates.risk-free` — Risk-free rate updates
- `rates.forwards` — Forward curve updates

**Reference data:**
- `reference-data.dividends` — Dividend yield updates
- `reference-data.credit-spreads` — Credit spread updates

**Market data:**
- `volatility.surfaces` — Volatility surface snapshots
- `correlation.matrices` — Correlation matrix snapshots

**Governance:**
- `governance.audit` — Governance and approval workflow audit events

**Dead-letter queues:**
- `trades.lifecycle.dlq`
- `price.updates.dlq`
- `risk.results.dlq`
- `risk.anomalies.dlq`
- `governance.audit.dlq`

### Alternatives Considered
- **RabbitMQ**: Lower latency per message, simpler operations, but lacks replay capability. Audit trail and reprocessing require durable streams.
- **Redis Streams**: Lightweight but limited in durability guarantees and consumer group management at scale.
- **NATS**: Fast but lacks the durability and ecosystem maturity of Kafka for financial workloads.

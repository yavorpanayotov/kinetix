# ADR-0006: Selective Event Sourcing for Trade Lifecycle

## Status
Accepted

## Context
Financial systems require auditability and the ability to reconstruct historical state. Event sourcing (storing every state change as an immutable event) is a natural fit for some domains but adds complexity. We need to decide where to apply it.

## Decision
Apply event sourcing selectively:
- **position-service** (trade lifecycle): Every trade mutation stored as an immutable event. Current position is a projection.
- **audit-service**: Append-only event store consuming from all Kafka topics.
- **All other services**: Standard CRUD with PostgreSQL.

## Applies when
- Designing persistence for a new entity type — choosing between event-sourced and CRUD.
- Adding a new mutation to a trade, order, or audit entity.
- Tempted to "fix" event-sourced data with an UPDATE/DELETE.

## Rules
- **DO** model every trade lifecycle mutation in `position-service` as an immutable `trade_event` row. Compute current position state as a projection.
- **DO** append-only in `audit-service` — every row is sealed by hash chain (ADR-0017).
- **DO** use standard CRUD in price-service, rates-service, volatility-service, correlation-service, reference-data-service, notification-service, regulatory-service, risk-orchestrator. Event sourcing here adds complexity without auditability benefit.
- **DO** version event schemas explicitly (e.g. `event_type`, `schema_version`) when adding new fields to an event-sourced table.
- **DON'T** issue `UPDATE`/`DELETE` against `trade_events` or `audit_events`. To correct a trade, append a corrective event. To correct an audit entry, append a new entry referencing the prior one — never break the chain.
- **DON'T** propose new event-sourced tables outside position-service and audit-service without architectural review.

## Consequences

### Positive
- Full audit trail for trades — regulatory requirement for Basel III/IV
- Point-in-time position reconstruction ("what was our exposure at 3:45 PM on March 15?")
- Event replay enables reprocessing and debugging
- Simpler services where event sourcing doesn't add value (price, regulatory, notification)

### Negative
- Two persistence patterns in the codebase (event-sourced vs CRUD) — developers need to understand both
- Event-sourced projections add complexity (building current state from events, handling projection failures)
- Schema evolution for events requires careful versioning

### Alternatives Considered
- **Full event sourcing everywhere**: Maximum auditability but disproportionate complexity for services like notification or price-service where CRUD is sufficient.
- **No event sourcing (CRUD everywhere + audit table)**: Simpler but loses the ability to reconstruct point-in-time state from events. Audit becomes a secondary concern bolted on rather than a core data model.

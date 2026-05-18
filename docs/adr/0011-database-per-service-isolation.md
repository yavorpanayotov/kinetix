# ADR-0011: Database-per-Service Isolation

## Status
Accepted

## Context
With 11 microservices accessing PostgreSQL, we need a strategy for data isolation. Options: a single shared database with per-service schemas, a single shared database with no isolation, or a separate database per service.

## Decision
Every microservice owns its own database. No service reads or writes another service's database. Inter-service data flows through Kafka topics or synchronous HTTP calls.

The 11 databases are provisioned idempotently by `infra/db/init/01-create-databases.sql`:
- `kinetix_position` — Position Service
- `kinetix_price` — Price Service (TimescaleDB extension enabled)
- `kinetix_risk` — Risk Orchestrator
- `kinetix_audit` — Audit Service
- `kinetix_notification` — Notification Service
- `kinetix_regulatory` — Regulatory Service
- `kinetix_rates` — Rates Service
- `kinetix_reference_data` — Reference Data Service
- `kinetix_volatility` — Volatility Service
- `kinetix_correlation` — Correlation Service
- `kinetix_gateway` — Gateway (reserved, currently unused)

Each service manages its own schema via Flyway migrations under `src/main/resources/db/<service-name>/` and connects through its own HikariCP pool.

## Applies when
- Adding a table that needs data from another service's domain.
- Tempted to JOIN across `kinetix_*` databases or open a connection to another service's DB.
- Choosing where a new piece of state should live (which service owns it?).

## Rules
- **DO** persist each service's data in its own database (`kinetix_<service>`).
- **DO** acquire data from another service via that service's HTTP API, gRPC, or a Kafka event — never via direct DB access.
- **DO** keep migration files under `src/main/resources/db/<service>/` with versions managed in Flyway (ADR-0027).
- **DO** denormalise sparingly when a service needs a read replica of another's data; subscribe to the source service's Kafka topic and project locally.
- **DON'T** add a foreign key, view, or query that crosses `kinetix_*` database boundaries.
- **DON'T** share a connection pool, user, or schema across services. Each service has its own HikariCP pool configured for its workload.
- **DON'T** use `kinetix_gateway` for anything — it is reserved/unused.

## Consequences

### Positive
- Strong isolation: a schema change in one service cannot break another
- Independent migration lifecycles — each service evolves its schema at its own pace
- Connection pools are tuned per workload (e.g., price-service: 20 max, audit-service: 8 max)
- No risk of accidental cross-service JOINs creating hidden coupling

### Negative
- Aggregating data across services requires Kafka events or HTTP calls, not SQL JOINs
- Slightly more operational overhead managing multiple databases (mitigated by a single PostgreSQL instance with the init script)
- Local development requires all databases provisioned at startup

### Alternatives Considered
- **Shared database, per-service schemas**: Lower operational overhead, but schemas are not truly isolated — a misbehaving migration could affect the shared instance. Cross-schema JOINs are tempting and create coupling.
- **Shared database, no isolation**: Simplest operationally, but creates strong coupling between services and makes independent deployment impossible.

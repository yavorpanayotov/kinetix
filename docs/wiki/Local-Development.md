# Local Development

Everything you need to run Kinetix end-to-end on a laptop.

## Prerequisites

- **Java 21** (Temurin recommended)
- **Python 3.12+** with [uv](https://docs.astral.sh/uv/)
- **Node.js 22** with npm
- **Docker** and Docker Compose
- A laptop with at least 16 GB RAM (12 GB usable while the full stack runs)
- Free local ports: 5173, 8080, 8180, 9090, 3000, 5432, 6379, 9092

## Bring the platform up

```bash
./dev-up.sh
```

This script:

1. Starts infrastructure (Postgres, TimescaleDB, Redis, Kafka in KRaft mode, Keycloak)
2. Builds Kotlin services and the Python risk-engine + ai-insights-service images
3. Starts all 12 Kotlin services
4. Starts the risk-engine
5. Starts `ai-insights-service` (port 8095). The Docker entry bind-mounts `~/.claude:/home/kinetix/.claude:ro` so the container reuses your Claude Code subscription. If the mount is missing or `claude` is not authenticated on the host, the service falls back to deterministic canned responses and the UI displays a "Demo mode" badge — set `DEMO_MODE=true` explicitly in CI / Playwright to force this. See [AI Features](AI-Features) for the full credential model.
6. Starts the UI on the Vite dev server

Wait for all services to log "Server is listening" or equivalent. The first run takes 5–10 minutes; subsequent runs are seconds.

## URLs

| URL | Service |
|---|---|
| <http://localhost:5173> | Trading & Risk Dashboard |
| <http://localhost:8080> | Gateway API |
| <http://localhost:3000> | Grafana (admin/admin) |
| <http://localhost:9090> | Prometheus |
| <http://localhost:8180> | Keycloak (admin/admin) |

## Default users

| Username | Password | Role |
|---|---|---|
| trader1 | trader1 | TRADER |
| risk_mgr | risk_mgr | RISK_MANAGER |
| compliance1 | compliance1 | COMPLIANCE |
| admin | admin | ADMIN |

## Try it out

1. Open <http://localhost:5173> and log in as `trader1` / `trader1`.
2. Go to **Trades** and place a buy order — e.g. 1,000 shares of AAPL at the live mid.
3. Switch to **Positions** to see the new line mark-to-market in real time.
4. Switch to **Risk** — VaR, ES, Greeks, limit utilisation recompute within seconds.
5. **Alerts** surfaces any breaches.

You have just exercised the full end-to-end pipeline: UI → gateway → position-service → Kafka → risk-orchestrator → risk-engine (gRPC) → notification-service → UI WebSocket.

## Stop

```bash
./dev-down.sh
```

## Full reset (nuke and re-seed)

```bash
./dev-nuke.sh    # destroys data volumes; next dev-up will re-seed
```

## Common dev loops

### Backend service change (e.g. position-service)

```bash
./gradlew :position-service:build
./dev-restart.sh position-service
```

### Risk-engine change

```bash
cd risk-engine
uv run pytest -m unit             # fast feedback
# rebuild + restart container
docker compose -f ../docker-compose.services.yml restart risk-engine
```

### UI change

The Vite dev server hot-reloads on save. For Playwright E2E:

```bash
cd ui
npx playwright test --ui          # interactive mode
```

### Schema change (Flyway)

1. Add a new `V<timestamp>__<description>.sql` under the service's `src/main/resources/db/<schema>/`
2. Add a rollback file (`<timestamp>__<description>.rollback.sql`)
3. `./gradlew :<service>:build` — Flyway runs on startup
4. See [ADR-0025](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0025-flyway-backward-compatible-migrations.md) and [ADR-0027](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0027-database-migration-practices.md) before writing destructive migrations

## Demo data

```bash
/demo                  # in a Claude Code session
```

Or directly:

```bash
./scripts/demo-reset.sh
```

Demo seed includes:

- 30-counterparty universe across composition tiers
- 3–8 traders per desk across a six-level org hierarchy
- 252-day trade tape with amend/cancel lifecycle events
- Yield curve and vol surface daily snapshots (252 days)
- Scenario branches (equity long-short, stress, options-book)
- Golden SHA-256 fixtures for the tape generator

A demo-reset fan-out concurrently resets state in 8 services; a concurrent reset returns HTTP 409 to prevent overlap.

## Observability

- **Logs:** all services ship to Loki. Query in Grafana → Explore.
- **Metrics:** Prometheus scrape on every service. Per-service dashboards in Grafana under "Kinetix".
- **Traces:** Tempo. Search by correlation ID (visible in the UI footer of every request error).

## Health check

```bash
/health                  # in a Claude Code session
```

Verifies all services are running, Kafka consumers are caught up, databases are connected, market data is fresh.

## Common gotchas

### "Containers won't start" — port conflicts

Likely something else owns 5432, 6379, 9092, 8180, or 5173. `lsof -iTCP:<port> -sTCP:LISTEN` to find the culprit.

### "Risk engine PYTHONPATH error"

The risk-engine Docker image sets `PYTHONPATH=/app/src` because `uv sync` doesn't install the project in editable mode. If you mount source over a stale build, restart the container.

### "Testcontainers fails in `common` module"

Don't put integration tests in `common`. It's a library module without the Docker client classpath. Integration tests live in the service modules. ([CLAUDE.md](https://github.com/panayotovk/kinetix/blob/main/CLAUDE.md) → Known Gotchas)

### "Exposed `shouldThrow` doesn't catch the exception"

Exceptions thrown inside `newSuspendedTransaction` cannot be caught by Kotest's `shouldThrow`. Move validation **before** the `transactional.run{}` block.

### "Flyway migration failed: CREATE INDEX CONCURRENTLY"

`CONCURRENTLY` cannot run inside a transaction, and Flyway runs migrations inside a transaction. Either drop `CONCURRENTLY` or split into a non-transactional migration via configuration.

### "Account suspended" when cloning the wiki

The wiki git repo is initialised lazily by GitHub the first time a page is created via the web UI. If you see this error on a fresh repo, create a page in the wiki first, then clone.

## IDE setup

### IntelliJ IDEA

1. Import the root `build.gradle.kts` as a Gradle project
2. Set Project SDK to JDK 21 (Temurin)
3. Enable Kotest plugin
4. Mark `risk-engine/src/kinetix_risk` as a Sources Root (Python)

### VS Code

For the UI:

1. Open `ui/` as a workspace
2. Recommended extensions: ESLint, Prettier, Tailwind CSS IntelliSense, Playwright Test for VS Code
3. `cd ui && npm install` before opening

## Useful slash commands

Run inside a Claude Code session:

| Command | What it does |
|---|---|
| `/health` | Quick health check across all services |
| `/incident` | Structured incident triage |
| `/deploy` | Full redeploy (stops, rebuilds, restarts) |
| `/demo` | Seed demo data |
| `/dep-audit` | Dependency vulnerability scan |

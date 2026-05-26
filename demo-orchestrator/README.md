# demo-orchestrator

Background scheduler that keeps a Kinetix demo environment alive: it bootstraps
seed data, drips synthetic trades at a configurable cadence during trading
hours, and runs SOD / EOD jobs on cron.

The service is only enabled when `DEMO_MODE=true`. In a production deployment
the chart is disabled (`demo-orchestrator.enabled=false` in `values.yaml`) and
the binary is never started.

Click-path walkthrough scripts for each demo scenario live in
[`docs/demos/`](../docs/demos/README.md). This README covers the runtime
configuration the orchestrator itself consumes.

## Configuration

All knobs are environment variables read by
[`DemoConfig.fromEnv`](src/main/kotlin/com/kinetix/demo/config/DemoConfig.kt).

| Env var | Default | Meaning |
| --- | --- | --- |
| `DEMO_MODE` | `false` | Master switch. Must be `true` for the orchestrator to run any of its jobs. |
| `POSITION_SERVICE_URL` | `http://position-service:8080` | Where to book synthetic trades. |
| `RISK_ORCHESTRATOR_URL` | `http://risk-orchestrator:8080` | Target for SOD / EOD risk recalculations. |
| `REGULATORY_SERVICE_URL` | `http://regulatory-service:8080` | Target for regulatory snapshot jobs. |
| `KAFKA_BOOTSTRAP_SERVERS` | `kafka:9092` | Kafka cluster for trade / risk events. |
| `DEMO_TRADING_HOURS_START` | `09:00` | UTC start of the synthetic trading window (`HH:mm`). |
| `DEMO_TRADING_HOURS_END` | `16:30` | UTC end of the synthetic trading window (`HH:mm`). |
| `DEMO_TRADE_CADENCE_SECONDS` | `90` | Interval between synthetic trade ticks. See profile guidance below. |
| `DEMO_BREACH_BOOK` | `derivatives-book` | Book whose limits are seeded tight so a breach is guaranteed early. See [kx-5q4](../docs/demos/README.md). |
| `DEMO_BREACH_VAR_FACTOR` | `0.50` | VaR-limit factor applied to `DEMO_BREACH_BOOK` (vs. the standard 0.80 used for every other book). Lower = breach sooner. |

## Live-demo vs background-mode tuning

The orchestrator has two intended duty cycles. Pick the cadence to match.

### Background mode (default — `DEMO_TRADE_CADENCE_SECONDS=90`)

Used for the always-on demo environment, nightly cron-reseeded clusters, and
local dev sessions that may run for hours. A 90-second cadence yields ~300
trades per 7.5h trading window, which is enough to keep the blotter visibly
alive without filling Postgres or Kafka faster than retention can prune.

Helm: `deploy/helm/kinetix/values-demo.yaml` ships with `90`.
Compose: `docker-compose.services.yml` ships with `90`.

### Live-demo mode (`DEMO_TRADE_CADENCE_SECONDS=30`)

Used for buyer walkthroughs and screen-recorded demos where the presenter
needs **visible blotter activity within the first ~2 minutes** of opening the
UI. 30 seconds yields a new trade roughly every two breaths of presenter
narration, which lines up with the click paths in `docs/demos/`.

Don't leave a cluster at 30s overnight — table growth doubles vs. the default
and the synthetic-trade noise starts to dominate the audit trail.

For a tight limit-breach demo on top of the live cadence, also set:

- `DEMO_BREACH_BOOK=derivatives-book` (default — change to target a different
  book in the live scenario you are demoing).
- `DEMO_BREACH_VAR_FACTOR=0.50` (default — lower to ~`0.30` if you want a
  breach in the very first trade tick).

See [kx-5q4](../docs/demos/README.md) for the design rationale.

### Switching profiles

The two supported ways to flip into live-demo cadence:

**Docker Compose** — add the `docker-compose.live-demo.yml` override on top of
the usual stack:

```bash
docker compose \
  -f infra/docker-compose.infra.yml \
  -f infra/docker-compose.observability.yml \
  -f docker-compose.services.yml \
  -f docker-compose.live-demo.yml \
  up -d demo-orchestrator
```

**Helm** — layer the live-demo values file on top of `values-demo.yaml`:

```bash
helm upgrade kinetix deploy/helm/kinetix \
  -f deploy/helm/kinetix/values-dev.yaml \
  -f deploy/helm/kinetix/values-demo.yaml \
  -f deploy/helm/kinetix/values-live-demo.yaml
```

Both overrides set `DEMO_TRADE_CADENCE_SECONDS=30` and nothing else, so they
compose cleanly with the standard demo configuration.

## Running locally

```bash
# All-in stack (default 90s cadence)
./deploy/redeploy.sh

# Live-demo cadence (30s) on top of the default stack
docker compose \
  -f infra/docker-compose.infra.yml \
  -f infra/docker-compose.observability.yml \
  -f docker-compose.services.yml \
  -f docker-compose.live-demo.yml \
  up -d
```

Health: `http://localhost:8094/health`. Readiness: `/health/ready`.

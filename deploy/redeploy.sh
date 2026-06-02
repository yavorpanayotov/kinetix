#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

# ── Stop all services ───────────────────────────────────────────────────────
echo "==> Stopping all services..."
docker compose \
  -f "$ROOT_DIR/infra/docker-compose.infra.yml" \
  -f "$ROOT_DIR/infra/docker-compose.observability.yml" \
  -f "$ROOT_DIR/docker-compose.services.yml" \
  down

# ── Rebuild Kotlin builder image from scratch ───────────────────────────────
echo "==> Rebuilding Kotlin builder image (this will recompile all services)..."
docker build --no-cache \
  -t kinetix-builder:latest \
  -f "$ROOT_DIR/deploy/docker/Dockerfile.kotlin-builder" \
  "$ROOT_DIR"

# ── Create shared Docker network ────────────────────────────────────────────
docker network inspect kinetix >/dev/null 2>&1 || {
  echo "==> Creating 'kinetix' Docker network..."
  docker network create kinetix
}

# ── Override observability configs for containerised targets ─────────────────
export PROMETHEUS_CONFIG="${ROOT_DIR}/deploy/observability/prometheus.yml"
export ALERTMANAGER_CONFIG="${ROOT_DIR}/deploy/observability/alertmanager.yml"
# Prod public hostname so Grafana emits correct absolute asset URLs through
# the Caddy reverse proxy. Without it the SPA bootstrap fails with "Grafana
# has failed to load its application files".
export GRAFANA_ROOT_URL="https://grafana.kinetixrisk.ai"

# ── Ensure databases exist ──────────────────────────────────────────────────
echo "==> Starting infrastructure..."
docker compose \
  -f "$ROOT_DIR/infra/docker-compose.infra.yml" \
  up -d --wait

echo "==> Ensuring databases exist..."
docker exec kinetix-postgres psql -U kinetix -f /docker-entrypoint-initdb.d/01-create-databases.sql 2>/dev/null || true

# ── Rebuild all service images from scratch ──────────────────────────────────
echo "==> Rebuilding all service images (this may take several minutes)..."
docker compose \
  -f "$ROOT_DIR/infra/docker-compose.infra.yml" \
  -f "$ROOT_DIR/infra/docker-compose.observability.yml" \
  -f "$ROOT_DIR/docker-compose.services.yml" \
  build --no-cache

# ── Start the full stack (demo mode — no Keycloak) ──────────────────────────
# Reused for the start + the Created-state recovery sweep below.
COMPOSE_FILES=(
  -f "$ROOT_DIR/infra/docker-compose.infra.yml"
  -f "$ROOT_DIR/infra/docker-compose.observability.yml"
  -f "$ROOT_DIR/docker-compose.services.yml"
)

echo "==> Starting all services in demo mode..."
# Allow a non-zero exit so we can recover stuck containers rather than aborting:
# if --wait times out (e.g. a dependency was slow to become healthy) compose
# leaves the dependants in 'Created' state and exits non-zero.
set +e
docker compose "${COMPOSE_FILES[@]}" up -d --wait
up_rc=$?
set -e

# ── Recover containers stuck in 'Created' state ──────────────────────────────
# Symptom this guards against: public URLs down while backends are healthy,
# because caddy/ui (chained on `condition: service_healthy`) were never started
# after an interrupted or timed-out `up --wait`. Re-running `up -d --wait` is
# idempotent — it starts the Created ones and waits for them to go healthy.
for attempt in 1 2 3; do
  created="$(docker compose "${COMPOSE_FILES[@]}" ps --status created -q)"
  [ -z "$created" ] && break
  echo "==> Found containers stuck in 'Created' state (attempt ${attempt}/3) — starting them:"
  docker compose "${COMPOSE_FILES[@]}" ps --status created --format '    {{.Name}}'
  set +e
  docker compose "${COMPOSE_FILES[@]}" up -d --wait
  up_rc=$?
  set -e
done

created="$(docker compose "${COMPOSE_FILES[@]}" ps --status created -q)"
if [ -n "$created" ]; then
  echo "WARNING: containers are still in 'Created' state after recovery attempts:" >&2
  docker compose "${COMPOSE_FILES[@]}" ps --status created --format '    {{.Name}}' >&2
  echo "         Inspect with 'docker compose ${COMPOSE_FILES[*]} ps' / 'docker logs <name>'." >&2
elif [ "${up_rc:-0}" -ne 0 ]; then
  echo "WARNING: 'docker compose up --wait' reported errors (rc=${up_rc}); review 'docker compose ps'." >&2
fi

# ── Optional: reset + reseed demo data (opt-in) ─────────────────────────────
# A code change can alter how seed data *should* look (e.g. the instrument
# taxonomy fix that retyped Treasuries), but `down` keeps the Postgres volumes,
# so the running data stays stale-relative-to-code until the nightly cron
# (deploy/cron/kinetix-demo-reset.sh, 02:00) fires. Set DEMO_RESET_ON_DEPLOY=true
# to wipe + reseed as part of this deploy. Off by default so local dev keeps its
# working data across redeploys; turn it on for the demo cluster.
if [ "${DEMO_RESET_ON_DEPLOY:-false}" = "true" ]; then
  echo "==> DEMO_RESET_ON_DEPLOY=true — resetting + reseeding demo data..."
  reset_url="${GATEWAY_URL:-http://localhost:8080}"
  # `up --wait` already gates on the gateway healthcheck, but poll /health once
  # more so a slow-to-settle gateway doesn't make the reset fan-out fail.
  ready=
  for _ in $(seq 1 30); do
    if curl -sf -o /dev/null "${reset_url}/health"; then ready=1; break; fi
    sleep 2
  done
  if [ -z "$ready" ]; then
    echo "WARNING: gateway ${reset_url}/health not ready after ~60s; skipping demo reset." >&2
  else
    GATEWAY_URL="$reset_url" \
    DEMO_ADMIN_KEY="${DEMO_ADMIN_KEY:-kinetix-demo-admin-dev}" \
      "$ROOT_DIR/deploy/cron/kinetix-demo-reset.sh" \
      || echo "WARNING: demo reset failed; see output above. Stack is up regardless." >&2
  fi
fi

# ── Summary ─────────────────────────────────────────────────────────────────
echo ""
echo "=============================================="
echo "  Kinetix is running (full rebuild, demo mode)"
echo "=============================================="
echo ""
printf "  %-22s %s\n" "Service" "URL"
printf "  %-22s %s\n" "──────────────────────" "──────────────────────────"
printf "  %-22s %s\n" "UI"                   "https://kinetixrisk.ai"
printf "  %-22s %s\n" "Gateway API"          "https://api.kinetixrisk.ai"
printf "  %-22s %s\n" "Grafana"              "https://grafana.kinetixrisk.ai"
echo ""
echo "  Demo mode: no login required, persona switcher in header"
echo "  Stop: ./deploy/deploy-down.sh"
echo ""

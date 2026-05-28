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
echo "==> Starting all services in demo mode..."
docker compose \
  -f "$ROOT_DIR/infra/docker-compose.infra.yml" \
  -f "$ROOT_DIR/infra/docker-compose.observability.yml" \
  -f "$ROOT_DIR/docker-compose.services.yml" \
  up -d --wait

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

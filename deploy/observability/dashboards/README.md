# Grafana dashboards (dashboards as code)

Grafana dashboards for Kinetix are version-controlled JSON. They live here as
plain `.json` exports and are loaded by Grafana's file-based dashboard
provisioner — **no hand-built dashboards in the Grafana UI**.

## How it works

- `deploy/observability/grafana/provisioning/dashboards/dashboards.yml` defines a
  file provider that scans this directory for dashboard JSON.
- `deploy/observability/grafana/provisioning/datasources/datasources.yml` defines
  the Prometheus, Loki, and Tempo datasources the dashboards query.
- Both the local `docker-compose` stack and the Helm `observability` chart mount
  this directory into the Grafana container so the same dashboards run
  everywhere.

## Conventions

- One dashboard per JSON file. Name files by the business domain or service they
  cover (e.g. `audit-trail.json`).
- Sub-folders become Grafana folders (`foldersFromFilesStructure: true`), so
  group dashboards by area (`risk/`, `trading/`, `infrastructure/`).
- Reference datasources by their stable `uid` (`prometheus`, `loki`, `tempo`)
  rather than by name, so dashboards stay portable across environments.
- Edit dashboards by changing the JSON in this repo and redeploying — changes
  made in the Grafana UI are not persisted.

# Helm charts — aspirational, not currently deployed

> **Heads-up for support engineers and contributors.** The Helm charts under
> this directory are an **aspirational EKS migration target**. They are **not
> what runs production today**.

## What actually runs production

`kinetixrisk.ai` and `grafana.kinetixrisk.ai` are served by a **single-host
docker-compose stack** behind Caddy:

- `deploy/deploy.sh` / `deploy/redeploy.sh` brings the stack up.
- `infra/docker-compose.infra.yml` + `infra/docker-compose.observability.yml`
  + `docker-compose.services.yml` define the services.
- `deploy/docker/Caddyfile` terminates TLS and reverse-proxies to the
  in-cluster services.

If you need to fix something in prod, it is almost certainly one of the
files above — **not** anything under `deploy/helm/`.

## Why the chart exists

The charts capture the intended shape of a future Kubernetes deployment
(probably on EKS — see `deploy/terraform/eks.tf`). Until that migration
actually happens, edits here have **zero production impact** and risk
sending the next engineer in the wrong direction (this happened during
the `kx-42wk.3` support investigation in May 2026 — see the bd issue
for the post-mortem).

## What to do

- **Bug-fixing the live platform?** Edit the docker-compose files, then
  run `/deploy`.
- **Working on the EKS migration?** Edit charts here, but make it clear
  in your PR description that the change is migration-track, not
  production-track.
- **Considering deleting these?** Discuss with the team before doing so —
  the migration may still be on the roadmap.

---
name: onboarding
description: Generate a "new engineer, day 1" onboarding doc for Kinetix — setup, repo tour, where to look first, common workflows, troubleshooting. Doubles as evidence the project can ramp a team. Invoke with /onboarding optionally followed by a role (e.g. "backend", "frontend", "quant", "sre").
user-invocable: true
allowed-tools: Read, Glob, Grep, Bash
---

# Engineer Onboarding Document

Generate a **day-1 onboarding doc** for a new engineer joining the Kinetix project. The doc is read-once at start and bookmarked thereafter. The audience is a senior engineer who is competent in their stack but has never seen Kinetix.

A good onboarding doc is itself a hiring signal — it shows the team can write for newcomers, not just for themselves.

## Step 1 — Determine role

Argument names a role; default to "generalist". Tailor the depth of each section:
- **backend** — Kotlin services emphasis: Ktor patterns, Exposed, Kafka, gRPC, acceptance-test conventions.
- **frontend** — React + Vite + TypeScript: tab structure, API client patterns, copilot integration, Playwright E2E.
- **quant** — Python risk engine: pricing modules, calibration, Hypothesis property tests, gRPC contracts.
- **sre** — deploy/observability: docker-compose stack, Grafana/Prometheus/Loki/Tempo, redeploy scripts, incident runbooks.
- **generalist** — short version of all of the above.

## Step 2 — Sections to produce

### 1. Welcome and orientation (½ page)
- Two-sentence description of what Kinetix is and who uses it.
- The single most important architectural fact for this role.
- Where to find help (Slack/Linear/etc. — leave placeholders; do not fabricate).

### 2. Setup (1 page)
- Prerequisites (Docker, JVM 21, Python 3.12, Node 20, etc. — pull from existing tooling config: `.tool-versions`, `.nvmrc`, `package.json` engines, `pyproject.toml`).
- Clone, install, build commands (cite the actual commands from `CLAUDE.md` Build & Run section).
- First run via `./deploy/redeploy.sh` and `/health`.
- Local URLs (`https://kinetixrisk.ai`, `https://api.kinetixrisk.ai`, `https://grafana.kinetixrisk.ai`).
- Common setup gotchas (Docker resource needs, TLS certs for `*.kinetixrisk.ai`, ports in use).

### 3. Repo tour (1 page)
- Top-level directory map with one-line descriptions (from `CLAUDE.md` Project Structure).
- The 3–5 ADRs to read first for this role.
- The 2–3 Allium specs that matter most for this role.
- Where this role's code lives (file globs).

### 4. The first ticket (½ page)
Suggest 2–3 starter beads issues from `bd ready` that match the role. If none are tagged "good-first-issue", suggest issue types that would be reasonable warm-up work (a small acceptance-test gap, a UI polish, a metric to add).

### 5. Common workflows (1–2 pages)
Tailor these to role; include cite-able commands:
- Running a single test (`./gradlew :<service>:test --tests "*Foo*"`, `cd risk-engine && uv run pytest tests/test_foo.py::test_bar`).
- Adding a Kafka topic (cite ADR-0004 and existing publisher/consumer patterns).
- Adding a new route (Ktor pattern + acceptance test).
- Reading logs (Loki query syntax with one example).
- Debugging a slow query (pgAdmin + EXPLAIN).
- Generating a regulatory or marketing artefact (link `/case-study`, `/diagrams`, `/regulatory-map`).

### 6. Project conventions
Pull from `CLAUDE.md` Project Conventions and Design Principles. Don't re-explain — link and quote selectively.

### 7. Quality gates before pushing
- `./gradlew build` for backend changes.
- `cd ui && npm run lint && npm run test` for UI changes.
- `cd risk-engine && uv run pytest -m unit` for engine changes.
- Beads issue lifecycle, commit-and-push protocol.
- Cite `/review` and `/code-review` skills.

### 8. The AI-assisted workflow
Half a page on how this team uses AI — `/distill`, `/weed`, `/propagate` loop, persona skills (`/architect`, `/quant`, `/security-engineer`), TDD via `/tdd`. This is the cultural orientation a new hire needs.

### 9. Troubleshooting
Pull from `CLAUDE.md` Known Gotchas. Add 3–5 more from recent incidents if `bd search` surfaces them.

### 10. Glossary
The 15–20 Kinetix-specific terms a new joiner will hit on day 1: VaR, ES, NMRF, regime, FRTB SA/IMA, EOD, four-eyes, hash chain, manifest, discovery–valuation, Allium, propagate, weed, etc.

## Step 3 — Output

Write to `docs/onboarding/<role>.md` (or `docs/onboarding/generalist.md` for the default). Create `docs/onboarding/` if needed. Print the file path and a summary.

If multiple role variants exist, also update `docs/onboarding/README.md` as an index.

## Reminders

- Every command in the doc must be copy-pasteable and verified against `CLAUDE.md` or actual scripts. A doc with a broken first command erodes trust faster than no doc.
- Prefer linking ADRs and specs over re-explaining them — the doc is a map, not a textbook.
- Keep the tone welcoming but professional. No "fun" emoji headers, no exclamation marks.
- After generation, run `bd update <issue> --notes` if a beads onboarding issue exists, so the artefact is discoverable from the issue tracker.

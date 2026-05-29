---
name: ai-impact-report
description: Quantify the AI-assisted contribution to Kinetix — sessions, prompts, commits, lines per feature, spec→test propagation stats, agent-hours. Produces a credibility artefact for "what can you actually do with AI-assisted development". Invoke with /ai-impact-report optionally followed by a time range (e.g. "last 30 days", "since 2026-01-01").
user-invocable: true
allowed-tools: Read, Glob, Grep, Bash
---

# AI Impact Report

Produce a **quantified report** of how AI-assisted development has shaped the Kinetix codebase. This is the showcase artefact for prospective clients evaluating AI-leveraged delivery capability. Numbers must be defensible — if a metric cannot be cleanly derived, omit it rather than guess.

## Step 1 — Data sources

- `~/.claude/history.jsonl` — every prompt issued by the user, with timestamps, session IDs, and the project path. Filter to entries where `project` equals the current working directory.
- `git log --no-merges --numstat` — every commit, with author, date, and lines added/removed per file.
- `.claude/skills/` — the skill set that scaffolds AI-assisted workflows.
- `specs/` — Allium specs (count, lines, last-touched).
- `specs/divergences/` — drift reports between specs and code.
- ADR-0036 if present — the AI runtime surface decisions.
- Beads (`bd list --status=closed --json`) — closed issues with timestamps, for feature-level grouping.

## Step 2 — Compute metrics

Parse the data and compute:

### Activity volume
- Total Claude Code sessions on this project.
- Total prompts.
- Active days (days with at least one prompt).
- Average prompts per active day.
- Total commits on the project over the same window.

### Throughput
- Commits per active day.
- Lines added / removed per active day (split by ext: `.kt`, `.py`, `.ts`/`.tsx`, `.md`, `.allium`).
- Median time between consecutive prompts within a session — a proxy for iteration cadence.

### Spec-driven coverage
- Number of Allium specs.
- Number of services with at least one spec.
- Number of divergence reports recorded vs resolved.
- Tests generated via `/propagate` (grep test files for "Generated from spec" markers, if present).

### Skill leverage
- Inventory of project-local skills under `.claude/skills/`.
- Distinguish persona skills (architect, qa, …) from utility skills (deploy, health, …) from BD/marketing skills (case-study, pitch-deck, …).
- Note skills that are themselves AI artefacts (evolution-report, ai-impact-report, distill, weed, propagate).

### Feature-level pairing (best-effort)
For each closed beads issue in the time range:
- The issue title and ID.
- Number of commits referencing the ID.
- Lines changed in those commits.
- Number of Claude Code sessions overlapping the commit window.

This gives a per-feature "agent-effort" signal. Mark estimates as best-effort, not authoritative.

## Step 3 — Headline numbers

Surface 5–7 headline numbers at the top of the report. Pick the ones most defensible and most impressive:
- Total active days × prompts per day.
- Lines of code originated during AI-paired sessions.
- Specs maintained.
- Tests generated from specs.
- ADRs authored during the window.
- Number of skills scaffolded.

Always show the underlying definition next to the number ("active day = at least one prompt"). No metrics without definitions.

## Step 4 — Narrative sections

After the numbers:
- **What AI did well** — areas where prompts→commits velocity was highest. 2–3 examples grounded in real beads issues or commit ranges.
- **What still required hand-shaping** — be honest. Hard parts: quant correctness, regulatory nuance, integration-test stability, UI polish.
- **The pipeline** — diagram (or prose) of the Allium spec → propagate → code → weed → ship loop. Cite skills used.
- **Repeatable patterns** — 3–5 lessons that would apply to another team adopting this stack.

## Step 5 — Output

Write to `docs/ai-impact-report.md` (overwriting if exists). Print the file path and the headline numbers as a summary.

Include the time window in the filename suffix only if the user specified one: `docs/ai-impact-report-2026Q1.md`.

## Reminders

- This document will be read by people evaluating Yavor and JUXT for AI-assisted contract work. Defensible > impressive. A small honest number beats a big fluffy one.
- Never claim a metric you cannot derive from the listed sources. If `~/.claude/history.jsonl` does not exist or the project path doesn't match, stop and explain.
- Do not include any private content from prompts (the report is about volume and shape, not content).
- Pair this skill with `/evolution-report` — that produces the narrative; this produces the numbers.

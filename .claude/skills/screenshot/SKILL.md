---
name: screenshot
description: Capture screenshots of the Kinetix UI for README, marketing, and docs — drives Playwright through the running UI, writes PNGs to docs/screenshots/, and updates an index. Invoke with /screenshot optionally followed by a scope (e.g. "all", "dashboard", "copilot", "risk-tabs").
user-invocable: true
allowed-tools: Read, Glob, Grep, Bash, Write, Edit
---

# UI Screenshot Capture

Capture screenshots of the Kinetix UI for embedding in README, case studies, pitch decks, and blog posts. Uses Playwright against the running UI and writes deterministic PNGs to `docs/screenshots/`.

This skill assumes the platform is already running (`/health` should return green for at least the UI and gateway). If not, instruct the user to run `/deploy` first and stop.

## Step 1 — Pre-flight

Check that:
- `https://kinetixrisk.ai` resolves to the running UI (curl with `-k -I` if TLS is self-signed).
- `https://api.kinetixrisk.ai` returns a non-error response.
- `ui/playwright.config.ts` exists.

If any check fails, stop and explain what the user needs to start.

## Step 2 — Determine scope

If the user passed a scope:
- `all` (default) — every top-level tab plus the copilot.
- `dashboard` — landing view only.
- `copilot` — copilot launcher, ⌘K palette, narrative panel.
- `risk-tabs` — VaR, Greeks, stress, FRTB capital, hierarchy roll-up tabs.
- `<tab-name>` — single named tab.

Map scope to a list of Playwright routes/selectors. If `ui/e2e/` contains fixtures or page-object helpers, reuse them — do not reinvent navigation.

## Step 3 — Use existing Playwright fixtures

Look at `ui/e2e/fixtures.ts` and existing E2E specs to understand:
- How to log in (or which mock auth fixture to use).
- How to navigate to each tab.
- How to seed demo data (link to `/demo`).

Prefer reusing fixtures over writing fresh navigation logic.

## Step 4 — Capture script

Write a one-off Playwright spec at `ui/e2e/screenshots/capture.spec.ts` (or update an existing one):

- For each target view: navigate, wait for network idle and key selectors, take a full-page screenshot.
- Standard viewport: 1440x900 (laptop), with optional 1920x1080 for hero shots.
- File naming: `docs/screenshots/<area>-<view>.png` (kebab-case), e.g. `dashboard-overview.png`, `copilot-narrative.png`, `risk-var-tab.png`.
- Use `path` relative to the repo root, not relative to `ui/`.

Run with:
```bash
cd ui && npx playwright test e2e/screenshots/capture.spec.ts --project=chromium
```

## Step 5 — Optimise

After capture, optionally run an image optimiser if available (`pngquant`, `oxipng`, `imagemin`). If none are installed, skip — do not install new dependencies.

## Step 6 — Update index

Write/update `docs/screenshots/README.md` with:
- A table: Screenshot · Description · Last captured · Captured against commit.
- An embed of each screenshot at thumbnail size.
- Capture date and the current git short SHA.

## Step 7 — README integration

Optionally update the main `README.md` "Screenshots" section if it exists (or suggest adding one). Do not silently rewrite the README — show a proposed diff and ask before applying.

## Step 8 — Output summary

Print:
- Scope captured.
- Files written.
- Any views skipped and why (selector timeout, auth failure, etc.).
- Suggested next steps (rerun with `--scope` for missing views, or rerun after a UI change).

## Reminders

- Screenshots are credibility signals. A polished UI screenshot in a README is worth a thousand words of prose to a non-engineer evaluator.
- Use the **demo data** profile (`/demo`) so screenshots show realistic numbers — not zeros, not test fixtures with `foo`/`bar`.
- Do not commit production credentials or counterparty PII in screenshots — Kinetix's local data is synthetic, but verify.
- Keep filenames deterministic and stable so each rerun overwrites cleanly without introducing churn in `docs/screenshots/`.

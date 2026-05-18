# Wiki staging area

Markdown files in this directory are the **source of truth for the GitHub Wiki** at <https://github.com/panayotovk/kinetix/wiki>.

## How to publish

The GitHub wiki is a separate git repository (`kinetix.wiki.git`) that GitHub initialises lazily the first time a page is created. To publish for the first time:

1. Go to <https://github.com/panayotovk/kinetix/wiki> and click **Create the first page** — any content; it will be overwritten.
2. Clone the wiki repo locally:
   ```bash
   git clone git@github.com:panayotovk/kinetix.wiki.git ../kinetix.wiki
   ```
3. Copy these files over (filename = URL slug):
   ```bash
   cp docs/wiki/*.md ../kinetix.wiki/
   ```
4. Commit and push:
   ```bash
   cd ../kinetix.wiki
   git add .
   git commit -m "Sync wiki pages from docs/wiki/"
   git push origin master
   ```

On subsequent updates, edit the files here, copy across, push.

## Pages

| File | Slug | Purpose |
|---|---|---|
| `Home.md` | `Home` | Landing page; table of contents |
| `Architecture.md` | `Architecture` | Service topology, Kafka topics, data flows |
| `Services.md` | `Services` | Per-service responsibilities and boundaries |
| `Risk-Methodology.md` | `Risk-Methodology` | VaR/ES, Greeks, factor model, stress, regime |
| `FRTB-Capital.md` | `FRTB-Capital` | SBM, DRC, RRAO; bucket correlations |
| `Audit-and-Compliance.md` | `Audit-and-Compliance` | Hash chain, four-eyes, retention, replay |
| `Local-Development.md` | `Local-Development` | Setup, dev loops, troubleshooting |
| `Testing-Strategy.md` | `Testing-Strategy` | Test pyramid, Testcontainers, mutation, property |
| `ADR-Index.md` | `ADR-Index` | All 35 ADRs with summaries and by-task lookup |

## Conventions

- GitHub-flavoured Markdown, no emoji.
- Links to source code use absolute URLs (`https://github.com/panayotovk/kinetix/blob/main/...`) so they resolve correctly on the wiki, which lives outside the repo tree.
- Internal wiki links use the page slug only: `[Architecture](Architecture)` — no `.md` suffix, no path.
- Tables for dense reference content (services, ADRs, Kafka topics).
- Cite ADR numbers wherever a decision is referenced.

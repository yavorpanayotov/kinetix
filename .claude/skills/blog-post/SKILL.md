---
name: blog-post
description: Draft a technical blog post / LinkedIn long-form article from a Kinetix ADR, a commit range, or a topic. Suitable for dev.to, LinkedIn, or a personal site. Invoke with /blog-post followed by a topic, ADR number, or commit range (e.g. "ADR-0017", "last 20 commits", "regime-adaptive VaR").
user-invocable: true
allowed-tools: Read, Glob, Grep, Bash
---

# Kinetix Blog Post Draft

You are drafting a **public technical blog post** (~1000–1500 words) from material in the Kinetix repo. The piece will appear under Yavor Panayotov's name. The audience is fintech engineers, quants, and engineering leaders. Tone: thoughtful, opinionated, evidence-led; not promotional.

## Step 1 — Resolve the source

Parse the argument:
- `ADR-NNNN` → read the ADR and any code/specs it cites.
- `last N commits` → `git log --oneline -<N>` and read the diff for substantive commits.
- A free-form topic ("regime-adaptive VaR") → `grep -ri` the topic across `docs/adr/`, `specs/`, and the relevant service, then pick 2–3 anchor files.

If you cannot find substantive source material, stop and tell the user — do not pad a blog post with generic content.

## Step 2 — Find the thesis

A blog post needs a single thesis. Phrases that work:
- "Why we chose X over Y" (decision post)
- "How we built X" (engineering walkthrough)
- "What we got wrong about X" (post-mortem / pivot)
- "X is harder than it looks" (depth-of-domain post)

State the thesis in the first paragraph in plain language. If the source material does not support a single thesis, split into two posts.

## Step 3 — Structure

Use this skeleton, adjusted for the thesis:

1. **Hook** — one paragraph. A concrete scenario, a number, or a contrarian claim. Not "In this post I will…".
2. **The problem** — 2–3 paragraphs. What was painful, what existed before, why the obvious solution didn't work.
3. **What we did** — 4–6 paragraphs. The decision, the architecture, the trade-offs. Include code snippets only if they make the point sharper. Link to the ADR.
4. **What it cost us** — 1–2 paragraphs. Honest constraints, compromises, things still on the to-do list. Credibility comes from owning trade-offs.
5. **What we learned** — 1 paragraph. Generalise to "if you're building X, consider Y".
6. **References** — bullet list with links to the relevant ADRs, specs, and any external sources.

## Step 4 — Voice

- First-person plural ("we") for engineering choices; first-person singular ("I") when reflecting.
- British English. No emojis. Sparing use of bold.
- Avoid AI clichés: "delve", "leverage", "robust", "seamless", "in today's fast-paced world".
- Show, don't tell. A code snippet or a number is worth a paragraph of adjectives.
- Cite competitors fairly. Don't punch down at vendor systems by name without backing the claim.

## Step 5 — Output

Write to `docs/blog/<slug>.md` where `<slug>` is a kebab-case summary of the thesis. Create `docs/blog/` if needed. Print the path and a one-line summary.

Include front-matter at the top of the post (so it can be pasted into dev.to or Hashnode directly):

```yaml
---
title: "<headline>"
published: false
description: "<one-sentence summary, ≤155 chars>"
tags: ["risk", "fintech", "kotlin", "ai"]
author: "Yavor Panayotov"
---
```

## Reminders

- The user is positioning for senior engineering work in financial services — every post is a hiring signal. Quality > frequency.
- If you cannot back a claim with a repo artefact (ADR, spec, code, commit), cut the claim or mark it `[TODO: source]` for the user.
- Aim for one strong post over three mediocre ones — if the material is thin, say so and suggest the user pick a richer source.

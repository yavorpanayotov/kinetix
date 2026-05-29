---
name: talk-outline
description: Generate a conference/meetup talk outline from Kinetix material — speaker notes, slide cues, code/demo suggestions, Q&A prep. Invoke with /talk-outline followed by topic and optionally duration (e.g. "AI copilot 30m", "hash-chained audit 15m lightning").
user-invocable: true
allowed-tools: Read, Glob, Grep, Bash
---

# Conference Talk Outline

Produce a detailed **talk outline** suitable for a fintech, Kotlin, or AI-engineering meetup or conference. The talk will be delivered by Yavor Panayotov.

## Step 1 — Parse arguments

Identify:
- **Topic** — the technical anchor (e.g. "regime-adaptive VaR", "Allium spec-driven dev", "AI copilot").
- **Duration** — 10m (lightning), 15m, 30m (standard), 45m (long-form), 60m (workshop).
- **Audience** — infer from topic or ask: engineers, quants, engineering leaders, AI practitioners.

Default to 30 minutes if not specified.

## Step 2 — Gather source material

Read the ADR(s) and spec(s) most relevant to the topic. Skim the actual code paths so the talk can show real snippets, not invented ones. Pull commit history for the topic — talks land better with a "here's how we got here" narrative.

## Step 3 — Structure (scaled to duration)

Time budget for a 30-minute talk:
- 2 min — hook (a concrete scenario or surprising claim)
- 4 min — problem framing (why this is hard)
- 18 min — the meat (the design, decisions, code, trade-offs)
- 3 min — what we got wrong / what's still open
- 3 min — Q&A buffer / wrap

Scale proportionally for other durations. For 10-min lightning: skip "what we got wrong", compress hook + problem to 2 min combined.

For each segment produce:
- **Header** (slide title)
- **Goal** (what the audience should take away — one sentence)
- **Speaker notes** (2–5 bullets of what to say)
- **Visual** (slide content — diagram, code, table, screenshot)
- **Time** (target minutes)

## Step 4 — Demo / code snippets

If the topic permits a live demo or code snippet, include:
- **Setup** — what needs to be running (link to `/health`, `/demo`).
- **The reveal** — the 5–20 lines that make the point.
- **Fallback** — what to show if the live demo fails (a screenshot, a Loom link placeholder).

Live demos at conferences fail half the time. Always provide a fallback.

## Step 5 — Q&A prep

List 6–10 anticipated audience questions with one-paragraph answers. Include:
- The obvious follow-up ("how does this compare to <vendor>?")
- The sceptical question ("but does this scale?")
- The adjacent topic the speaker should redirect to ("we'll be covering that in <other talk / blog>")

## Step 6 — Speaker briefing

A 5-bullet "what to remember on stage" section: pacing, where to slow down, the one stat that has to land, what NOT to say.

## Step 7 — Output

Write to `docs/talks/<slug>-<duration>.md`. Create `docs/talks/` if needed. Print the file path and a one-line summary.

Include front-matter:

```yaml
---
title: "<headline>"
speaker: "Yavor Panayotov"
duration: "30m"
audience: "<inferred audience>"
status: draft
---
```

## Reminders

- A talk is not a blog post read aloud. It needs a hook, a narrative arc, and one clear takeaway.
- The audience remembers one slide — pick which one and make it impossible to misread.
- For Kinetix talks, the most underused angle is **AI-assisted delivery**. Engineers want to hear how this was actually built with AI, not just what the runtime AI surface does.

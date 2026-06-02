"""Copilot eval harness — golden Q&A scored against the real safety guards.

This package is the model-governance layer for the AI copilot. Where the
runtime guards (:mod:`kinetix_insights.policy.banned_phrases`,
:mod:`kinetix_insights.citations.verifier`,
:mod:`kinetix_insights.citations.symbol_verifier`,
:mod:`kinetix_insights.chat.sanitiser`) *enforce* correct behaviour at
request time, this harness *measures* it: a curated golden dataset of
prompts paired with model narratives — including deliberately adversarial
ones — is replayed through those same guards and scored.

Two modes share one dataset and one scoring engine:

* **Offline** (deterministic, no API key) — scripted narratives in the
  golden set are classified by the real guards and checked against an
  expected verdict. Run via ``pytest -m eval``. This is the regression
  gate.
* **Live** (``pytest -m eval_live``) — the real Claude Agent SDK answers
  the same prompts against controlled fake backend data and an
  LLM-as-judge scores the result. Skips cleanly without
  ``ANTHROPIC_API_KEY``.

The harness deliberately does NOT modify the guards it tests — it is a
mirror, not a patch. See ``docs/governance/copilot-eval-scorecard.md`` for
the rendered report and ADR-0036 for the copilot architecture.
"""

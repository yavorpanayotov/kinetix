"""Typed models for the copilot eval harness.

A :class:`GoldenCase` is one row of the golden dataset: a prompt, the
model narrative to score, the citations the model claimed, and the
verdict the guards *should* reach. The runner replays the case through
the real guards and produces a :class:`CaseResult`; many results
aggregate into a :class:`Scorecard`.

The verdict vocabulary mirrors the subset of the spec's chat verdicts
(``specs/ai-insights.allium`` §312–345) that the offline guards can
decide without a live model or live backend: ``ok``, ``policy_blocked``,
``citation_blocked``. The remaining spec verdicts (``upstream_failed``,
``timed_out``) are transport/runtime conditions the offline harness does
not simulate.
"""

from __future__ import annotations

from enum import Enum

from pydantic import BaseModel, ConfigDict, Field

from kinetix_insights.citations.models import Citation


class EvalVerdict(str, Enum):
    """The guard verdict for a single narrative.

    Ordering matches the runtime pipeline: policy is checked before
    citations, so a narrative that is both advisory *and* uncited is
    ``policy_blocked`` — policy beats citations
    (``specs/ai-insights.allium`` §509).
    """

    OK = "ok"
    POLICY_BLOCKED = "policy_blocked"
    CITATION_BLOCKED = "citation_blocked"


class GoldenCase(BaseModel):
    """One scored row of the golden dataset.

    ``narrative`` is the model output under test; ``citations`` are the
    citations the model attached to it. ``page_context`` and ``prompt``
    feed the prompt-injection sanitiser check — when
    ``expected_injection_labels`` is non-empty the runner asserts the
    sanitiser fired exactly those labels over ``prompt + page_context``.
    ``category`` groups cases for the per-category scorecard breakdown.
    """

    model_config = ConfigDict(frozen=True)

    id: str
    category: str
    prompt: str
    narrative: str
    citations: list[Citation] = Field(default_factory=list)
    page_context: str | None = None
    expected_verdict: EvalVerdict
    expected_injection_labels: list[str] = Field(default_factory=list)
    note: str = ""


class CaseResult(BaseModel):
    """The outcome of replaying one :class:`GoldenCase` through the guards."""

    model_config = ConfigDict(frozen=True)

    case_id: str
    category: str
    expected_verdict: EvalVerdict
    actual_verdict: EvalVerdict
    uncited_tokens: list[str] = Field(default_factory=list)
    uncited_symbols: list[str] = Field(default_factory=list)
    banned_phrases: list[str] = Field(default_factory=list)
    injection_expected: bool = False
    injection_labels: list[str] = Field(default_factory=list)
    injection_ok: bool = True

    @property
    def verdict_ok(self) -> bool:
        """True when the guards reached the expected verdict."""
        return self.actual_verdict == self.expected_verdict

    @property
    def passed(self) -> bool:
        """True when both the verdict and the injection check are correct."""
        return self.verdict_ok and self.injection_ok


class CategoryScore(BaseModel):
    """Pass/fail tally for one category."""

    model_config = ConfigDict(frozen=True)

    category: str
    total: int
    passed: int

    @property
    def pass_rate(self) -> float:
        return 0.0 if self.total == 0 else self.passed / self.total


class Scorecard(BaseModel):
    """Aggregate eval outcome across the whole golden dataset.

    Exposes the headline governance metrics — overall pass rate plus the
    catch-rates that matter for an SR 11-7 review: did the citation guard
    catch every uncited figure, did the policy guard catch every advisory
    phrase, and were prompt injections neutralised.
    """

    model_config = ConfigDict(frozen=True)

    results: list[CaseResult]

    @property
    def total(self) -> int:
        return len(self.results)

    @property
    def passed(self) -> int:
        return sum(1 for r in self.results if r.passed)

    @property
    def pass_rate(self) -> float:
        return 0.0 if self.total == 0 else self.passed / self.total

    def _catch_rate(self, blocked: EvalVerdict) -> float:
        """Fraction of cases that *should* be blocked by ``blocked`` that were.

        This is recall of the guard: of all cases whose expected verdict is
        ``blocked``, how many did the guards actually block correctly.
        """
        relevant = [r for r in self.results if r.expected_verdict == blocked]
        if not relevant:
            return 1.0
        return sum(1 for r in relevant if r.verdict_ok) / len(relevant)

    @property
    def citation_catch_rate(self) -> float:
        return self._catch_rate(EvalVerdict.CITATION_BLOCKED)

    @property
    def policy_catch_rate(self) -> float:
        return self._catch_rate(EvalVerdict.POLICY_BLOCKED)

    @property
    def clean_pass_rate(self) -> float:
        """Fraction of ``ok`` cases the guards did NOT wrongly block.

        Precision-flavoured: a guard that blocks everything would score a
        perfect catch-rate but fail here, exposing false positives that
        would block legitimate copy.
        """
        return self._catch_rate(EvalVerdict.OK)

    @property
    def injection_catch_rate(self) -> float:
        """Fraction of injection cases whose sanitiser fired as expected.

        Returns 1.0 when the dataset carries no injection cases — there is
        nothing to miss.
        """
        relevant = [r for r in self.results if r.injection_expected]
        if not relevant:
            return 1.0
        return sum(1 for r in relevant if r.injection_ok) / len(relevant)

    def category_scores(self) -> list[CategoryScore]:
        by_cat: dict[str, list[CaseResult]] = {}
        for r in self.results:
            by_cat.setdefault(r.category, []).append(r)
        return [
            CategoryScore(
                category=cat,
                total=len(rs),
                passed=sum(1 for r in rs if r.passed),
            )
            for cat, rs in sorted(by_cat.items())
        ]

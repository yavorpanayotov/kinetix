"""Pydantic models for the per-book morning brief (checkbox 6.5).

A :class:`MorningBrief` is the structured digest a trader sees at the
start of the day for ONE book: a short ordered list of
:class:`BriefSection` panels — Value at Risk, P&L attribution, recent
breaches, limit utilisation, Greeks — each carrying a prose narrative,
a handful of headline bullets, the provenance :class:`Citation`\\ s the
numbers came from, and a severity badge.

Why ``book_id`` lives on :class:`MorningBrief`
----------------------------------------------
``plans/ai-v2.md`` § 6.5 specifies ``MorningBrief{sections[...],
generated_at, mode}`` without a top-level ``book_id``, but also asks the
generator to iterate *per book*. A :class:`MorningBrief` is therefore
PER-BOOK — one brief object per book — and we keep ``book_id`` on it so
a multi-book caller (``MorningBriefGenerator.generate_all``) can tell
the briefs apart. The plan's ``sections`` list is exactly the per-book
section list.

Both models are frozen-free (mutable) only insofar as Pydantic defaults
allow; they are assembled once by the generator and then passed around
read-only. They round-trip cleanly through JSON — :class:`Citation` is
itself a Pydantic model so ``sources`` survives ``model_dump_json`` /
``model_validate_json``.
"""

from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, Field

from kinetix_insights.citations.models import Citation


class BriefSection(BaseModel):
    """One panel of a :class:`MorningBrief`.

    ``title`` names the panel (e.g. ``"Value at Risk"``). ``narrative``
    is a short prose line derived from the backing tool's numbers.
    ``bullets`` carries 2-4 headline figures. ``sources`` carries the
    provenance citations for the numbers in the panel (one per backing
    tool call). ``severity`` is the panel's badge — ``"info"`` for a
    normal panel, ``"warning"`` when something needs attention (open
    breaches absent but data quality degraded, an amber limit, a failed
    tool), ``"critical"`` for a hard breach / red limit. ``status``
    records how the backing tool call resolved: ``"ok"`` when it
    returned cleanly, ``"error"`` when it raised a non-timeout failure,
    ``"timeout"`` when it raised a timeout-flavoured failure.
    """

    title: str
    narrative: str
    bullets: list[str] = Field(default_factory=list)
    sources: list[Citation] = Field(default_factory=list)
    severity: str
    status: str = "ok"


class MorningBrief(BaseModel):
    """The per-book morning brief — one object per book.

    ``book_id`` identifies the book this brief covers (see module
    docstring for why it sits here). ``sections`` is the ordered list of
    panels — VaR, P&L, breaches, limits, Greeks. ``generated_at`` is the
    generator's clock reading at assembly time. ``mode`` records whether
    the brief was built from ``"live"`` tool calls or a ``"canned"``
    fixture.
    """

    book_id: str
    sections: list[BriefSection]
    generated_at: datetime
    mode: str

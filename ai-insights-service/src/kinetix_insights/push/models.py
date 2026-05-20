"""Pydantic model for the intraday push payload (plan ai-v2.md § 7.4).

An :class:`IntradayPush` is the structured alert the AI Insights service
composes when an intraday threshold breach fires (see
:class:`~kinetix_insights.push.threshold_evaluator.IntradayAlert`). It is
the wire shape the gateway internal endpoint receives in checkbox 7.7 and
the UI renders as a toast / alert banner.

The plan pins the field set exactly::

    {alert_type, severity, book_id, headline, context_bullets,
     sources, session_id, generated_at}

Why a Pydantic model
--------------------
The payload round-trips through JSON on its way to the gateway, so a
Pydantic model gives us validation, ``model_dump`` / ``model_dump_json``
serialisation, and — because :class:`Citation` is itself a Pydantic
model — a clean nested ``sources`` array. It mirrors
:class:`~kinetix_insights.brief.models.MorningBrief`, which is assembled
once by a generator and then passed around read-only.

``sources`` carries the provenance trail
----------------------------------------
``sources`` is the list of :class:`Citation`\\ s for the *tool calls used
to evaluate the threshold* — the threshold-config read
(``get_alert_thresholds``) and the ``risk.results`` Kafka event the
breaching measure was read from. The UI uses these to render inline
provenance exactly as it does for chat and the morning brief.
"""

from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, Field

from kinetix_insights.citations.models import Citation


class IntradayPush(BaseModel):
    """One intraday push alert composed from a firing :class:`IntradayAlert`.

    ``alert_type`` is the threshold's alert type (e.g. ``"VAR_BREACH"``).
    ``severity`` is ``"warning"`` or ``"critical"``, carried through from
    the evaluator. ``book_id`` identifies the book whose ``risk.results``
    event breached. ``headline`` is a short human-readable summary line.
    ``context_bullets`` carries 2-4 supporting figures (current value,
    breached threshold, how far over). ``sources`` is the provenance
    trail — the :class:`Citation`\\ s for the tool calls used to evaluate
    the threshold. ``session_id`` is a per-push UUID so the UI and audit
    trail can correlate frames. ``generated_at`` is the composition
    clock reading, an ISO-8601 UTC timestamp.
    """

    alert_type: str
    severity: str
    book_id: str
    headline: str
    context_bullets: list[str] = Field(default_factory=list)
    sources: list[Citation] = Field(default_factory=list)
    session_id: str
    generated_at: datetime

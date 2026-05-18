"""Report commentary route.

Exposes ``POST /api/v1/insights/explain/report`` — a thin HTTP wrapper that
converts a typed :class:`ReportCommentaryRequest` into a generic
:class:`InsightRequest` and delegates to the configured insight client
(:class:`CannedInsightClient` in DEMO mode, :class:`ClaudeAgentInsightClient`
otherwise) attached to ``request.app.state.insight_client``.
"""

from fastapi import APIRouter, Request
from pydantic import BaseModel, Field

from ..models import InsightRequest, InsightResponse


class ReportDriver(BaseModel):
    """A single driver of a report's headline metrics."""

    name: str
    contribution_usd: float


class ReportCommentaryRequest(BaseModel):
    """Typed request payload for the report commentary route.

    ``template_id`` identifies which report template was rendered and
    ``report_date`` is the as-of date (ISO string). ``summary_metrics``
    carries the headline numeric summary keyed by metric name.
    ``top_drivers`` lists the largest contributors to the headline P&L /
    risk metric, and ``breaches`` lists any limit or threshold breaches
    that should be called out in the narrative.
    """

    template_id: str
    report_date: str
    summary_metrics: dict[str, float] = Field(default_factory=dict)
    top_drivers: list[ReportDriver] = Field(default_factory=list)
    breaches: list[str] = Field(default_factory=list)


router = APIRouter(prefix="/api/v1/insights/explain", tags=["insights"])


@router.post("/report", response_model=InsightResponse)
async def explain_report(
    payload: ReportCommentaryRequest, request: Request
) -> InsightResponse:
    """Explain a report via the configured insight client."""
    client = request.app.state.insight_client
    insight_req = InsightRequest(kind="report", payload=payload.model_dump())
    return await client.explain(insight_req)

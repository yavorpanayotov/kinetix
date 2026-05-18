"""VaR explainer route.

Exposes ``POST /api/v1/insights/explain/var`` — a thin HTTP wrapper that
converts a typed :class:`VarExplainerRequest` into a generic
:class:`InsightRequest` and delegates to the configured insight client
(:class:`CannedInsightClient` in DEMO mode, :class:`ClaudeAgentInsightClient`
otherwise) attached to ``request.app.state.insight_client``.
"""

from fastapi import APIRouter, Request
from pydantic import BaseModel, Field

from ..models import InsightRequest, InsightResponse


class VarContributor(BaseModel):
    """A single instrument's contribution to portfolio VaR."""

    instrument: str
    contribution_pct: float


class VarExplainerRequest(BaseModel):
    """Typed request payload for the VaR explainer route.

    ``method`` is one of ``"historical"``, ``"parametric"``, or
    ``"monte_carlo"``. ``confidence`` is a probability (e.g. 0.95, 0.99)
    and ``horizon_days`` is the holding period. ``value_usd`` is the
    headline VaR figure. ``top_contributors`` lists the largest
    instrument-level contributors, and ``regime`` is an optional market
    regime tag (e.g. ``"high_vol"``).
    """

    method: str
    confidence: float
    horizon_days: int
    value_usd: float
    top_contributors: list[VarContributor] = Field(default_factory=list)
    regime: str = ""


router = APIRouter(prefix="/api/v1/insights/explain", tags=["insights"])


@router.post("/var", response_model=InsightResponse)
async def explain_var(payload: VarExplainerRequest, request: Request) -> InsightResponse:
    """Explain a VaR result via the configured insight client."""
    client = request.app.state.insight_client
    insight_req = InsightRequest(kind="var", payload=payload.model_dump())
    return await client.explain(insight_req)

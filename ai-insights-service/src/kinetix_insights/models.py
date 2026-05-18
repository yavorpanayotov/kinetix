"""Pydantic models for the Kinetix AI Insights service.

These types form the shared request/response contract used by every
insight endpoint (VaR, Report, future kinds). They are intentionally
small and transport-agnostic so they can be serialised across HTTP and
reused by the canned client, the Claude SDK client, and the routes.
"""

from typing import Any, Literal

from pydantic import BaseModel, Field


class InsightRequest(BaseModel):
    """A request for an LLM-generated insight.

    The `kind` discriminates between supported insight types; the
    `payload` carries the kind-specific input data (for example, a VaR
    result or a report summary). Validation of the payload shape is
    deferred to the consumer because it varies by kind.
    """

    kind: Literal["var", "report"]
    payload: dict[str, Any] = Field(default_factory=dict)


class InsightResponse(BaseModel):
    """The structured response shape every insight endpoint returns.

    `narrative` is the prose answer, `bullets` is a list of short
    talking points, `model` records which LLM produced the output, and
    `mode` indicates whether the response came from a live model call
    or from a canned/offline fallback.
    """

    narrative: str
    bullets: list[str] = Field(default_factory=list)
    model: str
    mode: Literal["live", "canned"]

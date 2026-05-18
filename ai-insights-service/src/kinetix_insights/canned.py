import hashlib
import json
from typing import Any

from .models import InsightRequest, InsightResponse

_VAR_NARRATIVES: tuple[str, ...] = (
    "Portfolio VaR rose modestly, reflecting a broad-based increase in market volatility across risk factors.",
    "Portfolio VaR is elevated versus the prior period, driven by concentration in a small number of risk factors.",
    "Portfolio VaR is broadly in line with recent history; risk factor moves largely offset within the book.",
    "Portfolio VaR has tightened as diversification benefits outweigh idiosyncratic risk factor increases.",
)

_REPORT_NARRATIVES: tuple[str, ...] = (
    "Daily P&L was driven primarily by directional exposure; tail-risk metrics remain within tolerance.",
    "Daily P&L was mixed across desks; aggregate risk consumption increased on widening factor moves.",
    "Daily P&L was muted; limit utilisation drifted higher on rates and credit exposures.",
    "Daily P&L benefited from hedge effectiveness; selected limits warrant attention given current positioning.",
)

_GENERIC_VAR_BULLETS: tuple[str, ...] = (
    "VaR captures the worst expected loss at the chosen confidence level over the holding period.",
    "Movement vs the prior run reflects changes in volatilities, correlations, and underlying positions.",
    "Compare against limits and prior peaks to gauge whether the change is material.",
)

_GENERIC_REPORT_BULLETS: tuple[str, ...] = (
    "P&L attribution is dominated by directional exposure rather than basis or carry effects.",
    "Risk consumption is within mandate; no material limit breaches detected in this period.",
    "Monitor concentrations across desks and asset classes as positioning evolves.",
)


def _canonical_hash(payload: dict[str, Any]) -> str:
    encoded = json.dumps(payload, sort_keys=True, separators=(",", ":"), default=str)
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def _bucket(digest: str, modulus: int) -> int:
    return int(digest, 16) % modulus


def _var_bullets(payload: dict[str, Any], bucket: int) -> list[str]:
    contributors = payload.get("top_contributors")
    if isinstance(contributors, list) and contributors:
        bullets: list[str] = []
        for item in contributors[:4]:
            if isinstance(item, dict):
                instrument = item.get("instrument", "unknown")
                contribution = item.get("contribution_pct")
                if contribution is not None:
                    bullets.append(
                        f"{instrument} contributes {contribution} to portfolio VaR."
                    )
                else:
                    bullets.append(f"{instrument} is a notable contributor to portfolio VaR.")
            else:
                bullets.append(f"{item} is a notable contributor to portfolio VaR.")
        bullets.append(
            "Review hedges against the largest contributors to reduce concentration risk."
        )
        return bullets
    base = list(_GENERIC_VAR_BULLETS)
    if bucket % 2 == 0:
        base.append("Stress scenarios remain the right cross-check against the parametric figure.")
    return base


def _report_bullets(payload: dict[str, Any], bucket: int) -> list[str]:
    drivers = payload.get("top_drivers")
    breaches = payload.get("breaches")
    bullets: list[str] = []
    if isinstance(drivers, list) and drivers:
        for driver in drivers[:3]:
            bullets.append(f"Top driver: {driver}.")
    if isinstance(breaches, list) and breaches:
        for breach in breaches[:2]:
            bullets.append(f"Breach flagged: {breach}.")
    if bullets:
        bullets.append("Review desk-level attribution to confirm drivers and remediate breaches.")
        return bullets
    base = list(_GENERIC_REPORT_BULLETS)
    if bucket % 2 == 1:
        base.append("Cross-check intraday flow against end-of-day risk to spot regime shifts.")
    return base


class CannedInsightClient:
    async def explain(self, request: InsightRequest) -> InsightResponse:
        digest = _canonical_hash(request.payload)
        if request.kind == "var":
            bucket = _bucket(digest, len(_VAR_NARRATIVES))
            narrative = _VAR_NARRATIVES[bucket]
            bullets = _var_bullets(request.payload, bucket)
        else:
            bucket = _bucket(digest, len(_REPORT_NARRATIVES))
            narrative = _REPORT_NARRATIVES[bucket]
            bullets = _report_bullets(request.payload, bucket)
        return InsightResponse(
            narrative=narrative,
            bullets=bullets,
            model="canned",
            mode="canned",
        )

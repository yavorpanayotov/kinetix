"""The :class:`MorningBriefGenerator` — checkbox 6.5.

Assembles a per-book :class:`MorningBrief` by composing the five v2
read tools (``get_book_var``, ``get_pnl_attribution``,
``get_recent_breaches``, ``get_limit_utilisation``,
``get_greeks_summary``) into one ordered list of
:class:`BriefSection` panels.

Resilience by construction
--------------------------
Each tool call is wrapped: a :class:`KinetixHttpError` (ACL failure,
upstream error, timeout) on one tool produces an ``error``/``timeout``
section but does NOT abort the remaining tool calls or the other
books. A bare ``except Exception`` guards each call too — checkbox 6.5
states explicitly that *per-book errors do NOT abort the batch*, so a
partial brief is always preferred to no brief. ``generate_all`` adds a
second, defensive wrapper around each ``generate`` call so even a
total per-book failure still yields a brief with all-error sections.

"Deltas vs SOD"
---------------
The plan frames the P&L and Greeks sections as "deltas vs start of
day". v2 does NOT fetch a separate SOD snapshot — the tools already
encode SOD-relative figures: the P&L attribution tool's ``total_pnl``
*is* the day's move (P&L attribution is inherently a day-over-day
quantity), and the Greeks tool's ``aggregate`` is the current book
Greeks. The narratives phrase the numbers as "today" / "vs start of
day" where the tool data naturally supports it; no synthetic
SOD-snapshot fetch is introduced. Closing the gap to a true
SOD-snapshot delta requires an upstream endpoint and is a v2 follow-up.

Timeout classification
----------------------
:class:`KinetixHttpError` carries no dedicated timeout category, so a
failure is treated as a ``timeout`` section ONLY when its ``code`` is
``"TIMEOUT"`` or its ``message`` contains ``"timeout"``
(case-insensitively). Every other failure — including ``UNAUTHORIZED``
ACL failures and ``UPSTREAM_ERROR`` — is an ``error`` section.
"""

from __future__ import annotations

import time
from datetime import datetime, timezone
from typing import Callable

from kinetix_insights.brief.models import BriefSection, MorningBrief
from kinetix_insights.citations.models import Citation
from kinetix_insights.clients.kinetix_http_client import (
    KinetixHttpClient,
    KinetixHttpError,
)
from kinetix_insights.metrics.copilot_metrics import (
    COPILOT_BRIEF_GENERATION_DURATION_SECONDS,
)
from kinetix_insights.clients.user_context import UserContext
from kinetix_insights.mcp.tools.get_book_var import get_book_var
from kinetix_insights.mcp.tools.get_greeks_summary import get_greeks_summary
from kinetix_insights.mcp.tools.get_limit_utilisation import get_limit_utilisation
from kinetix_insights.mcp.tools.get_pnl_attribution import get_pnl_attribution
from kinetix_insights.mcp.tools.get_recent_breaches import get_recent_breaches

_SEVERITY_INFO = "info"
_SEVERITY_WARNING = "warning"
_SEVERITY_CRITICAL = "critical"

_STATUS_OK = "ok"
_STATUS_ERROR = "error"
_STATUS_TIMEOUT = "timeout"

_TITLE_VAR = "Value at Risk"
_TITLE_PNL = "P&L Attribution"
_TITLE_BREACHES = "Recent Breaches"
_TITLE_LIMITS = "Limit Utilisation"
_TITLE_GREEKS = "Greeks"

# P&L data-quality substrings that warrant a warning badge.
_PNL_DEGRADED_MARKERS = ("STALE", "PRICE_ONLY")


def _default_now() -> datetime:
    return datetime.now(timezone.utc)


def _is_timeout(error: KinetixHttpError) -> bool:
    """Classify a failure as timeout-flavoured.

    ``KinetixHttpError`` has no dedicated timeout code, so we treat a
    failure as a timeout only when its ``code`` is exactly ``TIMEOUT``
    or its ``message`` mentions ``timeout`` (case-insensitively).
    """

    if error.code == "TIMEOUT":
        return True
    return "timeout" in error.message.lower()


def _money(value: float) -> str:
    """Render a monetary figure compactly for a bullet line."""

    return f"{value:,.0f} USD"


class MorningBriefGenerator:
    """Assembles a per-book :class:`MorningBrief` from the five read tools.

    Each tool call is wrapped so a failure on one tool produces an
    ``error``/``timeout`` section without aborting the remaining tool
    calls or other books. The batch is resilient by construction — a
    partial brief is always better than no brief.

    The "deltas vs SOD" framing from checkbox 6.5 is satisfied by the
    tools themselves: P&L attribution is inherently a day-over-day
    quantity and the Greeks ``aggregate`` is the current book Greeks.
    No separate SOD-snapshot fetch is performed — see the module
    docstring.
    """

    def __init__(
        self,
        *,
        http: KinetixHttpClient,
        now: Callable[[], datetime] | None = None,
        mode: str = "live",
    ) -> None:
        self._http = http
        self._now = now or _default_now
        self._mode = mode

    async def generate(self, *, book_id: str, user: UserContext) -> MorningBrief:
        """Generate the morning brief for ONE book.

        Builds five sections — VaR, P&L attribution, recent breaches,
        limit utilisation, Greeks — in that order. A failure in any one
        tool yields an ``error``/``timeout`` section and never aborts
        the rest.
        """

        started = time.monotonic()
        try:
            sections = [
                await self._var_section(book_id=book_id, user=user),
                await self._pnl_section(book_id=book_id, user=user),
                await self._breaches_section(book_id=book_id, user=user),
                await self._limits_section(book_id=book_id, user=user),
                await self._greeks_section(book_id=book_id, user=user),
            ]
        finally:
            COPILOT_BRIEF_GENERATION_DURATION_SECONDS.labels(
                mode=self._mode
            ).observe(time.monotonic() - started)
        return MorningBrief(
            book_id=book_id,
            sections=sections,
            generated_at=self._now(),
            mode=self._mode,
        )

    async def generate_all(self, *, user: UserContext) -> list[MorningBrief]:
        """Generate a brief for every book in ``user.books``, in order.

        A failure generating one book's brief never aborts the others.
        ``generate`` is itself resilient, so a total per-book failure
        should be impossible — but a defensive wrapper here guarantees
        every book still yields a :class:`MorningBrief` (with all-error
        sections) rather than aborting the batch.
        """

        briefs: list[MorningBrief] = []
        for book_id in user.books:
            try:
                briefs.append(await self.generate(book_id=book_id, user=user))
            except Exception as exc:  # pragma: no cover - defensive only
                briefs.append(self._all_error_brief(book_id=book_id, reason=str(exc)))
        return briefs

    # ------------------------------------------------------------------
    # Section builders — one per tool
    # ------------------------------------------------------------------

    async def _var_section(self, *, book_id: str, user: UserContext) -> BriefSection:
        try:
            result = await get_book_var(
                book_id=book_id, user=user, http=self._http, now=self._now
            )
        except KinetixHttpError as exc:
            return self._error_section(_TITLE_VAR, exc)
        except Exception as exc:  # noqa: BLE001 - batch must never crash
            return self._unexpected_error_section(_TITLE_VAR, exc)

        total_var = result["total_var"]
        confidence = result["confidence_level"]
        citation: Citation = result["citation"]
        bullets = [f"Total VaR: {_money(total_var)} ({confidence})"]
        for row in result["var_by_asset_class"][:3]:
            bullets.append(
                f"{row['asset_class']}: {_money(row['var_contribution'])} "
                f"({row['percentage_of_total']:.0f}%)"
            )
        narrative = (
            f"Book VaR stands at {_money(total_var)} at {confidence} confidence."
        )
        return BriefSection(
            title=_TITLE_VAR,
            narrative=narrative,
            bullets=bullets,
            sources=[citation],
            severity=_SEVERITY_INFO,
            status=_STATUS_OK,
        )

    async def _pnl_section(self, *, book_id: str, user: UserContext) -> BriefSection:
        try:
            result = await get_pnl_attribution(
                book_id=book_id, user=user, http=self._http, now=self._now
            )
        except KinetixHttpError as exc:
            return self._error_section(_TITLE_PNL, exc)
        except Exception as exc:  # noqa: BLE001 - batch must never crash
            return self._unexpected_error_section(_TITLE_PNL, exc)

        total_pnl = result["total_pnl"]
        components = result["components"]
        data_quality = result["data_quality"]
        citation = result["citation"]
        bullets = [
            f"Day P&L: {_money(total_pnl)}",
            f"Delta P&L: {_money(components['delta_pnl'])}",
            f"Vega P&L: {_money(components['vega_pnl'])}",
            f"Unexplained P&L: {_money(components['unexplained_pnl'])}",
        ]
        narrative = (
            f"P&L vs start of day is {_money(total_pnl)} "
            f"(data quality: {data_quality})."
        )
        severity = _SEVERITY_INFO
        if any(marker in str(data_quality).upper() for marker in _PNL_DEGRADED_MARKERS):
            severity = _SEVERITY_WARNING
        return BriefSection(
            title=_TITLE_PNL,
            narrative=narrative,
            bullets=bullets,
            sources=[citation],
            severity=severity,
            status=_STATUS_OK,
        )

    async def _breaches_section(
        self, *, book_id: str, user: UserContext
    ) -> BriefSection:
        try:
            result = await get_recent_breaches(
                book_id=book_id, user=user, http=self._http, now=self._now
            )
        except KinetixHttpError as exc:
            return self._error_section(_TITLE_BREACHES, exc)
        except Exception as exc:  # noqa: BLE001 - batch must never crash
            return self._unexpected_error_section(_TITLE_BREACHES, exc)

        recent_count = result["recent_count"]
        open_count = result["open_count"]
        citation = result["citation"]
        bullets = [
            f"Recent breaches: {recent_count}",
            f"Open breaches: {open_count}",
        ]
        if open_count > 0:
            narrative = (
                f"{open_count} open limit breach(es) need attention — "
                f"{recent_count} breach(es) in the recent window."
            )
            severity = _SEVERITY_CRITICAL
        else:
            narrative = (
                f"No open limit breaches; {recent_count} breach(es) in the "
                "recent window."
            )
            severity = _SEVERITY_INFO
        return BriefSection(
            title=_TITLE_BREACHES,
            narrative=narrative,
            bullets=bullets,
            sources=[citation],
            severity=severity,
            status=_STATUS_OK,
        )

    async def _limits_section(
        self, *, book_id: str, user: UserContext
    ) -> BriefSection:
        try:
            result = await get_limit_utilisation(
                book_id=book_id, user=user, http=self._http, now=self._now
            )
        except KinetixHttpError as exc:
            return self._error_section(_TITLE_LIMITS, exc)
        except Exception as exc:  # noqa: BLE001 - batch must never crash
            return self._unexpected_error_section(_TITLE_LIMITS, exc)

        limits = result["limits"]
        returned_count = result["returned_count"]
        citation = result["citation"]
        statuses = {str(row.get("status", "")).upper() for row in limits}
        if "RED" in statuses:
            severity = _SEVERITY_CRITICAL
        elif "AMBER" in statuses:
            severity = _SEVERITY_WARNING
        else:
            # v2 limit tool returns "UNKNOWN" — no live utilisation wired.
            severity = _SEVERITY_INFO
        bullets = [f"Book-level limit definitions: {returned_count}"]
        for row in limits[:3]:
            bullets.append(f"{row['limit_type']}: cap {_money(row['limit'])}")
        narrative = (
            f"{returned_count} book-level limit definition(s) in force; "
            "live utilisation is not exposed upstream in v2."
        )
        return BriefSection(
            title=_TITLE_LIMITS,
            narrative=narrative,
            bullets=bullets,
            sources=[citation],
            severity=severity,
            status=_STATUS_OK,
        )

    async def _greeks_section(
        self, *, book_id: str, user: UserContext
    ) -> BriefSection:
        try:
            result = await get_greeks_summary(
                book_id=book_id, user=user, http=self._http, now=self._now
            )
        except KinetixHttpError as exc:
            return self._error_section(_TITLE_GREEKS, exc)
        except Exception as exc:  # noqa: BLE001 - batch must never crash
            return self._unexpected_error_section(_TITLE_GREEKS, exc)

        aggregate = result["aggregate"]
        citation = result["citation"]
        bullets = [
            f"Delta: {aggregate['delta']:,.0f}",
            f"Gamma: {aggregate['gamma']:,.0f}",
            f"Vega: {aggregate['vega']:,.0f}",
            f"Theta: {aggregate['theta']:,.0f}",
        ]
        narrative = (
            f"Book Greeks vs start of day: delta {aggregate['delta']:,.0f}, "
            f"vega {aggregate['vega']:,.0f}."
        )
        return BriefSection(
            title=_TITLE_GREEKS,
            narrative=narrative,
            bullets=bullets,
            sources=[citation],
            severity=_SEVERITY_INFO,
            status=_STATUS_OK,
        )

    # ------------------------------------------------------------------
    # Error-section helpers
    # ------------------------------------------------------------------

    @staticmethod
    def _error_section(title: str, error: KinetixHttpError) -> BriefSection:
        """Build an ``error``/``timeout`` section from a tool failure."""

        status = _STATUS_TIMEOUT if _is_timeout(error) else _STATUS_ERROR
        narrative = f"{title} section unavailable: {error.message}"
        return BriefSection(
            title=title,
            narrative=narrative,
            bullets=[],
            sources=[],
            severity=_SEVERITY_WARNING,
            status=status,
        )

    @staticmethod
    def _unexpected_error_section(title: str, error: Exception) -> BriefSection:
        """Build an ``error`` section from a non-``KinetixHttpError`` failure.

        The batch must never crash — an unexpected exception in one tool
        is surfaced as an error section like any other failure.
        """

        narrative = f"{title} section unavailable: {error}"
        return BriefSection(
            title=title,
            narrative=narrative,
            bullets=[],
            sources=[],
            severity=_SEVERITY_WARNING,
            status=_STATUS_ERROR,
        )

    def _all_error_brief(self, *, book_id: str, reason: str) -> MorningBrief:
        """Build a brief whose every section is an error — last-resort fallback.

        Only reached if ``generate`` itself somehow raises, which should
        be impossible given it is internally resilient. Kept as a hard
        guarantee that ``generate_all`` never aborts the batch.
        """

        titles = (
            _TITLE_VAR,
            _TITLE_PNL,
            _TITLE_BREACHES,
            _TITLE_LIMITS,
            _TITLE_GREEKS,
        )
        sections = [
            BriefSection(
                title=title,
                narrative=f"{title} section unavailable: {reason}",
                bullets=[],
                sources=[],
                severity=_SEVERITY_WARNING,
                status=_STATUS_ERROR,
            )
            for title in titles
        ]
        return MorningBrief(
            book_id=book_id,
            sections=sections,
            generated_at=self._now(),
            mode=self._mode,
        )

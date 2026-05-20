"""Unit tests for :class:`MorningBriefGenerator` (checkbox 6.5).

The generator composes the five v2 read tools — ``get_book_var``,
``get_pnl_attribution``, ``get_recent_breaches``,
``get_limit_utilisation`` and ``get_greeks_summary`` — into a per-book
:class:`MorningBrief`. These tests stub the ``KinetixHttpClient`` with
:class:`tests.fakes.fake_kinetix_http_client.FakeKinetixHttpClient` and
assert that:

* a happy-path call produces exactly five ``ok`` sections in tool order,
* ``book_id`` / ``generated_at`` / ``mode`` are stamped correctly,
* a failure in ONE tool surfaces an ``error``/``timeout`` section
  without aborting the remaining four sections or other books,
* severity is derived deterministically from the tool payloads,
* ``generate_all`` yields one brief per book and never aborts the batch,
* the models round-trip through JSON with citations intact.

No network — the five tools are exercised entirely via the fake.
"""

from __future__ import annotations

from datetime import datetime, timezone

import pytest

from kinetix_insights.brief.generator import MorningBriefGenerator
from kinetix_insights.brief.models import BriefSection, MorningBrief
from kinetix_insights.clients.kinetix_http_client import KinetixHttpError
from kinetix_insights.clients.user_context import UserContext
from tests.fakes.fake_kinetix_http_client import FakeKinetixHttpClient

pytestmark = pytest.mark.unit


# ---------------------------------------------------------------------------
# Constants — endpoints the five tools fan out to
# ---------------------------------------------------------------------------

_BOOK = "fx-main"
_USER = UserContext(user_id="trader-1", books=(_BOOK, "rates-emea"))


def _var_path(book: str) -> str:
    return f"/api/v1/risk/var/{book}"


def _pnl_path(book: str) -> str:
    return f"/api/v1/risk/pnl-attribution/{book}"


def _breaches_path(book: str) -> str:
    return f"/api/v1/books/{book}/limit-breaches"


_LIMITS_PATH = "/api/v1/limits"


def _fixed_now() -> datetime:
    return datetime(2026, 5, 20, 16, 0, 0, tzinfo=timezone.utc)


# ---------------------------------------------------------------------------
# Sample upstream payloads
# ---------------------------------------------------------------------------


def _var_response(book: str = _BOOK) -> dict[str, object]:
    """Upstream ``VaRResultResponse`` — also carries ``greeks`` for the
    Greeks tool which shares this endpoint."""

    return {
        "bookId": book,
        "calculationType": "PARAMETRIC",
        "confidenceLevel": "CL_95",
        "varValue": "1234567.89",
        "expectedShortfall": "1500000.00",
        "componentBreakdown": [
            {
                "assetClass": "FX",
                "varContribution": "1000000.00",
                "percentageOfTotal": "81.00",
            },
            {
                "assetClass": "RATES",
                "varContribution": "234567.89",
                "percentageOfTotal": "19.00",
            },
        ],
        "calculatedAt": "2026-05-20T08:00:00Z",
        "marketDataComplete": True,
        "greeks": {
            "theta": "-12000.0",
            "rho": "5000.0",
            "assetClassGreeks": [
                {"delta": "250000.0", "gamma": "1200.0", "vega": "8000.0"},
            ],
        },
        "positionGreeks": [
            {
                "instrumentId": "EURUSD_CALL_20260601_1.09",
                "delta": "250000.0",
                "gamma": "1200.0",
                "vega": "8000.0",
                "theta": "-12000.0",
                "rho": "5000.0",
            }
        ],
    }


def _pnl_response(book: str = _BOOK) -> dict[str, object]:
    """Upstream daily ``PnlAttributionResponse``."""

    return {
        "bookId": book,
        "date": "2026-05-20",
        "totalPnl": "85000.0",
        "deltaPnl": "60000.0",
        "gammaPnl": "5000.0",
        "vegaPnl": "10000.0",
        "thetaPnl": "-3000.0",
        "rhoPnl": "1000.0",
        "vannaPnl": "500.0",
        "volgaPnl": "300.0",
        "charmPnl": "200.0",
        "crossGammaPnl": "1000.0",
        "unexplainedPnl": "10000.0",
        "calculatedAt": "2026-05-20T08:00:00Z",
        "dataQualityFlag": "FULL_ATTRIBUTION",
    }


def _breach_row(*, is_open: bool, breach_id: str = "b-1") -> dict[str, object]:
    return {
        "id": breach_id,
        "entityId": _BOOK,
        "limitType": "NOTIONAL",
        "severity": "CRITICAL",
        "currentValue": "12000000.0",
        "limitValue": "10000000.0",
        "breachedAt": "2026-05-20T07:30:00Z",
        "resolvedAt": None if is_open else "2026-05-20T09:00:00Z",
    }


def _breaches_response(*, open_count: int) -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    for i in range(open_count):
        rows.append(_breach_row(is_open=True, breach_id=f"open-{i}"))
    rows.append(_breach_row(is_open=False, breach_id="resolved-1"))
    return rows


def _limits_response(book: str = _BOOK) -> list[dict[str, object]]:
    return [
        {
            "level": "BOOK",
            "entityId": book,
            "limitType": "NOTIONAL",
            "limitValue": "10000000.00",
            "intradayLimit": "8000000.00",
            "overnightLimit": None,
            "active": True,
        },
        {
            "level": "BOOK",
            "entityId": book,
            "limitType": "VAR",
            "limitValue": "2000000.00",
            "intradayLimit": None,
            "overnightLimit": None,
            "active": True,
        },
    ]


def _register_happy_path(fake: FakeKinetixHttpClient, book: str = _BOOK) -> None:
    """Seed all five tools' endpoints so ``generate`` yields five ok sections."""

    fake.register_response(
        "GET", "risk-orchestrator", _var_path(book), _var_response(book)
    )
    fake.register_response(
        "GET", "risk-orchestrator", _pnl_path(book), _pnl_response(book)
    )
    fake.register_response(
        "GET", "position", _breaches_path(book), _breaches_response(open_count=0)
    )
    fake.register_response(
        "GET", "position", _LIMITS_PATH, _limits_response(book)
    )


def _generator(fake: FakeKinetixHttpClient, *, mode: str | None = None) -> MorningBriefGenerator:
    if mode is None:
        return MorningBriefGenerator(http=fake, now=_fixed_now)
    return MorningBriefGenerator(http=fake, now=_fixed_now, mode=mode)


_EXPECTED_TITLES = [
    "Value at Risk",
    "P&L Attribution",
    "Recent Breaches",
    "Limit Utilisation",
    "Greeks",
]


# ---------------------------------------------------------------------------
# Happy path
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_generate_produces_five_sections() -> None:
    fake = FakeKinetixHttpClient()
    _register_happy_path(fake)

    brief = await _generator(fake).generate(book_id=_BOOK, user=_USER)

    assert isinstance(brief, MorningBrief)
    assert len(brief.sections) == 5
    assert [section.title for section in brief.sections] == _EXPECTED_TITLES


@pytest.mark.asyncio
async def test_generate_sections_all_ok_on_happy_path() -> None:
    fake = FakeKinetixHttpClient()
    _register_happy_path(fake)

    brief = await _generator(fake).generate(book_id=_BOOK, user=_USER)

    assert all(section.status == "ok" for section in brief.sections)


@pytest.mark.asyncio
async def test_generate_sets_book_id_generated_at_and_mode() -> None:
    fake = FakeKinetixHttpClient()
    _register_happy_path(fake)

    brief = await _generator(fake, mode="canned").generate(book_id=_BOOK, user=_USER)

    assert brief.book_id == _BOOK
    assert brief.generated_at == _fixed_now()
    assert brief.mode == "canned"


@pytest.mark.asyncio
async def test_generate_var_section_has_narrative_and_sources() -> None:
    fake = FakeKinetixHttpClient()
    _register_happy_path(fake)

    brief = await _generator(fake).generate(book_id=_BOOK, user=_USER)

    var_section = brief.sections[0]
    assert var_section.title == "Value at Risk"
    assert var_section.narrative.strip() != ""
    assert len(var_section.sources) == 1
    assert var_section.sources[0].tool == "get_book_var"


# ---------------------------------------------------------------------------
# Per-tool error resilience
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_one_tool_error_does_not_abort_the_brief() -> None:
    fake = FakeKinetixHttpClient()
    _register_happy_path(fake)
    # Override the P&L endpoint with an upstream error.
    fake.register_response(
        "GET",
        "risk-orchestrator",
        _pnl_path(_BOOK),
        KinetixHttpError(
            status_code=502,
            code="UPSTREAM_ERROR",
            message="risk engine unreachable",
            service="risk-orchestrator",
            path=_pnl_path(_BOOK),
        ),
    )

    brief = await _generator(fake).generate(book_id=_BOOK, user=_USER)

    assert len(brief.sections) == 5
    statuses = {section.title: section.status for section in brief.sections}
    assert statuses["P&L Attribution"] == "error"
    assert statuses["Value at Risk"] == "ok"
    assert statuses["Recent Breaches"] == "ok"
    assert statuses["Limit Utilisation"] == "ok"
    assert statuses["Greeks"] == "ok"


@pytest.mark.asyncio
async def test_tool_timeout_surfaces_timeout_status() -> None:
    fake = FakeKinetixHttpClient()
    _register_happy_path(fake)
    fake.register_response(
        "GET",
        "position",
        _LIMITS_PATH,
        KinetixHttpError(
            status_code=504,
            code="TIMEOUT",
            message="upstream timed out",
            service="position",
            path=_LIMITS_PATH,
        ),
    )

    brief = await _generator(fake).generate(book_id=_BOOK, user=_USER)

    limits_section = next(s for s in brief.sections if s.title == "Limit Utilisation")
    assert limits_section.status == "timeout"


@pytest.mark.asyncio
async def test_error_section_has_warning_severity_and_explanatory_narrative() -> None:
    fake = FakeKinetixHttpClient()
    _register_happy_path(fake)
    fake.register_response(
        "GET",
        "risk-orchestrator",
        _var_path(_BOOK),
        KinetixHttpError(
            status_code=502,
            code="UPSTREAM_ERROR",
            message="risk engine unreachable",
            service="risk-orchestrator",
            path=_var_path(_BOOK),
        ),
    )

    brief = await _generator(fake).generate(book_id=_BOOK, user=_USER)

    var_section = brief.sections[0]
    assert var_section.status == "error"
    assert var_section.severity == "warning"
    assert "unavailable" in var_section.narrative.lower()
    assert var_section.bullets == []
    assert var_section.sources == []


# ---------------------------------------------------------------------------
# Severity derivation
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_breaches_section_is_critical_when_open_breaches_present() -> None:
    fake = FakeKinetixHttpClient()
    _register_happy_path(fake)
    fake.register_response(
        "GET",
        "position",
        _breaches_path(_BOOK),
        _breaches_response(open_count=2),
    )

    brief = await _generator(fake).generate(book_id=_BOOK, user=_USER)

    breaches_section = next(s for s in brief.sections if s.title == "Recent Breaches")
    assert breaches_section.severity == "critical"


@pytest.mark.asyncio
async def test_breaches_section_is_info_when_no_open_breaches() -> None:
    fake = FakeKinetixHttpClient()
    _register_happy_path(fake)
    fake.register_response(
        "GET",
        "position",
        _breaches_path(_BOOK),
        _breaches_response(open_count=0),
    )

    brief = await _generator(fake).generate(book_id=_BOOK, user=_USER)

    breaches_section = next(s for s in brief.sections if s.title == "Recent Breaches")
    assert breaches_section.severity == "info"


# ---------------------------------------------------------------------------
# generate_all — multi-book batch
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_generate_all_produces_one_brief_per_book() -> None:
    fake = FakeKinetixHttpClient()
    user = UserContext(user_id="trader-1", books=("fx-main", "rates-emea"))
    _register_happy_path(fake, "fx-main")
    fake.register_response(
        "GET", "risk-orchestrator", _var_path("rates-emea"), _var_response("rates-emea")
    )
    fake.register_response(
        "GET", "risk-orchestrator", _pnl_path("rates-emea"), _pnl_response("rates-emea")
    )
    fake.register_response(
        "GET", "position", _breaches_path("rates-emea"), _breaches_response(open_count=0)
    )
    # /api/v1/limits is shared — happy-path already registered it.

    briefs = await _generator(fake).generate_all(user=user)

    assert len(briefs) == 2
    assert [b.book_id for b in briefs] == ["fx-main", "rates-emea"]


@pytest.mark.asyncio
async def test_generate_all_one_book_failure_does_not_abort_others() -> None:
    fake = FakeKinetixHttpClient()
    user = UserContext(user_id="trader-1", books=("fx-main", "rates-emea"))
    _register_happy_path(fake, "fx-main")
    # Every "rates-emea" endpoint is explicitly seeded to fail upstream so
    # all five of that book's sections error — without aborting fx-main.
    rates_error = KinetixHttpError(
        status_code=502,
        code="UPSTREAM_ERROR",
        message="risk engine unreachable",
        service="risk-orchestrator",
        path="rates",
    )
    fake.register_response(
        "GET", "risk-orchestrator", _var_path("rates-emea"), rates_error
    )
    fake.register_response(
        "GET", "risk-orchestrator", _pnl_path("rates-emea"), rates_error
    )
    fake.register_response(
        "GET", "position", _breaches_path("rates-emea"), rates_error
    )
    # The limits endpoint is shared across books; override it last so the
    # rates-emea limits section also errors. fx-main's limits section
    # therefore errors too — but fx-main's other four remain ok, proving
    # one failing tool does not abort a book's brief either.
    fake.register_response("GET", "position", _LIMITS_PATH, rates_error)

    briefs = await _generator(fake).generate_all(user=user)

    assert len(briefs) == 2
    assert [b.book_id for b in briefs] == ["fx-main", "rates-emea"]
    rates_brief = next(b for b in briefs if b.book_id == "rates-emea")
    assert all(s.status == "error" for s in rates_brief.sections)
    fx_brief = next(b for b in briefs if b.book_id == "fx-main")
    # fx-main still produced a full five-section brief despite the loop
    # continuing past rates-emea's total failure.
    assert len(fx_brief.sections) == 5


# ---------------------------------------------------------------------------
# ACL handling
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_acl_failure_on_a_tool_becomes_an_error_section() -> None:
    fake = FakeKinetixHttpClient()
    # ``orphan-book`` is NOT in the user's scope -> book-scoped tools raise
    # KinetixHttpError(UNAUTHORIZED, 403) before touching the fake.
    user = UserContext(user_id="trader-1", books=("fx-main",))

    brief = await _generator(fake).generate(book_id="orphan-book", user=user)

    assert len(brief.sections) == 5
    assert all(section.status == "error" for section in brief.sections)
    assert all(section.severity == "warning" for section in brief.sections)


# ---------------------------------------------------------------------------
# Mode default
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_mode_defaults_to_live() -> None:
    fake = FakeKinetixHttpClient()
    _register_happy_path(fake)

    brief = await _generator(fake).generate(book_id=_BOOK, user=_USER)

    assert brief.mode == "live"


# ---------------------------------------------------------------------------
# Model round-trip
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_models_round_trip() -> None:
    fake = FakeKinetixHttpClient()
    _register_happy_path(fake)

    brief = await _generator(fake).generate(book_id=_BOOK, user=_USER)

    rebuilt = MorningBrief.model_validate_json(brief.model_dump_json())

    assert rebuilt == brief
    # The VaR section's citation survived the JSON round trip intact.
    assert rebuilt.sections[0].sources[0].tool == "get_book_var"
    assert isinstance(rebuilt.sections[0], BriefSection)

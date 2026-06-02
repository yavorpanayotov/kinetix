"""Unit tests for the ``get_recent_breaches`` MCP tool.

These tests stub the ``KinetixHttpClient`` with
:class:`tests.fakes.fake_kinetix_http_client.FakeKinetixHttpClient` and
assert that:

* the tool routes to the conventional position-service endpoint
  (``GET /api/v1/books/{book_id}/limit-breaches``) with no query
  parameters and forwards the caller's :class:`UserContext`,
* the upstream ``limit_breach_events`` JSON array is mapped to the v2
  tool-output shape defined in ``docs/plans/ai-v2.md`` § PR 6,
* client-side filtering (``since`` window, default last 7 days) and
  sort-by-``breachedAt``-desc behave as specified,
* ``open_count`` and the ``OPEN_BREACHES`` quality flag track the
  number of unresolved breaches in the returned window,
* invalid ``since`` raises ``BAD_REQUEST`` BEFORE any HTTP call,
* book-level ACL fails closed before the HTTP client is ever touched,
* a single :class:`Citation` describing the ``recent_count`` value is
  populated with the expected provenance fields,
* upstream ``NOT_FOUND`` and ``UPSTREAM_ERROR`` errors propagate
  unmodified,
* non-list upstream payloads are rejected as ``UPSTREAM_ERROR``.

No network, no FastMCP wiring — registry wiring for this 11th tool is a
v2 follow-up (the original 10 were wired in checkbox 2.11).
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

import pytest

from kinetix_insights.clients.kinetix_http_client import KinetixHttpError
from kinetix_insights.clients.user_context import UserContext
from kinetix_insights.mcp.tools.get_recent_breaches import get_recent_breaches
from tests.fakes.fake_kinetix_http_client import FakeKinetixHttpClient

pytestmark = pytest.mark.unit


# ---------------------------------------------------------------------------
# Fixture constants
# ---------------------------------------------------------------------------


_DEFAULT_USER = UserContext(user_id="trader-1", books=("fx-main", "rates-emea"))

_SERVICE = "position"
_BREACHES_PATH = "/api/v1/books/fx-main/limit-breaches"


def _fixed_now() -> datetime:
    return datetime(2026, 5, 19, 16, 0, 0, tzinfo=timezone.utc)


def _breach(
    *,
    breach_id: str,
    breached_at: str,
    entity_id: str = "fx-main",
    book_id: str = "fx-main",
    limit_type: str = "VAR",
    severity: str = "HARD",
    current_value: str = "6200000.00",
    limit_value: str = "5000000.00",
    resolved_at: str | None = None,
) -> dict[str, Any]:
    """Build a representative upstream ``limit_breach_events`` row."""

    return {
        "id": breach_id,
        "entityId": entity_id,
        "bookId": book_id,
        "limitType": limit_type,
        "severity": severity,
        "currentValue": current_value,
        "limitValue": limit_value,
        "breachedAt": breached_at,
        "resolvedAt": resolved_at,
    }


# Canonical mixed fixture: breaches spread over time, with a mix of
# resolved and open rows, used by most tests.
_MIXED_BREACHES: list[dict[str, Any]] = [
    _breach(
        breach_id="breach-001",
        breached_at="2026-05-19T07:30:00Z",
        limit_type="VAR",
        severity="HARD",
        resolved_at=None,
    ),
    _breach(
        breach_id="breach-002",
        breached_at="2026-05-18T09:15:00Z",
        limit_type="NOTIONAL",
        severity="SOFT",
        resolved_at="2026-05-18T10:00:00Z",
    ),
    _breach(
        breach_id="breach-003",
        breached_at="2026-05-16T11:00:00Z",
        limit_type="POSITION",
        severity="HARD",
        resolved_at=None,
    ),
]


# ---------------------------------------------------------------------------
# Endpoint and parameter wiring
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_calls_correct_endpoint() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _BREACHES_PATH, [])

    await get_recent_breaches(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert len(fake.recorded_calls) == 1
    call = fake.recorded_calls[0]
    assert call.method == "GET"
    assert call.service == _SERVICE
    assert call.path == _BREACHES_PATH
    assert call.params is None
    assert call.user == _DEFAULT_USER


# ---------------------------------------------------------------------------
# Field mapping
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_maps_response_fields() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE,
        _BREACHES_PATH,
        [
            _breach(
                breach_id="breach-open",
                breached_at="2026-05-19T07:30:00Z",
                entity_id="fx-main",
                limit_type="VAR",
                severity="HARD",
                current_value="6200000.00",
                limit_value="5000000.00",
                resolved_at=None,
            ),
            _breach(
                breach_id="breach-resolved",
                breached_at="2026-05-18T09:15:00Z",
                entity_id="fx-main",
                limit_type="NOTIONAL",
                severity="SOFT",
                current_value="9000000.00",
                limit_value="8000000.00",
                resolved_at="2026-05-18T10:00:00Z",
            ),
        ],
    )

    result = await get_recent_breaches(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["book_id"] == "fx-main"
    by_id = {row["id"]: row for row in result["breaches"]}

    open_row = by_id["breach-open"]
    assert open_row["entity_id"] == "fx-main"
    assert open_row["limit_type"] == "VAR"
    assert open_row["severity"] == "HARD"
    assert open_row["current_value"] == 6200000.00
    assert isinstance(open_row["current_value"], float)
    assert open_row["limit_value"] == 5000000.00
    assert isinstance(open_row["limit_value"], float)
    assert open_row["breached_at"] == "2026-05-19T07:30:00Z"
    assert open_row["resolved_at"] is None
    assert open_row["is_open"] is True

    resolved_row = by_id["breach-resolved"]
    assert resolved_row["resolved_at"] == "2026-05-18T10:00:00Z"
    assert resolved_row["is_open"] is False


# ---------------------------------------------------------------------------
# Client-side filtering
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_default_since_is_seven_days() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE,
        _BREACHES_PATH,
        [
            # 3 days before _fixed_now (2026-05-19) — survives 7-day window.
            _breach(breach_id="breach-recent", breached_at="2026-05-16T12:00:00Z"),
            # 10 days before _fixed_now — outside 7-day window.
            _breach(breach_id="breach-stale", breached_at="2026-05-09T12:00:00Z"),
        ],
    )

    result = await get_recent_breaches(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    ids = {row["id"] for row in result["breaches"]}
    assert ids == {"breach-recent"}
    assert result["recent_count"] == 1
    assert result["total_fetched"] == 2


@pytest.mark.asyncio
async def test_explicit_since_filters() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _BREACHES_PATH, _MIXED_BREACHES)

    result = await get_recent_breaches(
        book_id="fx-main",
        since="2026-05-17",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    ids = {row["id"] for row in result["breaches"]}
    # breach-003 breached 2026-05-16 — excluded by since=2026-05-17.
    assert "breach-003" not in ids
    assert ids == {"breach-001", "breach-002"}
    assert result["recent_count"] == 2
    assert result["total_fetched"] == 3


@pytest.mark.asyncio
async def test_sorts_breaches_newest_first() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _BREACHES_PATH, _MIXED_BREACHES)

    result = await get_recent_breaches(
        book_id="fx-main",
        since="2026-05-01",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    ids = [row["id"] for row in result["breaches"]]
    # Expected desc by breachedAt: 05-19 (001), 05-18 (002), 05-16 (003).
    assert ids == ["breach-001", "breach-002", "breach-003"]


# ---------------------------------------------------------------------------
# Open-breach counting
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_open_count_counts_unresolved() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _BREACHES_PATH, _MIXED_BREACHES)

    result = await get_recent_breaches(
        book_id="fx-main",
        since="2026-05-01",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    # breach-001 and breach-003 are open; breach-002 is resolved.
    assert result["open_count"] == 2
    open_ids = {row["id"] for row in result["breaches"] if row["is_open"]}
    assert open_ids == {"breach-001", "breach-003"}


@pytest.mark.asyncio
async def test_quality_flags_open_breaches_when_open_present() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _BREACHES_PATH, _MIXED_BREACHES)

    result = await get_recent_breaches(
        book_id="fx-main",
        since="2026-05-01",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert "OPEN_BREACHES" in result["citation"].quality_flags


@pytest.mark.asyncio
async def test_quality_flags_no_open_breaches_flag_when_all_resolved() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE,
        _BREACHES_PATH,
        [
            _breach(
                breach_id="breach-resolved-1",
                breached_at="2026-05-18T09:15:00Z",
                resolved_at="2026-05-18T10:00:00Z",
            ),
            _breach(
                breach_id="breach-resolved-2",
                breached_at="2026-05-17T09:15:00Z",
                resolved_at="2026-05-17T10:00:00Z",
            ),
        ],
    )

    result = await get_recent_breaches(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["open_count"] == 0
    assert "OPEN_BREACHES" not in result["citation"].quality_flags


# ---------------------------------------------------------------------------
# Citation population
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_citation_records_params_and_recent_count() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _BREACHES_PATH, _MIXED_BREACHES)

    result = await get_recent_breaches(
        book_id="fx-main",
        since="2026-05-17",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    citation = result["citation"]
    assert citation.tool == "get_recent_breaches"
    assert citation.params == {"book_id": "fx-main", "since": "2026-05-17"}
    assert citation.result_field == "recent_count"
    assert citation.result_value == float(result["recent_count"])
    assert citation.result_currency == "USD"
    assert citation.data_source == "position-service"
    assert citation.as_of_timestamp == _fixed_now()
    assert citation.freshness_seconds == 0


# ---------------------------------------------------------------------------
# Input validation (must run BEFORE any HTTP call)
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_invalid_since_raises_bad_request_before_http() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _BREACHES_PATH, _MIXED_BREACHES)

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_recent_breaches(
            book_id="fx-main",
            since="not-a-date",
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "BAD_REQUEST"
    assert excinfo.value.status_code == 400
    assert fake.recorded_calls == []


# ---------------------------------------------------------------------------
# ACL / fails closed
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_fails_closed_when_book_not_in_user_scope() -> None:
    fake = FakeKinetixHttpClient()
    user = UserContext(user_id="t1", books=("rates-only",))

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_recent_breaches(
            book_id="fx-main",
            user=user,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "UNAUTHORIZED"
    assert excinfo.value.status_code == 403
    assert fake.recorded_calls == []


# ---------------------------------------------------------------------------
# Upstream error propagation
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_propagates_not_found() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE,
        _BREACHES_PATH,
        KinetixHttpError(
            status_code=404,
            code="NOT_FOUND",
            message="route misrouted",
            service=_SERVICE,
            path=_BREACHES_PATH,
        ),
    )

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_recent_breaches(
            book_id="fx-main",
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "NOT_FOUND"
    assert excinfo.value.status_code == 404


@pytest.mark.asyncio
async def test_propagates_upstream_error() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE,
        _BREACHES_PATH,
        KinetixHttpError(
            status_code=502,
            code="UPSTREAM_ERROR",
            message="position-service unreachable",
            service=_SERVICE,
            path=_BREACHES_PATH,
        ),
    )

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_recent_breaches(
            book_id="fx-main",
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "UPSTREAM_ERROR"
    assert excinfo.value.status_code == 502


# ---------------------------------------------------------------------------
# Empty and malformed upstream
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_handles_empty_book() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _BREACHES_PATH, [])

    result = await get_recent_breaches(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["breaches"] == []
    assert result["total_fetched"] == 0
    assert result["recent_count"] == 0
    assert result["open_count"] == 0
    assert result["citation"].result_value == 0.0
    assert "OPEN_BREACHES" not in result["citation"].quality_flags


@pytest.mark.asyncio
async def test_raises_upstream_error_on_non_list_payload() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE,
        _BREACHES_PATH,
        {"unexpected": "object"},
    )

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_recent_breaches(
            book_id="fx-main",
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "UPSTREAM_ERROR"
    assert excinfo.value.status_code == 502

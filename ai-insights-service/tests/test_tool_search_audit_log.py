"""Unit tests for the ``search_audit_log`` MCP tool.

These tests stub the ``KinetixHttpClient`` with
:class:`tests.fakes.fake_kinetix_http_client.FakeKinetixHttpClient` and
assert that:

* the tool routes to the audit-service endpoint
  (``GET /api/v1/audit/events``) with ``limit=500`` and forwards
  ``bookId`` only when a ``book_id`` is supplied,
* the effective time window resolves to the last 7 days when neither
  ``since`` nor ``until`` is supplied (the checkbox's headline
  assertion),
* one-sided ``since``/``until`` windows extend correctly,
* client-side time-window and case-insensitive text filtering behave
  as specified,
* events are sorted by ``receivedAt`` descending,
* ``total_fetched`` / ``match_count`` track raw upstream length vs the
  post-filter count,
* empty ``query``, malformed ``since``/``until``, and ``since`` after
  ``until`` all raise ``BAD_REQUEST`` BEFORE any HTTP call,
* book-level ACL fails closed when a supplied ``book_id`` is outside
  the caller's scope, but an omitted ``book_id`` runs unscoped,
* a single :class:`Citation` describing ``match_count`` is populated
  with the expected provenance fields,
* the ``RESULT_TRUNCATED`` quality flag appears only when the upstream
  returns exactly the 500-row page cap,
* upstream ``NOT_FOUND`` and ``UPSTREAM_ERROR`` errors propagate
  unmodified and non-list payloads are rejected as ``UPSTREAM_ERROR``.

No network, no FastMCP wiring — registry wiring for this tool is a v2
follow-up.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

import pytest

from kinetix_insights.clients.kinetix_http_client import KinetixHttpError
from kinetix_insights.clients.user_context import UserContext
from kinetix_insights.mcp.tools.search_audit_log import search_audit_log
from tests.fakes.fake_kinetix_http_client import FakeKinetixHttpClient

pytestmark = pytest.mark.unit


# ---------------------------------------------------------------------------
# Fixture constants
# ---------------------------------------------------------------------------


_DEFAULT_USER = UserContext(user_id="trader-1", books=("fx-main", "rates-emea"))

_SERVICE = "audit"
_EVENTS_PATH = "/api/v1/audit/events"
_FETCH_LIMIT = 500


def _fixed_now() -> datetime:
    return datetime(2026, 5, 20, 16, 0, 0, tzinfo=timezone.utc)


def _event(
    *,
    event_id: int,
    received_at: str,
    event_type: str = "TRADE_BOOKED",
    book_id: str | None = "fx-main",
    user_id: str | None = "trader-1",
    user_role: str | None = "TRADER",
    trade_id: str | None = "T-1",
    instrument_id: str | None = "EURUSD",
    details: str | None = None,
    model_name: str | None = None,
    scenario_id: str | None = None,
    limit_id: str | None = None,
    submission_id: str | None = None,
) -> dict[str, Any]:
    """Build a representative upstream ``AuditEventResponse`` row."""

    return {
        "id": event_id,
        "tradeId": trade_id,
        "bookId": book_id,
        "instrumentId": instrument_id,
        "assetClass": "FX",
        "side": "BUY",
        "quantity": "1000000",
        "priceAmount": "1.09",
        "priceCurrency": "USD",
        "tradedAt": received_at,
        "receivedAt": received_at,
        "previousHash": "prev",
        "recordHash": "hash",
        "userId": user_id,
        "userRole": user_role,
        "eventType": event_type,
        "modelName": model_name,
        "scenarioId": scenario_id,
        "limitId": limit_id,
        "submissionId": submission_id,
        "details": details,
        "sequenceNumber": event_id,
    }


# Canonical mixed fixture: events spread over time inside a default
# 7-day window ending 2026-05-20, all matching the term "TRADE_BOOKED".
_MIXED_EVENTS: list[dict[str, Any]] = [
    _event(event_id=1, received_at="2026-05-20T07:30:00Z"),
    _event(event_id=2, received_at="2026-05-18T09:15:00Z"),
    _event(event_id=3, received_at="2026-05-16T11:00:00Z"),
]


# ---------------------------------------------------------------------------
# Endpoint and parameter wiring
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_calls_correct_endpoint() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _EVENTS_PATH, [])

    await search_audit_log(
        query="TRADE_BOOKED",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert len(fake.recorded_calls) == 1
    call = fake.recorded_calls[0]
    assert call.method == "GET"
    assert call.service == _SERVICE
    assert call.path == _EVENTS_PATH
    assert call.params is not None
    assert call.params["limit"] == _FETCH_LIMIT
    assert "bookId" not in call.params
    assert call.user == _DEFAULT_USER


@pytest.mark.asyncio
async def test_forwards_book_id_as_bookId_param() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _EVENTS_PATH, [])

    await search_audit_log(
        query="TRADE_BOOKED",
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    call = fake.recorded_calls[0]
    assert call.params is not None
    assert call.params["bookId"] == "fx-main"


@pytest.mark.asyncio
async def test_omits_bookId_param_when_book_id_absent() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _EVENTS_PATH, [])

    await search_audit_log(
        query="TRADE_BOOKED",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    call = fake.recorded_calls[0]
    assert call.params is not None
    assert "bookId" not in call.params


# ---------------------------------------------------------------------------
# Window resolution
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_default_window_is_seven_days() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _EVENTS_PATH, [])

    result = await search_audit_log(
        query="TRADE_BOOKED",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    since = datetime.fromisoformat(result["window"]["since"])
    until = datetime.fromisoformat(result["window"]["until"])
    # until is end-of-day of the injected now.
    assert until == datetime(2026, 5, 20, 23, 59, 59, 999999, tzinfo=timezone.utc)
    # since is exactly 7 days before until.
    assert (until - since).days == 7


@pytest.mark.asyncio
async def test_since_only_window_extends_to_now() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _EVENTS_PATH, [])

    result = await search_audit_log(
        query="TRADE_BOOKED",
        since="2026-05-01",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    since = datetime.fromisoformat(result["window"]["since"])
    until = datetime.fromisoformat(result["window"]["until"])
    assert since == datetime(2026, 5, 1, 0, 0, 0, tzinfo=timezone.utc)
    assert until == datetime(2026, 5, 20, 23, 59, 59, 999999, tzinfo=timezone.utc)


@pytest.mark.asyncio
async def test_until_only_window_starts_seven_days_before() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _EVENTS_PATH, [])

    result = await search_audit_log(
        query="TRADE_BOOKED",
        until="2026-05-15",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    since = datetime.fromisoformat(result["window"]["since"])
    until = datetime.fromisoformat(result["window"]["until"])
    assert until == datetime(2026, 5, 15, 23, 59, 59, 999999, tzinfo=timezone.utc)
    assert (until - since).days == 7


# ---------------------------------------------------------------------------
# Client-side time filtering
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_time_filter_excludes_events_outside_window() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE,
        _EVENTS_PATH,
        [
            # 4 days before _fixed_now (2026-05-20) — inside 7-day window.
            _event(event_id=1, received_at="2026-05-16T12:00:00Z"),
            # 11 days before _fixed_now — outside 7-day window.
            _event(event_id=2, received_at="2026-05-09T12:00:00Z"),
        ],
    )

    result = await search_audit_log(
        query="TRADE_BOOKED",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    ids = {row["id"] for row in result["events"]}
    assert ids == {1}
    assert result["match_count"] == 1
    assert result["total_fetched"] == 2


# ---------------------------------------------------------------------------
# Text query filtering
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_text_query_matches_event_type() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE,
        _EVENTS_PATH,
        [
            _event(event_id=1, received_at="2026-05-19T07:00:00Z",
                   event_type="TRADE_BOOKED"),
            _event(event_id=2, received_at="2026-05-19T08:00:00Z",
                   event_type="LIMIT_UPDATED"),
        ],
    )

    result = await search_audit_log(
        query="TRADE_BOOKED",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    ids = {row["id"] for row in result["events"]}
    assert ids == {1}


@pytest.mark.asyncio
async def test_text_query_matches_details_field() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE,
        _EVENTS_PATH,
        [
            _event(event_id=1, received_at="2026-05-19T07:00:00Z",
                   event_type="MODEL_APPROVED",
                   details="approved by the model risk committee"),
            _event(event_id=2, received_at="2026-05-19T08:00:00Z",
                   event_type="MODEL_APPROVED",
                   details="rejected pending review"),
        ],
    )

    result = await search_audit_log(
        query="committee",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    ids = {row["id"] for row in result["events"]}
    assert ids == {1}


@pytest.mark.asyncio
async def test_text_query_is_case_insensitive() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE,
        _EVENTS_PATH,
        [_event(event_id=1, received_at="2026-05-19T07:00:00Z",
                event_type="TRADE_BOOKED")],
    )

    result = await search_audit_log(
        query="trade_booked",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert {row["id"] for row in result["events"]} == {1}


@pytest.mark.asyncio
async def test_text_query_skips_null_fields() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE,
        _EVENTS_PATH,
        [
            # details is None — must not crash; matched via userId instead.
            _event(event_id=1, received_at="2026-05-19T07:00:00Z",
                   user_id="auditor-9", details=None),
        ],
    )

    result = await search_audit_log(
        query="auditor-9",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert {row["id"] for row in result["events"]} == {1}


# ---------------------------------------------------------------------------
# Sorting
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_sorts_events_newest_first() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _EVENTS_PATH, _MIXED_EVENTS)

    result = await search_audit_log(
        query="TRADE_BOOKED",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    ids = [row["id"] for row in result["events"]]
    # Expected desc by receivedAt: 05-20 (1), 05-18 (2), 05-16 (3).
    assert ids == [1, 2, 3]


# ---------------------------------------------------------------------------
# Counts
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_match_count_and_total_fetched() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE,
        _EVENTS_PATH,
        [
            # In window, matches query.
            _event(event_id=1, received_at="2026-05-19T07:00:00Z",
                   event_type="TRADE_BOOKED"),
            # In window, does not match query.
            _event(event_id=2, received_at="2026-05-19T08:00:00Z",
                   event_type="LIMIT_UPDATED"),
            # Outside window.
            _event(event_id=3, received_at="2026-05-01T08:00:00Z",
                   event_type="TRADE_BOOKED"),
        ],
    )

    result = await search_audit_log(
        query="TRADE_BOOKED",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["total_fetched"] == 3
    assert result["match_count"] == 1


# ---------------------------------------------------------------------------
# Input validation (must run BEFORE any HTTP call)
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_empty_query_raises_bad_request_before_http() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _EVENTS_PATH, _MIXED_EVENTS)

    for bad_query in ("", "   "):
        with pytest.raises(KinetixHttpError) as excinfo:
            await search_audit_log(
                query=bad_query,
                user=_DEFAULT_USER,
                http=fake,
                now=_fixed_now,
            )
        assert excinfo.value.code == "BAD_REQUEST"
        assert excinfo.value.status_code == 400

    assert fake.recorded_calls == []


@pytest.mark.asyncio
async def test_invalid_since_raises_bad_request_before_http() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _EVENTS_PATH, _MIXED_EVENTS)

    with pytest.raises(KinetixHttpError) as excinfo:
        await search_audit_log(
            query="TRADE_BOOKED",
            since="not-a-date",
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "BAD_REQUEST"
    assert excinfo.value.status_code == 400
    assert fake.recorded_calls == []


@pytest.mark.asyncio
async def test_since_after_until_raises_bad_request() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _EVENTS_PATH, _MIXED_EVENTS)

    with pytest.raises(KinetixHttpError) as excinfo:
        await search_audit_log(
            query="TRADE_BOOKED",
            since="2026-05-19",
            until="2026-05-10",
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
async def test_fails_closed_when_supplied_book_not_in_user_scope() -> None:
    fake = FakeKinetixHttpClient()
    user = UserContext(user_id="t1", books=("rates-only",))

    with pytest.raises(KinetixHttpError) as excinfo:
        await search_audit_log(
            query="TRADE_BOOKED",
            book_id="fx-main",
            user=user,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "UNAUTHORIZED"
    assert excinfo.value.status_code == 403
    assert fake.recorded_calls == []


@pytest.mark.asyncio
async def test_no_acl_failure_when_book_id_omitted() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _EVENTS_PATH, [])
    user = UserContext(user_id="t1", books=("rates-only",))

    result = await search_audit_log(
        query="TRADE_BOOKED",
        user=user,
        http=fake,
        now=_fixed_now,
    )

    assert result["match_count"] == 0
    assert len(fake.recorded_calls) == 1


# ---------------------------------------------------------------------------
# Citation population
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_citation_records_params_and_match_count() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _EVENTS_PATH, _MIXED_EVENTS)

    result = await search_audit_log(
        query="TRADE_BOOKED",
        since="2026-05-15",
        until="2026-05-20",
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    citation = result["citation"]
    assert citation.tool == "search_audit_log"
    assert citation.params == {
        "query": "TRADE_BOOKED",
        "since": "2026-05-15",
        "until": "2026-05-20",
        "book_id": "fx-main",
    }
    assert citation.result_field == "match_count"
    assert citation.result_value == float(result["match_count"])
    assert citation.result_currency is None
    assert citation.data_source == "audit-service"
    assert citation.as_of_timestamp == _fixed_now()
    assert citation.freshness_seconds == 0


# ---------------------------------------------------------------------------
# Quality flags
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_quality_flag_result_truncated_when_page_cap_hit() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE,
        _EVENTS_PATH,
        [
            _event(event_id=i, received_at="2026-05-19T07:00:00Z")
            for i in range(_FETCH_LIMIT)
        ],
    )

    result = await search_audit_log(
        query="TRADE_BOOKED",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["total_fetched"] == _FETCH_LIMIT
    assert "RESULT_TRUNCATED" in result["citation"].quality_flags


@pytest.mark.asyncio
async def test_no_result_truncated_flag_below_page_cap() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _EVENTS_PATH, _MIXED_EVENTS)

    result = await search_audit_log(
        query="TRADE_BOOKED",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert "RESULT_TRUNCATED" not in result["citation"].quality_flags


# ---------------------------------------------------------------------------
# Upstream error propagation
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_propagates_not_found() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE,
        _EVENTS_PATH,
        KinetixHttpError(
            status_code=404,
            code="NOT_FOUND",
            message="route misrouted",
            service=_SERVICE,
            path=_EVENTS_PATH,
        ),
    )

    with pytest.raises(KinetixHttpError) as excinfo:
        await search_audit_log(
            query="TRADE_BOOKED",
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
        _EVENTS_PATH,
        KinetixHttpError(
            status_code=502,
            code="UPSTREAM_ERROR",
            message="audit-service unreachable",
            service=_SERVICE,
            path=_EVENTS_PATH,
        ),
    )

    with pytest.raises(KinetixHttpError) as excinfo:
        await search_audit_log(
            query="TRADE_BOOKED",
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "UPSTREAM_ERROR"
    assert excinfo.value.status_code == 502


@pytest.mark.asyncio
async def test_raises_upstream_error_on_non_list_payload() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE,
        _EVENTS_PATH,
        {"unexpected": "object"},
    )

    with pytest.raises(KinetixHttpError) as excinfo:
        await search_audit_log(
            query="TRADE_BOOKED",
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "UPSTREAM_ERROR"
    assert excinfo.value.status_code == 502

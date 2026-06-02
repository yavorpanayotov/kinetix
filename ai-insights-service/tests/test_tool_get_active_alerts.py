"""Unit tests for the ``get_active_alerts`` MCP tool.

These tests stub the ``KinetixHttpClient`` with
:class:`tests.fakes.fake_kinetix_http_client.FakeKinetixHttpClient` and
assert that:

* the tool routes to the correct notification-service endpoint
  (``GET /api/v1/notifications/alerts``) and always sends
  ``params={"limit": 200}`` so client-side filtering has enough headroom,
* the upstream ``List[AlertEventResponse]`` payload is mapped to the v2
  tool output shape defined in ``docs/plans/ai-v2.md`` § PR 2,
* client-side filtering (book scope, ACTIVE status, optional ``severity``
  and ``since``) and sort-by-``triggered_at``-desc behave as specified,
* invalid ``severity`` / ``since`` raise ``BAD_REQUEST`` BEFORE any HTTP
  call,
* book-level ACL fails closed before the HTTP client is ever touched,
* a single :class:`Citation` describing the ``active_count`` value is
  populated with the expected provenance fields and quality flags
  (always ``CROSS_BOOK_NOT_SUPPORTED``; conditionally
  ``INCLUDES_ACKNOWLEDGED`` / ``INCLUDES_ESCALATED``),
* upstream ``NOT_FOUND`` and ``UPSTREAM_ERROR`` errors propagate
  unmodified,
* non-list upstream payloads are rejected as ``UPSTREAM_ERROR``.

No network, no FastMCP wiring — that lands in checkbox 2.11.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

import pytest

from kinetix_insights.clients.kinetix_http_client import KinetixHttpError
from kinetix_insights.clients.user_context import UserContext
from kinetix_insights.mcp.tools.get_active_alerts import get_active_alerts
from tests.fakes.fake_kinetix_http_client import FakeKinetixHttpClient

pytestmark = pytest.mark.unit


# ---------------------------------------------------------------------------
# Fixture constants
# ---------------------------------------------------------------------------


_DEFAULT_USER = UserContext(user_id="trader-1", books=("fx-main", "rates-emea"))

_ALERTS_PATH = "/api/v1/notifications/alerts"
_SERVICE = "notification"


def _fixed_now() -> datetime:
    return datetime(2026, 5, 19, 16, 0, 0, tzinfo=timezone.utc)


def _alert(
    *,
    alert_id: str,
    book_id: str,
    severity: str,
    status: str,
    triggered_at: str,
    rule_id: str = "rule-var-breach",
    rule_name: str = "VaR > 5% NAV",
    alert_type: str = "VAR_LIMIT",
    message: str = "VaR breach on FX book",
    current_value: float = 6.2,
    threshold: float = 5.0,
    correlation_id: str | None = "corr-xyz",
    suggested_action: str | None = "Reduce EURUSD exposure",
) -> dict[str, Any]:
    """Build a representative upstream ``AlertEventResponse`` row."""

    return {
        "id": alert_id,
        "ruleId": rule_id,
        "ruleName": rule_name,
        "type": alert_type,
        "severity": severity,
        "message": message,
        "currentValue": current_value,
        "threshold": threshold,
        "bookId": book_id,
        "triggeredAt": triggered_at,
        "status": status,
        "resolvedAt": None,
        "resolvedReason": None,
        "escalatedAt": None,
        "escalatedTo": None,
        "correlationId": correlation_id,
        "suggestedAction": suggested_action,
        "snoozedUntil": None,
    }


# Canonical mixed-status, mixed-book, mixed-severity fixture used by most
# tests. Two books (fx-main, rates-emea), four statuses, varied severities
# and triggeredAt timestamps so we can exercise filters / sort.
_MIXED_ALERTS: list[dict[str, Any]] = [
    _alert(
        alert_id="alert-001",
        book_id="fx-main",
        severity="HIGH",
        status="TRIGGERED",
        triggered_at="2026-05-19T07:30:00Z",
    ),
    _alert(
        alert_id="alert-002",
        book_id="fx-main",
        severity="CRITICAL",
        status="ESCALATED",
        triggered_at="2026-05-19T09:15:00Z",
    ),
    _alert(
        alert_id="alert-003",
        book_id="fx-main",
        severity="MEDIUM",
        status="ACKNOWLEDGED",
        triggered_at="2026-05-19T08:00:00Z",
    ),
    _alert(
        alert_id="alert-004",
        book_id="fx-main",
        severity="LOW",
        status="RESOLVED",
        triggered_at="2026-05-18T10:00:00Z",
    ),
    _alert(
        alert_id="alert-005",
        book_id="rates-emea",
        severity="HIGH",
        status="TRIGGERED",
        triggered_at="2026-05-19T06:00:00Z",
    ),
    _alert(
        alert_id="alert-006",
        book_id="fx-main",
        severity="LOW",
        status="TRIGGERED",
        triggered_at="2026-05-18T22:00:00Z",
    ),
]


# ---------------------------------------------------------------------------
# Endpoint and parameter wiring
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_active_alerts_calls_correct_endpoint() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _ALERTS_PATH, [])

    await get_active_alerts(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert len(fake.recorded_calls) == 1
    call = fake.recorded_calls[0]
    assert call.method == "GET"
    assert call.service == _SERVICE
    assert call.path == _ALERTS_PATH
    assert call.params == {"limit": 200}
    assert call.user == _DEFAULT_USER


# ---------------------------------------------------------------------------
# Client-side filtering
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_active_alerts_filters_to_book_id() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _ALERTS_PATH, _MIXED_ALERTS)

    result = await get_active_alerts(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    for row in result["alerts"]:
        # rates-emea alert (alert-005) must not appear.
        assert row["id"] != "alert-005"
    # Sanity: book filter must not strip everything.
    assert len(result["alerts"]) >= 1


@pytest.mark.asyncio
async def test_get_active_alerts_excludes_resolved() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _ALERTS_PATH, _MIXED_ALERTS)

    result = await get_active_alerts(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    ids = [row["id"] for row in result["alerts"]]
    assert "alert-004" not in ids
    for row in result["alerts"]:
        assert row["status"] != "RESOLVED"


@pytest.mark.asyncio
async def test_get_active_alerts_includes_triggered_acknowledged_escalated() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _ALERTS_PATH, _MIXED_ALERTS)

    result = await get_active_alerts(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    statuses = {row["status"] for row in result["alerts"]}
    assert "TRIGGERED" in statuses
    assert "ACKNOWLEDGED" in statuses
    assert "ESCALATED" in statuses


@pytest.mark.asyncio
async def test_get_active_alerts_filters_by_severity() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _ALERTS_PATH, _MIXED_ALERTS)

    result = await get_active_alerts(
        book_id="fx-main",
        severity="CRITICAL",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert len(result["alerts"]) == 1
    assert result["alerts"][0]["severity"] == "CRITICAL"
    assert result["alerts"][0]["id"] == "alert-002"


@pytest.mark.asyncio
async def test_get_active_alerts_filters_by_since() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _ALERTS_PATH, _MIXED_ALERTS)

    result = await get_active_alerts(
        book_id="fx-main",
        since="2026-05-19",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    ids = {row["id"] for row in result["alerts"]}
    # alert-006 triggered on 2026-05-18 must be excluded.
    assert "alert-006" not in ids
    # alerts on 2026-05-19 remain.
    assert "alert-001" in ids
    assert "alert-002" in ids
    assert "alert-003" in ids


@pytest.mark.asyncio
async def test_get_active_alerts_sorts_by_triggered_at_desc() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _ALERTS_PATH, _MIXED_ALERTS)

    result = await get_active_alerts(
        book_id="fx-main",
        since="2026-05-19",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    ids = [row["id"] for row in result["alerts"]]
    # Expected desc by triggeredAt: 09:15 (002), 08:00 (003), 07:30 (001).
    assert ids == ["alert-002", "alert-003", "alert-001"]


# ---------------------------------------------------------------------------
# Counts and mapping
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_active_alerts_total_fetched_and_active_count() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _ALERTS_PATH, _MIXED_ALERTS)

    result = await get_active_alerts(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["total_fetched"] == len(_MIXED_ALERTS)
    assert result["active_count"] == len(result["alerts"])


@pytest.mark.asyncio
async def test_get_active_alerts_maps_response_fields() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE,
        _ALERTS_PATH,
        [
            _alert(
                alert_id="alert-001",
                book_id="fx-main",
                severity="HIGH",
                status="TRIGGERED",
                triggered_at="2026-05-19T07:30:00Z",
                rule_id="rule-var-breach",
                rule_name="VaR > 5% NAV",
                alert_type="VAR_LIMIT",
                message="VaR breach on FX book",
                current_value=6.2,
                threshold=5.0,
                correlation_id="corr-xyz",
                suggested_action="Reduce EURUSD exposure",
            )
        ],
    )

    result = await get_active_alerts(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["book_id"] == "fx-main"
    assert result["alerts"] == [
        {
            "id": "alert-001",
            "rule_id": "rule-var-breach",
            "rule_name": "VaR > 5% NAV",
            "type": "VAR_LIMIT",
            "severity": "HIGH",
            "message": "VaR breach on FX book",
            "current_value": 6.2,
            "threshold": 5.0,
            "triggered_at": "2026-05-19T07:30:00Z",
            "status": "TRIGGERED",
            "suggested_action": "Reduce EURUSD exposure",
            "correlation_id": "corr-xyz",
        }
    ]


# ---------------------------------------------------------------------------
# Citation population
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_active_alerts_citation_records_call_params() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _ALERTS_PATH, _MIXED_ALERTS)

    result = await get_active_alerts(
        book_id="fx-main",
        severity="HIGH",
        since="2026-05-19",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["citation"].params == {
        "book_id": "fx-main",
        "severity": "HIGH",
        "since": "2026-05-19",
    }


@pytest.mark.asyncio
async def test_get_active_alerts_citation_result_value_is_active_count() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _ALERTS_PATH, _MIXED_ALERTS)

    result = await get_active_alerts(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    citation = result["citation"]
    assert citation.tool == "get_active_alerts"
    assert citation.result_field == "active_count"
    assert citation.result_value == float(result["active_count"])
    assert citation.result_currency == "USD"
    assert citation.data_source == "notification-service"
    assert citation.as_of_timestamp == _fixed_now()
    assert citation.freshness_seconds == 0


@pytest.mark.asyncio
async def test_get_active_alerts_citation_always_flags_cross_book_not_supported() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _ALERTS_PATH, _MIXED_ALERTS)

    result = await get_active_alerts(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert "CROSS_BOOK_NOT_SUPPORTED" in result["citation"].quality_flags


@pytest.mark.asyncio
async def test_get_active_alerts_citation_adds_acknowledged_flag_when_present() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _ALERTS_PATH, _MIXED_ALERTS)

    result = await get_active_alerts(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert "INCLUDES_ACKNOWLEDGED" in result["citation"].quality_flags


@pytest.mark.asyncio
async def test_get_active_alerts_citation_adds_escalated_flag_when_present() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _ALERTS_PATH, _MIXED_ALERTS)

    result = await get_active_alerts(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert "INCLUDES_ESCALATED" in result["citation"].quality_flags


@pytest.mark.asyncio
async def test_get_active_alerts_citation_does_not_add_acknowledged_flag_when_none_present() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE,
        _ALERTS_PATH,
        [
            _alert(
                alert_id="alert-only-triggered",
                book_id="fx-main",
                severity="HIGH",
                status="TRIGGERED",
                triggered_at="2026-05-19T07:30:00Z",
            )
        ],
    )

    result = await get_active_alerts(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert "INCLUDES_ACKNOWLEDGED" not in result["citation"].quality_flags


# ---------------------------------------------------------------------------
# Input validation (must run BEFORE any HTTP call)
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_active_alerts_invalid_severity_raises_bad_request_before_http() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _ALERTS_PATH, _MIXED_ALERTS)

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_active_alerts(
            book_id="fx-main",
            severity="ULTRA",
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "BAD_REQUEST"
    assert excinfo.value.status_code == 400
    assert fake.recorded_calls == []


@pytest.mark.asyncio
async def test_get_active_alerts_invalid_since_raises_bad_request_before_http() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _ALERTS_PATH, _MIXED_ALERTS)

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_active_alerts(
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
async def test_get_active_alerts_fails_closed_when_book_not_in_user_scope() -> None:
    fake = FakeKinetixHttpClient()
    user = UserContext(user_id="t1", books=("rates-only",))

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_active_alerts(
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
async def test_get_active_alerts_propagates_not_found() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE,
        _ALERTS_PATH,
        KinetixHttpError(
            status_code=404,
            code="NOT_FOUND",
            message="route misrouted",
            service=_SERVICE,
            path=_ALERTS_PATH,
        ),
    )

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_active_alerts(
            book_id="fx-main",
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "NOT_FOUND"
    assert excinfo.value.status_code == 404


@pytest.mark.asyncio
async def test_get_active_alerts_propagates_upstream_error() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE,
        _ALERTS_PATH,
        KinetixHttpError(
            status_code=502,
            code="UPSTREAM_ERROR",
            message="notification-service unreachable",
            service=_SERVICE,
            path=_ALERTS_PATH,
        ),
    )

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_active_alerts(
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
async def test_get_active_alerts_handles_empty_upstream() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _ALERTS_PATH, [])

    result = await get_active_alerts(
        book_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["alerts"] == []
    assert result["total_fetched"] == 0
    assert result["active_count"] == 0
    assert result["citation"].result_value == 0.0


@pytest.mark.asyncio
async def test_get_active_alerts_raises_upstream_error_on_non_list_payload() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE,
        _ALERTS_PATH,
        {"unexpected": "object"},
    )

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_active_alerts(
            book_id="fx-main",
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "UPSTREAM_ERROR"
    assert excinfo.value.status_code == 502

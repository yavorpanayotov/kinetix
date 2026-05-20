"""Unit tests for the intraday threshold evaluator (plan ai-v2.md § 7.2).

These tests cover two collaborating pieces:

* the :func:`get_alert_thresholds` MCP tool — which reads the
  ``copilot_alert_thresholds`` config table via the conventional
  risk-orchestrator endpoint and projects rows onto the v2 tool-output
  shape — and
* the :class:`IntradayThresholdEvaluator` — which compares a parsed
  ``risk.results`` Kafka event against the fetched thresholds and emits
  :class:`IntradayAlert`s, with per-(book, alert_type) cooldown dedupe.

Both are exercised entirely against
:class:`tests.fakes.fake_kinetix_http_client.FakeKinetixHttpClient`
with an injected, advanceable ``now`` — no network, no Kafka, no
FastMCP wiring.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Any

import pytest

from kinetix_insights.clients.kinetix_http_client import KinetixHttpError
from kinetix_insights.clients.user_context import UserContext
from kinetix_insights.mcp.tools.get_alert_thresholds import get_alert_thresholds
from kinetix_insights.push.threshold_evaluator import (
    IntradayAlert,
    IntradayThresholdEvaluator,
)
from tests.fakes.fake_kinetix_http_client import FakeKinetixHttpClient

pytestmark = pytest.mark.unit


# ---------------------------------------------------------------------------
# Fixture constants
# ---------------------------------------------------------------------------


_DEFAULT_USER = UserContext(user_id="trader-1", books=("fx-main", "rates-emea"))

_SERVICE = "risk-orchestrator"
_THRESHOLDS_PATH = "/api/v1/risk/copilot-alert-thresholds"


def _fixed_now() -> datetime:
    return datetime(2026, 5, 20, 9, 0, 0, tzinfo=timezone.utc)


class _Clock:
    """Advanceable clock — mirrors the injected-``now`` idiom used by the
    conversation store and tool tests."""

    def __init__(self, start: datetime) -> None:
        self._current = start

    def __call__(self) -> datetime:
        return self._current

    def advance(self, delta: timedelta) -> None:
        self._current += delta


def _threshold(
    *,
    threshold_id: str = "thr-1",
    scope_type: str = "GLOBAL",
    scope_id: str | None = None,
    alert_type: str = "VAR_BREACH",
    threshold_value: str = "5000000.0",
    cooldown_minutes: int = 30,
) -> dict[str, Any]:
    """Build a representative upstream ``copilot_alert_thresholds`` row."""

    return {
        "id": threshold_id,
        "scopeType": scope_type,
        "scopeId": scope_id,
        "alertType": alert_type,
        "thresholdValue": threshold_value,
        "cooldownMinutes": cooldown_minutes,
    }


def _risk_event(
    *,
    book_id: str = "fx-main",
    var_value: str = "6200000.00",
    expected_shortfall: str = "7100000.00",
    aggregate_delta: str | None = None,
    margin_utilisation: float | None = None,
) -> dict[str, Any]:
    """Build a representative parsed ``risk.results`` Kafka event."""

    event: dict[str, Any] = {
        "bookId": book_id,
        "varValue": var_value,
        "expectedShortfall": expected_shortfall,
        "calculationType": "PARAMETRIC",
        "calculatedAt": "2026-05-20T09:00:00Z",
        "confidenceLevel": "CL_95",
        "componentBreakdown": [],
        "correlationId": "corr-1",
    }
    if aggregate_delta is not None:
        event["aggregateDelta"] = aggregate_delta
    if margin_utilisation is not None:
        event["marginUtilisation"] = margin_utilisation
    return event


def _make_evaluator(
    fake: FakeKinetixHttpClient,
    now: Any,
) -> IntradayThresholdEvaluator:
    return IntradayThresholdEvaluator(
        http=fake,
        user=_DEFAULT_USER,
        now=now,
    )


# ---------------------------------------------------------------------------
# 1. No thresholds → no alerts
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_no_thresholds_yields_no_alerts() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _THRESHOLDS_PATH, [])
    evaluator = _make_evaluator(fake, _fixed_now)

    alerts = await evaluator.evaluate(_risk_event(var_value="6200000.00"))

    assert alerts == []


# ---------------------------------------------------------------------------
# 2-3. VAR_BREACH
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_var_breach_fires_when_var_exceeds_threshold() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE,
        _THRESHOLDS_PATH,
        [
            _threshold(
                alert_type="VAR_BREACH",
                threshold_value="5000000.0",
            )
        ],
    )
    evaluator = _make_evaluator(fake, _fixed_now)

    alerts = await evaluator.evaluate(_risk_event(var_value="6200000.00"))

    assert len(alerts) == 1
    alert = alerts[0]
    assert isinstance(alert, IntradayAlert)
    assert alert.alert_type == "VAR_BREACH"
    assert alert.book_id == "fx-main"
    assert alert.current == 6200000.00
    assert alert.threshold == 5000000.0


@pytest.mark.asyncio
async def test_var_breach_does_not_fire_when_within_threshold() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE,
        _THRESHOLDS_PATH,
        [_threshold(alert_type="VAR_BREACH", threshold_value="9000000.0")],
    )
    evaluator = _make_evaluator(fake, _fixed_now)

    alerts = await evaluator.evaluate(_risk_event(var_value="6200000.00"))

    assert alerts == []


# ---------------------------------------------------------------------------
# 4-5. POSITION_DELTA
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_position_delta_fires_on_abs_delta() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE,
        _THRESHOLDS_PATH,
        [
            _threshold(
                alert_type="POSITION_DELTA",
                threshold_value="500000.0",
            )
        ],
    )
    evaluator = _make_evaluator(fake, _fixed_now)

    # Negative delta — the evaluator compares the absolute value.
    alerts = await evaluator.evaluate(
        _risk_event(aggregate_delta="-600000")
    )

    assert len(alerts) == 1
    assert alerts[0].alert_type == "POSITION_DELTA"
    assert alerts[0].current == 600000.0
    assert alerts[0].threshold == 500000.0


@pytest.mark.asyncio
async def test_position_delta_skipped_when_aggregate_delta_absent() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE,
        _THRESHOLDS_PATH,
        [_threshold(alert_type="POSITION_DELTA", threshold_value="500000.0")],
    )
    evaluator = _make_evaluator(fake, _fixed_now)

    # Event carries no aggregateDelta — the threshold contributes nothing.
    alerts = await evaluator.evaluate(_risk_event(aggregate_delta=None))

    assert alerts == []


# ---------------------------------------------------------------------------
# 6. LIMIT_UTILISATION
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_limit_utilisation_fires_on_margin_utilisation() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE,
        _THRESHOLDS_PATH,
        [
            _threshold(
                alert_type="LIMIT_UTILISATION",
                threshold_value="80.0",
            )
        ],
    )
    evaluator = _make_evaluator(fake, _fixed_now)

    # marginUtilisation 0.83 → 83% utilisation → above the 80 threshold.
    alerts = await evaluator.evaluate(
        _risk_event(margin_utilisation=0.83)
    )

    assert len(alerts) == 1
    assert alerts[0].alert_type == "LIMIT_UTILISATION"
    assert alerts[0].current == pytest.approx(83.0)
    assert alerts[0].threshold == 80.0


# ---------------------------------------------------------------------------
# 7. Unknown alert type
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_unknown_alert_type_is_skipped() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE,
        _THRESHOLDS_PATH,
        [
            _threshold(
                alert_type="REGIME_CHANGE",
                threshold_value="1.0",
            )
        ],
    )
    evaluator = _make_evaluator(fake, _fixed_now)

    # REGIME_CHANGE is not computable from a risk.results event; it must
    # be silently skipped without crashing.
    alerts = await evaluator.evaluate(_risk_event())

    assert alerts == []


# ---------------------------------------------------------------------------
# 8. Severity
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_severity_is_critical_above_1_5x() -> None:
    fake = FakeKinetixHttpClient()
    # Threshold 4_000_000; varValue 6_200_000 == 1.55x → critical.
    fake.register_response(
        "GET",
        _SERVICE,
        _THRESHOLDS_PATH,
        [_threshold(alert_type="VAR_BREACH", threshold_value="4000000.0")],
    )
    evaluator = _make_evaluator(fake, _fixed_now)

    alerts = await evaluator.evaluate(_risk_event(var_value="6200000.00"))

    assert len(alerts) == 1
    assert alerts[0].severity == "critical"


@pytest.mark.asyncio
async def test_severity_is_warning_just_over_threshold() -> None:
    fake = FakeKinetixHttpClient()
    # Threshold 6_000_000; varValue 6_200_000 == ~1.03x → warning.
    fake.register_response(
        "GET",
        _SERVICE,
        _THRESHOLDS_PATH,
        [_threshold(alert_type="VAR_BREACH", threshold_value="6000000.0")],
    )
    evaluator = _make_evaluator(fake, _fixed_now)

    alerts = await evaluator.evaluate(_risk_event(var_value="6200000.00"))

    assert len(alerts) == 1
    assert alerts[0].severity == "warning"


# ---------------------------------------------------------------------------
# 9-11. Cooldown dedupe
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_cooldown_suppresses_repeat_alert() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE,
        _THRESHOLDS_PATH,
        [
            _threshold(
                alert_type="VAR_BREACH",
                threshold_value="5000000.0",
                cooldown_minutes=30,
            )
        ],
    )
    clock = _Clock(_fixed_now())
    evaluator = _make_evaluator(fake, clock)
    event = _risk_event(var_value="6200000.00")

    first = await evaluator.evaluate(event)
    assert len(first) == 1

    # Re-evaluating within the cooldown window — suppressed.
    second = await evaluator.evaluate(event)
    assert second == []


@pytest.mark.asyncio
async def test_cooldown_expires_after_cooldown_minutes() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE,
        _THRESHOLDS_PATH,
        [
            _threshold(
                alert_type="VAR_BREACH",
                threshold_value="5000000.0",
                cooldown_minutes=30,
            )
        ],
    )
    clock = _Clock(_fixed_now())
    evaluator = _make_evaluator(fake, clock)
    event = _risk_event(var_value="6200000.00")

    first = await evaluator.evaluate(event)
    assert len(first) == 1

    # Advance past the 30-minute cooldown — the alert fires again.
    clock.advance(timedelta(minutes=31))
    third = await evaluator.evaluate(event)
    assert len(third) == 1
    assert third[0].alert_type == "VAR_BREACH"


@pytest.mark.asyncio
async def test_cooldown_is_per_book_and_per_alert_type() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE,
        _THRESHOLDS_PATH,
        [
            _threshold(
                threshold_id="thr-var",
                alert_type="VAR_BREACH",
                threshold_value="5000000.0",
            ),
            _threshold(
                threshold_id="thr-delta",
                alert_type="POSITION_DELTA",
                threshold_value="500000.0",
            ),
        ],
    )
    clock = _Clock(_fixed_now())
    evaluator = _make_evaluator(fake, clock)

    # Two different books breach VAR_BREACH — independent cooldown keys,
    # so both fire even though the alert type is the same.
    book_a = await evaluator.evaluate(
        _risk_event(book_id="fx-main", var_value="6200000.00")
    )
    book_b = await evaluator.evaluate(
        _risk_event(book_id="rates-emea", var_value="6200000.00")
    )
    assert len(book_a) == 1
    assert len(book_b) == 1
    assert book_a[0].book_id == "fx-main"
    assert book_b[0].book_id == "rates-emea"

    # The same book breaching a different alert type also fires — the
    # cooldown key is (book, alert_type), so VAR_BREACH's cooldown does
    # not suppress POSITION_DELTA.
    delta = await evaluator.evaluate(
        _risk_event(
            book_id="fx-main",
            var_value="100.00",
            aggregate_delta="600000",
        )
    )
    assert len(delta) == 1
    assert delta[0].alert_type == "POSITION_DELTA"


# ---------------------------------------------------------------------------
# 12. Cooldown key format
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_cooldown_key_format() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE,
        _THRESHOLDS_PATH,
        [_threshold(alert_type="VAR_BREACH", threshold_value="5000000.0")],
    )
    evaluator = _make_evaluator(fake, _fixed_now)

    alerts = await evaluator.evaluate(
        _risk_event(book_id="fx-main", var_value="6200000.00")
    )

    assert len(alerts) == 1
    assert alerts[0].cooldown_key == "fx-main:VAR_BREACH"


# ---------------------------------------------------------------------------
# 13. Multiple thresholds firing together
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_multiple_thresholds_fire_together() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE,
        _THRESHOLDS_PATH,
        [
            _threshold(
                threshold_id="thr-var",
                alert_type="VAR_BREACH",
                threshold_value="5000000.0",
            ),
            _threshold(
                threshold_id="thr-delta",
                alert_type="POSITION_DELTA",
                threshold_value="500000.0",
            ),
        ],
    )
    evaluator = _make_evaluator(fake, _fixed_now)

    alerts = await evaluator.evaluate(
        _risk_event(
            var_value="6200000.00",
            aggregate_delta="600000",
        )
    )

    assert len(alerts) == 2
    by_type = {alert.alert_type for alert in alerts}
    assert by_type == {"VAR_BREACH", "POSITION_DELTA"}


# ---------------------------------------------------------------------------
# 14. get_alert_thresholds — invalid scope
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_invalid_scope_raises_bad_request() -> None:
    fake = FakeKinetixHttpClient()

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_alert_thresholds(
            scope="BOGUS",
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "BAD_REQUEST"
    assert excinfo.value.status_code == 400
    # Validation must run before any HTTP call.
    assert fake.recorded_calls == []


# ---------------------------------------------------------------------------
# 15. Upstream error propagation
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_upstream_error_propagates() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE,
        _THRESHOLDS_PATH,
        KinetixHttpError(
            status_code=502,
            code="UPSTREAM_ERROR",
            message="risk-orchestrator unreachable",
            service=_SERVICE,
            path=_THRESHOLDS_PATH,
        ),
    )
    evaluator = _make_evaluator(fake, _fixed_now)

    # The evaluator does not swallow upstream errors — the Kafka consumer
    # in § 7.3 decides retry.
    with pytest.raises(KinetixHttpError) as excinfo:
        await evaluator.evaluate(_risk_event(var_value="6200000.00"))

    assert excinfo.value.code == "UPSTREAM_ERROR"
    assert excinfo.value.status_code == 502


# ---------------------------------------------------------------------------
# get_alert_thresholds — tool-level coverage
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_alert_thresholds_calls_endpoint_with_scope_param() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _THRESHOLDS_PATH, [])

    await get_alert_thresholds(
        scope="GLOBAL",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert len(fake.recorded_calls) == 1
    call = fake.recorded_calls[0]
    assert call.method == "GET"
    assert call.service == _SERVICE
    assert call.path == _THRESHOLDS_PATH
    assert call.params == {"scope": "GLOBAL"}
    assert call.user == _DEFAULT_USER


@pytest.mark.asyncio
async def test_get_alert_thresholds_sends_scope_id_when_present() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE, _THRESHOLDS_PATH, [])

    await get_alert_thresholds(
        scope="BOOK",
        scope_id="fx-main",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    call = fake.recorded_calls[0]
    assert call.params == {"scope": "BOOK", "scopeId": "fx-main"}


@pytest.mark.asyncio
async def test_get_alert_thresholds_maps_rows_and_citation() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE,
        _THRESHOLDS_PATH,
        [
            _threshold(
                threshold_id="thr-var",
                alert_type="VAR_BREACH",
                threshold_value="5.0",
                cooldown_minutes=30,
            ),
            _threshold(
                threshold_id="thr-delta",
                alert_type="POSITION_DELTA",
                threshold_value="500000.0",
                cooldown_minutes=15,
            ),
        ],
    )

    result = await get_alert_thresholds(
        scope="GLOBAL",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["scope"] == "GLOBAL"
    assert result["scope_id"] is None
    assert result["count"] == 2

    by_id = {row["id"]: row for row in result["thresholds"]}
    var_row = by_id["thr-var"]
    assert var_row["scope_type"] == "GLOBAL"
    assert var_row["scope_id"] is None
    assert var_row["alert_type"] == "VAR_BREACH"
    assert var_row["threshold_value"] == 5.0
    assert isinstance(var_row["threshold_value"], float)
    assert var_row["cooldown_minutes"] == 30

    delta_row = by_id["thr-delta"]
    assert delta_row["threshold_value"] == 500000.0
    assert delta_row["cooldown_minutes"] == 15

    citation = result["citation"]
    assert citation.tool == "get_alert_thresholds"
    assert citation.result_field == "threshold_count"
    assert citation.result_value == 2.0
    assert citation.result_currency is None
    assert citation.data_source == "risk-orchestrator"
    assert citation.as_of_timestamp == _fixed_now()


@pytest.mark.asyncio
async def test_get_alert_thresholds_rejects_non_list_payload() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE,
        _THRESHOLDS_PATH,
        {"unexpected": "object"},
    )

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_alert_thresholds(
            scope="GLOBAL",
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "UPSTREAM_ERROR"
    assert excinfo.value.status_code == 502

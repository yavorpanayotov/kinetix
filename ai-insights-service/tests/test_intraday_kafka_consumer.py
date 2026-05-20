"""Unit tests for the intraday-push Kafka consumer (plan ai-v2.md § 7.3).

The :class:`~kinetix_insights.push.kafka_consumer.IntradayKafkaConsumer`
subscribes to ``risk.results`` and ``risk.regime.changes`` under the
consumer group ``ai-insights-risk-consumer``, JSON-decodes each message,
runs it through an :class:`IntradayThresholdEvaluator`, and hands every
firing :class:`IntradayAlert` to a :class:`PushGenerator`.

These tests inject a :class:`_FakeKafkaConsumer` so they never touch a
real broker. The fake mimics ``aiokafka.AIOKafkaConsumer``: it is an
async-iterable yielding objects with ``.value`` (bytes) and ``.topic``,
and records that ``start()``/``stop()`` were called.

Most cases use the REAL :class:`IntradayThresholdEvaluator` wired to a
:class:`FakeKinetixHttpClient`, so the consumer→evaluator→push path is
exercised end-to-end; a couple use a small stub evaluator where a
controllable ``evaluate`` return (or raise) is what the case needs.

Draining: after ``start()`` the consume loop runs as a background task
stored on ``consumer._task``. Tests await that task (via the
``wait_drained`` helper) so post-drain assertions are deterministic —
the fake consumer's async iteration ends once its scripted messages are
exhausted, which ends the loop.
"""

from __future__ import annotations

import asyncio
import json
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

import pytest

from kinetix_insights.clients.kinetix_http_client import KinetixHttpError
from kinetix_insights.clients.user_context import UserContext
from kinetix_insights.push.kafka_consumer import (
    CONSUMER_GROUP,
    REGIME_CHANGES_TOPIC,
    RISK_RESULTS_TOPIC,
    IntradayKafkaConsumer,
    PushGenerator,
)
from kinetix_insights.push.threshold_evaluator import (
    IntradayAlert,
    IntradayThresholdEvaluator,
)
from tests.fakes.fake_kinetix_http_client import FakeKinetixHttpClient

pytestmark = pytest.mark.unit


# ---------------------------------------------------------------------------
# Fixture constants
# ---------------------------------------------------------------------------


_DEFAULT_USER = UserContext(user_id="trader-1", books=("fx-main",))

_SERVICE = "risk-orchestrator"
_THRESHOLDS_PATH = "/api/v1/risk/copilot-alert-thresholds"


def _fixed_now() -> datetime:
    return datetime(2026, 5, 20, 9, 0, 0, tzinfo=timezone.utc)


def _threshold(
    *,
    threshold_id: str = "thr-1",
    alert_type: str = "VAR_BREACH",
    threshold_value: str = "5000000.0",
    cooldown_minutes: int = 30,
) -> dict[str, Any]:
    """Build a representative upstream ``copilot_alert_thresholds`` row."""

    return {
        "id": threshold_id,
        "scopeType": "GLOBAL",
        "scopeId": None,
        "alertType": alert_type,
        "thresholdValue": threshold_value,
        "cooldownMinutes": cooldown_minutes,
    }


def _risk_event(
    *,
    book_id: str = "fx-main",
    var_value: str = "6200000.00",
) -> dict[str, Any]:
    """Build a representative parsed ``risk.results`` Kafka event."""

    return {
        "bookId": book_id,
        "varValue": var_value,
        "expectedShortfall": "7100000.00",
        "calculationType": "PARAMETRIC",
        "calculatedAt": "2026-05-20T09:00:00Z",
        "confidenceLevel": "CL_95",
        "componentBreakdown": [],
        "correlationId": "corr-1",
    }


def _regime_event() -> dict[str, Any]:
    """Build a representative ``risk.regime.changes`` event (different shape)."""

    return {
        "regimeId": "regime-7",
        "previousRegime": "LOW_VOL",
        "newRegime": "HIGH_VOL",
        "detectedAt": "2026-05-20T09:00:00Z",
    }


# ---------------------------------------------------------------------------
# Test doubles
# ---------------------------------------------------------------------------


@dataclass
class _FakeMessage:
    """Mimics an ``aiokafka`` consumer record: ``.value`` bytes + ``.topic``."""

    value: bytes
    topic: str


def _risk_message(event: dict[str, Any]) -> _FakeMessage:
    return _FakeMessage(
        value=json.dumps(event).encode("utf-8"), topic=RISK_RESULTS_TOPIC
    )


class _FakeKafkaConsumer:
    """In-memory stand-in for ``aiokafka.AIOKafkaConsumer``.

    Async-iterates a scripted list of :class:`_FakeMessage`, then raises
    ``StopAsyncIteration`` so the consume loop terminates cleanly. A
    short ``await asyncio.sleep(0)`` per message yields the event loop so
    ``stop()`` can interrupt iteration mid-stream.
    """

    def __init__(self, messages: list[_FakeMessage]) -> None:
        self._messages = list(messages)
        self._index = 0
        self.start_called = False
        self.stop_called = False
        self._stopped = False

    async def start(self) -> None:
        self.start_called = True

    async def stop(self) -> None:
        self.stop_called = True
        self._stopped = True

    def __aiter__(self) -> _FakeKafkaConsumer:
        return self

    async def __anext__(self) -> _FakeMessage:
        # Yield control so a concurrent stop() can take effect.
        await asyncio.sleep(0)
        if self._stopped or self._index >= len(self._messages):
            raise StopAsyncIteration
        message = self._messages[self._index]
        self._index += 1
        return message


class _BlockingKafkaConsumer:
    """Fake consumer that blocks forever — used to test ``stop()`` cancels.

    Its async iteration never yields a message; it parks on an
    ``asyncio.Event`` that is only set by ``stop()``.
    """

    def __init__(self) -> None:
        self.start_called = False
        self.stop_called = False
        self._released = asyncio.Event()

    async def start(self) -> None:
        self.start_called = True

    async def stop(self) -> None:
        self.stop_called = True
        self._released.set()

    def __aiter__(self) -> _BlockingKafkaConsumer:
        return self

    async def __anext__(self) -> _FakeMessage:
        await self._released.wait()
        raise StopAsyncIteration


class _RecordingPushGenerator:
    """:class:`PushGenerator` double — appends every alert it receives."""

    def __init__(self) -> None:
        self.alerts: list[IntradayAlert] = []

    async def handle_alert(self, alert: IntradayAlert) -> None:
        self.alerts.append(alert)


class _FlakyPushGenerator:
    """Push generator that raises on the first alert, then records the rest."""

    def __init__(self) -> None:
        self.alerts: list[IntradayAlert] = []
        self._calls = 0

    async def handle_alert(self, alert: IntradayAlert) -> None:
        self._calls += 1
        if self._calls == 1:
            raise RuntimeError("gateway dispatch failed")
        self.alerts.append(alert)


class _StubEvaluator:
    """Evaluator double with a scripted per-call ``evaluate`` outcome.

    Each entry in ``outcomes`` is either a ``list[IntradayAlert]`` to
    return or an ``Exception`` instance to raise, consumed in order.
    """

    def __init__(self, outcomes: list[Any]) -> None:
        self._outcomes = list(outcomes)
        self._index = 0

    async def evaluate(self, event: dict[str, Any]) -> list[IntradayAlert]:
        outcome = self._outcomes[self._index]
        self._index += 1
        if isinstance(outcome, Exception):
            raise outcome
        return outcome


def _alert(book_id: str = "fx-main") -> IntradayAlert:
    return IntradayAlert(
        alert_type="VAR_BREACH",
        severity="critical",
        book_id=book_id,
        current=6_200_000.0,
        threshold=4_000_000.0,
        cooldown_key=f"{book_id}:VAR_BREACH",
    )


def _real_evaluator(
    threshold_value: str = "5000000.0",
) -> tuple[IntradayThresholdEvaluator, FakeKinetixHttpClient]:
    """Build a REAL evaluator backed by a fake HTTP client."""

    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE,
        _THRESHOLDS_PATH,
        [_threshold(alert_type="VAR_BREACH", threshold_value=threshold_value)],
    )
    evaluator = IntradayThresholdEvaluator(
        http=fake, user=_DEFAULT_USER, now=_fixed_now
    )
    return evaluator, fake


async def _wait_drained(consumer: IntradayKafkaConsumer) -> None:
    """Await the consume task so post-drain assertions are deterministic."""

    task = consumer._task
    assert task is not None
    await asyncio.wait_for(task, timeout=2.0)


# ---------------------------------------------------------------------------
# 1. start() starts the underlying consumer
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_start_starts_the_underlying_consumer() -> None:
    evaluator, _ = _real_evaluator()
    fake_consumer = _FakeKafkaConsumer([])
    consumer = IntradayKafkaConsumer(
        evaluator=evaluator,
        push_generator=_RecordingPushGenerator(),
        consumer=fake_consumer,
    )

    await consumer.start()
    try:
        assert fake_consumer.start_called is True
    finally:
        await consumer.stop()


# ---------------------------------------------------------------------------
# 2. A breaching risk.results message dispatches an alert
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_consumes_a_risk_results_message_and_dispatches_an_alert() -> None:
    evaluator, _ = _real_evaluator(threshold_value="5000000.0")
    push = _RecordingPushGenerator()
    fake_consumer = _FakeKafkaConsumer(
        [_risk_message(_risk_event(var_value="6200000.00"))]
    )
    consumer = IntradayKafkaConsumer(
        evaluator=evaluator, push_generator=push, consumer=fake_consumer
    )

    await consumer.start()
    await _wait_drained(consumer)
    await consumer.stop()

    assert len(push.alerts) == 1
    assert push.alerts[0].alert_type == "VAR_BREACH"
    assert push.alerts[0].book_id == "fx-main"


# ---------------------------------------------------------------------------
# 3. A within-threshold message dispatches nothing
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_message_yielding_no_alert_dispatches_nothing() -> None:
    evaluator, _ = _real_evaluator(threshold_value="9000000.0")
    push = _RecordingPushGenerator()
    fake_consumer = _FakeKafkaConsumer(
        [_risk_message(_risk_event(var_value="6200000.00"))]
    )
    consumer = IntradayKafkaConsumer(
        evaluator=evaluator, push_generator=push, consumer=fake_consumer
    )

    await consumer.start()
    await _wait_drained(consumer)
    await consumer.stop()

    assert push.alerts == []


# ---------------------------------------------------------------------------
# 4. regime-change messages yield no alert in v2
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_regime_change_message_yields_no_alert_in_v2() -> None:
    # The v2 evaluator only computes alerts from risk.results-shaped
    # events; a regime-change event yields no alerts and must not crash
    # the loop. The real evaluator parses event["bookId"] — a regime
    # event has none — so a stub returning [] models the documented v2
    # behaviour without coupling to the evaluator's parse internals.
    push = _RecordingPushGenerator()
    fake_consumer = _FakeKafkaConsumer(
        [
            _FakeMessage(
                value=json.dumps(_regime_event()).encode("utf-8"),
                topic=REGIME_CHANGES_TOPIC,
            )
        ]
    )
    consumer = IntradayKafkaConsumer(
        evaluator=_StubEvaluator([[]]),
        push_generator=push,
        consumer=fake_consumer,
    )

    await consumer.start()
    await _wait_drained(consumer)
    await consumer.stop()

    assert push.alerts == []


# ---------------------------------------------------------------------------
# 5. malformed JSON is skipped, not fatal
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_malformed_json_message_is_skipped_not_fatal() -> None:
    evaluator, _ = _real_evaluator(threshold_value="5000000.0")
    push = _RecordingPushGenerator()
    fake_consumer = _FakeKafkaConsumer(
        [
            _FakeMessage(value=b"not json{", topic=RISK_RESULTS_TOPIC),
            _risk_message(_risk_event(var_value="6200000.00")),
        ]
    )
    consumer = IntradayKafkaConsumer(
        evaluator=evaluator, push_generator=push, consumer=fake_consumer
    )

    await consumer.start()
    await _wait_drained(consumer)
    await consumer.stop()

    # The poison message is skipped; the valid one still fires its alert.
    assert len(push.alerts) == 1
    assert push.alerts[0].alert_type == "VAR_BREACH"


# ---------------------------------------------------------------------------
# 6. an evaluator error does not kill the loop
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_evaluator_error_does_not_kill_the_loop() -> None:
    push = _RecordingPushGenerator()
    fake_consumer = _FakeKafkaConsumer(
        [
            _risk_message(_risk_event(book_id="fx-main")),
            _risk_message(_risk_event(book_id="rates-emea")),
        ]
    )
    # First message: thresholds endpoint down → KinetixHttpError.
    # Second message: succeeds with one alert.
    stub = _StubEvaluator(
        [
            KinetixHttpError(
                status_code=502,
                code="UPSTREAM_ERROR",
                message="risk-orchestrator unreachable",
                service=_SERVICE,
                path=_THRESHOLDS_PATH,
            ),
            [_alert(book_id="rates-emea")],
        ]
    )
    consumer = IntradayKafkaConsumer(
        evaluator=stub, push_generator=push, consumer=fake_consumer
    )

    await consumer.start()
    await _wait_drained(consumer)
    await consumer.stop()

    # The loop survived the first message's error and dispatched the
    # second message's alert.
    assert len(push.alerts) == 1
    assert push.alerts[0].book_id == "rates-emea"


# ---------------------------------------------------------------------------
# 7. a push-generator error does not drop remaining alerts
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_push_generator_error_does_not_drop_remaining_alerts() -> None:
    push = _FlakyPushGenerator()
    fake_consumer = _FakeKafkaConsumer(
        [
            _risk_message(_risk_event(book_id="fx-main")),
            _risk_message(_risk_event(book_id="rates-emea")),
        ]
    )
    # Each message yields one alert; handle_alert raises for the first.
    stub = _StubEvaluator(
        [[_alert(book_id="fx-main")], [_alert(book_id="rates-emea")]]
    )
    consumer = IntradayKafkaConsumer(
        evaluator=stub, push_generator=push, consumer=fake_consumer
    )

    await consumer.start()
    await _wait_drained(consumer)
    await consumer.stop()

    # The first alert's dispatch failed; the second still went through.
    assert len(push.alerts) == 1
    assert push.alerts[0].book_id == "rates-emea"


# ---------------------------------------------------------------------------
# 8. stop() cancels the loop and stops the consumer
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_stop_cancels_the_loop_and_stops_the_consumer() -> None:
    evaluator, _ = _real_evaluator()
    blocking_consumer = _BlockingKafkaConsumer()
    consumer = IntradayKafkaConsumer(
        evaluator=evaluator,
        push_generator=_RecordingPushGenerator(),
        consumer=blocking_consumer,
    )

    await consumer.start()
    task = consumer._task
    assert task is not None
    assert not task.done()

    await consumer.stop()

    assert task.done()
    assert blocking_consumer.stop_called is True


# ---------------------------------------------------------------------------
# 9. stop() is idempotent
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_stop_is_idempotent() -> None:
    evaluator, _ = _real_evaluator()

    # stop() before start() must not raise.
    never_started = IntradayKafkaConsumer(
        evaluator=evaluator,
        push_generator=_RecordingPushGenerator(),
        consumer=_FakeKafkaConsumer([]),
    )
    await never_started.stop()

    # stop() twice must not raise.
    consumer = IntradayKafkaConsumer(
        evaluator=evaluator,
        push_generator=_RecordingPushGenerator(),
        consumer=_FakeKafkaConsumer([]),
    )
    await consumer.start()
    await consumer.stop()
    await consumer.stop()


# ---------------------------------------------------------------------------
# 10. module constants — topics and consumer group
# ---------------------------------------------------------------------------


def test_subscribes_to_both_topics_and_correct_group() -> None:
    assert RISK_RESULTS_TOPIC == "risk.results"
    assert REGIME_CHANGES_TOPIC == "risk.regime.changes"
    assert CONSUMER_GROUP == "ai-insights-risk-consumer"


# ---------------------------------------------------------------------------
# 11. PushGenerator Protocol is runtime-checkable
# ---------------------------------------------------------------------------


def test_push_generator_protocol() -> None:
    assert isinstance(_RecordingPushGenerator(), PushGenerator)


# ---------------------------------------------------------------------------
# 12. end-to-end with the real evaluator
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_end_to_end_with_real_evaluator() -> None:
    # Real IntradayThresholdEvaluator + FakeKinetixHttpClient stubbing
    # get_alert_thresholds. Threshold 4_000_000; varValue 6_200_000 ==
    # 1.55x → a critical VAR_BREACH alert.
    evaluator, fake_http = _real_evaluator(threshold_value="4000000.0")
    push = _RecordingPushGenerator()
    fake_consumer = _FakeKafkaConsumer(
        [_risk_message(_risk_event(book_id="fx-main", var_value="6200000.00"))]
    )
    consumer = IntradayKafkaConsumer(
        evaluator=evaluator, push_generator=push, consumer=fake_consumer
    )

    await consumer.start()
    await _wait_drained(consumer)
    await consumer.stop()

    assert len(push.alerts) == 1
    alert = push.alerts[0]
    assert alert.alert_type == "VAR_BREACH"
    assert alert.severity == "critical"
    assert alert.book_id == "fx-main"
    assert alert.current == 6_200_000.0
    assert alert.threshold == 4_000_000.0
    assert alert.cooldown_key == "fx-main:VAR_BREACH"
    # The evaluator really did call the thresholds endpoint.
    assert any(
        call.path == _THRESHOLDS_PATH for call in fake_http.recorded_calls
    )

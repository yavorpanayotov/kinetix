"""aiokafka consumer for the intraday-push pipeline (plan ai-v2.md § 7.3).

Subscribes to ``risk.results`` and ``risk.regime.changes`` under the
consumer group ``ai-insights-risk-consumer``. Each message is JSON-
decoded and passed to :class:`~kinetix_insights.push.threshold_evaluator.
IntradayThresholdEvaluator`; every firing :class:`IntradayAlert` is
handed to a :class:`PushGenerator`.

The underlying ``aiokafka.AIOKafkaConsumer`` is injected — tests pass a
fake async-iterable so they never require a Kafka broker. The real
consumer is lazily constructed (and ``aiokafka`` lazily imported) only
when none is supplied, mirroring the lazy-import idiom of
:class:`~kinetix_insights.claude_agent_client.ClaudeAgentInsightClient`.

v2 scope — regime-change events yield no alerts
-----------------------------------------------
The checkbox requires SUBSCRIBING to both topics, and the consumer does.
But the v2 :class:`IntradayThresholdEvaluator` only computes alerts from
``risk.results``-shaped events (it reads ``event["bookId"]`` and the
risk measures a ``RiskResultEvent`` carries). A ``risk.regime.changes``
event has a different shape and the evaluator's ``REGIME_CHANGE`` alert
type is "not computable from a risk.results event" — so in v2 a
regime-change message produces no alerts. The subscription is kept so a
future evaluator iteration can act on regime changes without re-wiring
the consumer; today such a message simply yields ``[]`` (or is skipped
as a shape error, logged but never fatal).

Resilience
----------
One bad message must never kill the consume loop:

* JSON decode errors / shape errors → logged, message skipped.
* ``KinetixHttpError`` from the evaluator (thresholds endpoint down) →
  logged, message skipped; the next message retries.
* An exception from ``PushGenerator.handle_alert`` → logged; the
  remaining alerts for that message are still dispatched.

Only :class:`asyncio.CancelledError` ends the loop — that is how
:meth:`IntradayKafkaConsumer.stop` shuts it down cleanly.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import logging
import time
from datetime import datetime, timezone
from typing import Any, Protocol, runtime_checkable

from kinetix_insights.audit.audit_logger import AuditLogger
from kinetix_insights.audit.audit_record import AuditRecord
from kinetix_insights.clients.kinetix_http_client import KinetixHttpError
from kinetix_insights.push.threshold_evaluator import (
    IntradayAlert,
    IntradayThresholdEvaluator,
)

logger = logging.getLogger("kinetix_insights.push")

# user_id stamped on a push audit line when no caller identity is wired —
# the intraday pipeline is system-driven, not a per-request user call.
_SYSTEM_USER_ID = "system-intraday"

# Topics the consumer subscribes to and the (new, pre-approved) group.
RISK_RESULTS_TOPIC: str = "risk.results"
REGIME_CHANGES_TOPIC: str = "risk.regime.changes"
CONSUMER_GROUP: str = "ai-insights-risk-consumer"


@runtime_checkable
class PushGenerator(Protocol):
    """Consumes a firing :class:`IntradayAlert`.

    Satisfied by ``IntradayPushGenerator`` in checkbox 7.4. Modelled as a
    ``@runtime_checkable`` ``Protocol`` so 7.3 can wire and test the
    consumer against any object exposing ``handle_alert`` — including the
    no-op placeholder used until 7.4 lands.
    """

    async def handle_alert(
        self, alert: IntradayAlert
    ) -> None: ...  # pragma: no cover - structural only


def _build_aiokafka_consumer(bootstrap_servers: str) -> Any:
    """Lazily import ``aiokafka`` and build a real consumer.

    Isolated so the import only happens on the production path — unit
    tests always inject a fake and never reach this. A broker being
    unreachable surfaces when ``start()`` is awaited on the returned
    object, not here; an import failure surfaces as a clear
    ``RuntimeError``.
    """

    try:
        from aiokafka import AIOKafkaConsumer  # type: ignore[import-not-found]
    except Exception as exc:  # pragma: no cover - exercised only in prod
        raise RuntimeError(f"aiokafka import failed: {exc}") from exc

    return AIOKafkaConsumer(
        RISK_RESULTS_TOPIC,
        REGIME_CHANGES_TOPIC,
        bootstrap_servers=bootstrap_servers,
        group_id=CONSUMER_GROUP,
        enable_auto_commit=True,
        auto_offset_reset="latest",
    )


class IntradayKafkaConsumer:
    """Consumes ``risk.results`` / ``risk.regime.changes`` for the push pipeline.

    Each message flows decode → :meth:`IntradayThresholdEvaluator.evaluate`
    → :meth:`PushGenerator.handle_alert`. The consume loop runs as a
    background task; :meth:`stop` cancels it and stops the underlying
    consumer, idempotently.
    """

    def __init__(
        self,
        *,
        evaluator: IntradayThresholdEvaluator,
        push_generator: PushGenerator,
        consumer: Any | None = None,
        bootstrap_servers: str = "localhost:9092",
        user_id: str = _SYSTEM_USER_ID,
        audit_logger: AuditLogger | None = None,
    ) -> None:
        """Construct the consumer.

        Args:
            evaluator: The :class:`IntradayThresholdEvaluator` (or a
                compatible stub) each decoded event is evaluated against.
            push_generator: Collaborator that consumes every firing
                :class:`IntradayAlert`.
            consumer: An injected ``aiokafka``-shaped consumer (an
                async-iterable of records exposing ``.value``/``.topic``
                with ``start()``/``stop()`` coroutines). When ``None``,
                a real ``AIOKafkaConsumer`` is built lazily on
                :meth:`start` — tests always inject a fake.
            bootstrap_servers: Kafka bootstrap servers for the lazily
                built real consumer. Ignored when ``consumer`` is given.
            user_id: Identity stamped on each push audit line (checkbox
                10.3). Defaults to ``"system-intraday"`` — the intraday
                push pipeline is system-driven, not a per-request user
                call.
            audit_logger: Injectable :class:`AuditLogger` for tests;
                production uses a fresh default.
        """

        self._evaluator = evaluator
        self._push_generator = push_generator
        self._consumer = consumer
        self._bootstrap_servers = bootstrap_servers
        self._task: asyncio.Task[None] | None = None
        self._started = False
        self._user_id = user_id
        self._audit_logger = audit_logger or AuditLogger()

    async def start(self) -> None:
        """Build (if needed) + start the consumer, then run the consume loop.

        The consume loop runs as a background :class:`asyncio.Task` stored
        on ``self._task`` so :meth:`stop` can cancel it. Calling
        ``start`` more than once is a no-op after the first.
        """

        if self._started:
            return
        if self._consumer is None:
            self._consumer = _build_aiokafka_consumer(self._bootstrap_servers)
        await self._consumer.start()
        self._started = True
        self._task = asyncio.create_task(self._consume_loop())

    async def stop(self) -> None:
        """Cancel the consume loop and stop the consumer — idempotent.

        Safe to call twice, or before :meth:`start`: a missing task or an
        unstarted consumer is simply skipped. The cancelled task is
        awaited (swallowing :class:`asyncio.CancelledError`) so it never
        leaks into another event loop.
        """

        if self._task is not None:
            self._task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._task
            self._task = None
        if self._consumer is not None and self._started:
            await self._consumer.stop()
            self._started = False

    async def _consume_loop(self) -> None:
        """Async-iterate the consumer, dispatching alerts per message.

        One poison message (bad JSON, an evaluator error, a failing
        ``handle_alert``) is logged and skipped — it never breaks the
        loop. The loop ends when the consumer's async iteration ends
        (the broker/fake stops yielding) or when :meth:`stop` cancels the
        task.
        """

        assert self._consumer is not None  # set by start()
        async for message in self._consumer:
            await self._handle_message(message)

    async def _handle_message(self, message: Any) -> None:
        """Decode, evaluate, and dispatch a single Kafka record.

        Every failure mode is contained: a bad message is logged and
        dropped, never re-raised, so the consume loop survives.
        """

        topic = getattr(message, "topic", "<unknown>")
        try:
            event = json.loads(message.value)
        except (json.JSONDecodeError, TypeError, ValueError) as exc:
            logger.warning(
                "skipping malformed message on topic %s: %s", topic, exc
            )
            return
        if not isinstance(event, dict):
            logger.warning(
                "skipping non-object message on topic %s: got %s",
                topic,
                type(event).__name__,
            )
            return

        try:
            alerts = await self._evaluator.evaluate(event)
        except KinetixHttpError as exc:
            # Thresholds endpoint down — log and move on; the next
            # message retries. Crashing the consumer would be worse.
            logger.warning(
                "threshold evaluation failed for a message on topic %s: %s",
                topic,
                exc,
            )
            return
        except Exception:  # noqa: BLE001 - one bad message must not kill the loop
            logger.exception(
                "unexpected error evaluating a message on topic %s", topic
            )
            return

        for alert in alerts:
            started = time.monotonic()
            try:
                await self._push_generator.handle_alert(alert)
            except Exception:  # noqa: BLE001 - one alert's failure must not drop the rest
                logger.exception(
                    "push generator failed to handle alert %s for book %s",
                    alert.alert_type,
                    alert.book_id,
                )
            self._emit_push_audit(alert, started=started)

    def _emit_push_audit(self, alert: IntradayAlert, *, started: float) -> None:
        """Emit exactly one structured audit log line for a push call.

        A push has no free-form prompt — the audit trail hashes the
        ``alert_type:book_id`` marker so repeated alerts for the same
        book correlate. ``tool_calls`` records the two sources the
        intraday evaluation reads (the threshold-config tool and the
        ``risk.results`` event); ``mode`` is ``"push"`` to mark the
        system-driven origin.
        """

        latency_ms = (time.monotonic() - started) * 1000.0
        marker = f"{alert.alert_type}:{alert.book_id}"
        self._audit_logger.emit(
            AuditRecord(
                user_id=self._user_id,
                endpoint="push",
                prompt_hash=AuditRecord.hash_prompt(marker),
                tool_calls=["get_alert_thresholds", "risk.results"],
                tokens_estimated=0,
                mode="push",
                latency_ms=latency_ms,
                timestamp=datetime.now(timezone.utc),
            )
        )

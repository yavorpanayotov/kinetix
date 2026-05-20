"""The live :class:`IntradayPushGenerator` — checkbox 7.4.

When the :class:`~kinetix_insights.push.threshold_evaluator.
IntradayThresholdEvaluator` decides an intraday threshold has breached,
it emits an :class:`~kinetix_insights.push.threshold_evaluator.
IntradayAlert`. :class:`IntradayPushGenerator` turns that alert into a
fully-composed :class:`~kinetix_insights.push.models.IntradayPush` — the
structured payload the gateway internal endpoint receives in checkbox
7.7 and the UI renders as an alert toast.

It satisfies the :class:`~kinetix_insights.push.kafka_consumer.
PushGenerator` protocol, so the :class:`~kinetix_insights.push.
kafka_consumer.IntradayKafkaConsumer` can drive it directly.

Provenance: ``sources``
-----------------------
The plan requires ``sources`` to "include the original tool calls used
to evaluate the threshold". An :class:`IntradayAlert` is the *result* of
that evaluation; the evaluation itself read two things — the
threshold-config table (via the ``get_alert_thresholds`` MCP tool) and
the breaching ``risk.results`` Kafka event. The generator therefore
reconstructs one :class:`~kinetix_insights.citations.models.Citation`
per source so the UI can render the same inline provenance it shows for
chat and the morning brief:

* a ``get_alert_thresholds`` citation pinning the breached
  ``threshold_value``, and
* a ``risk.results`` citation pinning the breaching ``current`` measure.

Both carry the ``EVALUATED_FROM_ALERT`` quality flag — the alert object
does not itself carry per-tool ``as_of`` timestamps, so the citations
are anchored to the composition clock and flagged accordingly. Closing
that gap (threading the evaluator's own ``get_alert_thresholds``
citation through the alert) is a documented v2 follow-up.

The gateway POST is checkbox 7.7
--------------------------------
7.4 only *composes* the payload. :meth:`handle_alert` composes the push
and records it on :attr:`composed_pushes`; if an optional ``sink`` is
injected it also forwards the push to that sink. Checkbox 7.7's
``GatewayPushClient`` plugs into the ``sink`` seam — an injected async
callable — so this generator stays decoupled from the HTTP transport.
"""

from __future__ import annotations

import uuid
from collections.abc import Awaitable, Callable
from datetime import datetime, timezone

from kinetix_insights.citations.models import Citation
from kinetix_insights.push.models import IntradayPush
from kinetix_insights.push.threshold_evaluator import IntradayAlert

# Tool / source identifiers for the two provenance citations.
_THRESHOLD_TOOL: str = "get_alert_thresholds"
_RISK_RESULTS_SOURCE: str = "risk.results"

# Stamped on every push citation: the citation is reconstructed from the
# IntradayAlert (which carries no per-tool as_of timestamps) rather than
# captured at the original tool call. See the module docstring.
_EVALUATED_FROM_ALERT_FLAG: str = "EVALUATED_FROM_ALERT"

# Human-readable measure labels per alert type, for headline/bullet prose.
_MEASURE_LABELS: dict[str, str] = {
    "VAR_BREACH": "VaR",
    "POSITION_DELTA": "aggregate delta",
    "LIMIT_UTILISATION": "limit utilisation",
}

# Sink that forwards a composed push downstream (e.g. to the gateway).
PushSink = Callable[[IntradayPush], Awaitable[None]]


def _default_now() -> datetime:
    """Return the current UTC time. Indirected so tests can inject a fake."""

    return datetime.now(timezone.utc)


def _measure_label(alert_type: str) -> str:
    """Return a human-readable measure name for ``alert_type``."""

    return _MEASURE_LABELS.get(alert_type, alert_type.replace("_", " ").lower())


class IntradayPushGenerator:
    """Composes an :class:`IntradayPush` from a firing :class:`IntradayAlert`.

    Satisfies the :class:`~kinetix_insights.push.kafka_consumer.
    PushGenerator` protocol. Construction is cheap and side-effect free —
    no SDK call, no network. Each :meth:`compose` call assembles a fresh
    push with its own UUID ``session_id``.
    """

    def __init__(
        self,
        *,
        now: Callable[[], datetime] | None = None,
        sink: PushSink | None = None,
    ) -> None:
        """Construct the generator.

        Args:
            now: Injectable clock stamping ``generated_at`` and the
                provenance citations. Defaults to
                ``datetime.now(timezone.utc)``.
            sink: Optional async callable the composed push is forwarded
                to from :meth:`handle_alert`. Checkbox 7.7's
                ``GatewayPushClient`` plugs in here; when ``None`` the
                push is only recorded on :attr:`composed_pushes`.
        """

        self._now = now or _default_now
        self._sink = sink
        # Every push composed by handle_alert, newest last — lets 7.7's
        # wiring and the unit tests observe what would be dispatched.
        self.composed_pushes: list[IntradayPush] = []

    async def compose(self, alert: IntradayAlert) -> IntradayPush:
        """Compose — but do not dispatch — an :class:`IntradayPush`.

        Builds the headline, context bullets, and the provenance
        ``sources`` trail, and stamps a fresh ``session_id`` and
        ``generated_at``. Pure: no I/O, no mutation of generator state.
        """

        generated_at = self._now()
        measure = _measure_label(alert.alert_type)
        over_pct = _percent_over(alert.current, alert.threshold)

        headline = (
            f"{alert.severity.capitalize()} {alert.alert_type} on "
            f"{alert.book_id}: {measure} {alert.current:,.0f} exceeds "
            f"limit {alert.threshold:,.0f}"
        )
        context_bullets = [
            f"Current {measure}: {alert.current:,.0f}",
            f"Threshold: {alert.threshold:,.0f}",
            f"Breach magnitude: {over_pct:.0f}% over limit",
        ]
        sources = _build_sources(alert, as_of=generated_at)

        return IntradayPush(
            alert_type=alert.alert_type,
            severity=alert.severity,
            book_id=alert.book_id,
            headline=headline,
            context_bullets=context_bullets,
            sources=sources,
            session_id=str(uuid.uuid4()),
            generated_at=generated_at,
        )

    async def handle_alert(self, alert: IntradayAlert) -> None:
        """Compose the push, record it, and forward it to the sink (if any).

        Satisfies the :class:`~kinetix_insights.push.kafka_consumer.
        PushGenerator` protocol. The composed push is appended to
        :attr:`composed_pushes`; when a ``sink`` was injected it is also
        awaited with the push. The gateway POST itself is checkbox 7.7.
        """

        push = await self.compose(alert)
        self.composed_pushes.append(push)
        if self._sink is not None:
            await self._sink(push)


def _percent_over(current: float, threshold: float) -> float:
    """Return how far ``current`` exceeds ``threshold``, as a percentage.

    Guards against a zero threshold (no meaningful percentage) by
    returning ``0.0`` — the absolute figures still appear in the
    bullets, so no information is lost.
    """

    if threshold == 0:
        return 0.0
    return (current - threshold) / threshold * 100.0


def _build_sources(alert: IntradayAlert, *, as_of: datetime) -> list[Citation]:
    """Reconstruct the provenance citations for the threshold evaluation.

    Two tool calls fed the evaluation: the ``get_alert_thresholds``
    config read (the ``threshold`` value) and the ``risk.results`` Kafka
    event (the ``current`` measure). See the module docstring for why
    both carry the ``EVALUATED_FROM_ALERT`` flag.
    """

    threshold_citation = Citation(
        tool=_THRESHOLD_TOOL,
        params={"scope": "GLOBAL", "alert_type": alert.alert_type},
        result_field="threshold_value",
        result_value=alert.threshold,
        result_currency=None,
        as_of_timestamp=as_of,
        data_source="risk-orchestrator",
        freshness_seconds=0,
        quality_flags=[_EVALUATED_FROM_ALERT_FLAG],
    )
    risk_results_citation = Citation(
        tool=_RISK_RESULTS_SOURCE,
        params={"book_id": alert.book_id, "alert_type": alert.alert_type},
        result_field=_RESULT_FIELDS.get(alert.alert_type, "current"),
        result_value=alert.current,
        result_currency=None,
        as_of_timestamp=as_of,
        data_source="risk-orchestrator",
        freshness_seconds=0,
        quality_flags=[_EVALUATED_FROM_ALERT_FLAG],
    )
    return [threshold_citation, risk_results_citation]


# The risk.results event field each alert type's measure was read from.
_RESULT_FIELDS: dict[str, str] = {
    "VAR_BREACH": "varValue",
    "POSITION_DELTA": "aggregateDelta",
    "LIMIT_UTILISATION": "marginUtilisation",
}

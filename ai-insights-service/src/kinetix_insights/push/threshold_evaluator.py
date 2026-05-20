"""Intraday threshold evaluation for the push-alerts pipeline (§ 7.2).

The :class:`IntradayThresholdEvaluator` is the decision core of the v2
intraday push-alerts pipeline. It takes a parsed ``risk.results`` Kafka
event (the ``RiskResultEvent`` schema) and the set of configured copilot
alert thresholds, and decides which alerts should fire *right now*.

It guards against alert spam with a per-(book, alert_type) cooldown: a
sustained breach produces one alert, then stays quiet for the
threshold's ``cooldown_minutes`` before it can re-fire. Cooldown state is
held in :class:`_CooldownCache`, a small hand-rolled TTL dict that
lazily expires entries on access using an injectable ``now`` — the same
timestamp-eviction idiom as
:class:`~kinetix_insights.chat.conversation_store.InMemoryConversationStore`.
We deliberately do NOT pull in ``cachetools`` for this: the cooldown
cache is a dozen lines and adding a dependency is not warranted.

Computable subset (v2 scope)
----------------------------
A ``risk.results`` event only carries a handful of risk measures, so the
evaluator can only evaluate the threshold ``alert_type``s for which the
event carries data. The v2 computable subset is:

* ``VAR_BREACH`` — ``current = varValue``. The threshold is treated as
  an **absolute VaR ceiling**: the alert fires when ``varValue`` exceeds
  ``threshold_value``. NOTE: ``copilot_alert_thresholds`` models the
  VaR threshold as a percentage of NAV ("5.0" meaning 5%), but a
  ``risk.results`` event does not carry NAV, so proper %-of-NAV
  evaluation is a documented v2 follow-up. Treating the threshold as an
  absolute ceiling is the simplest dimensionally-correct v2 behaviour.

* ``POSITION_DELTA`` — ``current = abs(aggregateDelta)`` when the event
  carries ``aggregateDelta``. Fires when ``current`` exceeds
  ``threshold_value``. Skipped silently when the event omits the field.

* ``LIMIT_UTILISATION`` — ``current = marginUtilisation * 100`` (the
  event carries a 0-1 ratio; the threshold is a percentage) when the
  event carries ``marginUtilisation``. Fires when ``current`` exceeds
  ``threshold_value``.

Any other ``alert_type`` from the threshold list (e.g. ``REGIME_CHANGE``,
``CORRELATION_SPIKE``) is **skipped silently** — those alert types fire
from other event types and are out of scope for this evaluator in v2.

Severity, error handling
------------------------
* A threshold "fires" when ``current > threshold_value``.
* ``severity`` is ``"critical"`` when ``current >= 1.5 * threshold_value``,
  else ``"warning"``.
* ``cooldown_key`` is ``f"{book_id}:{alert_type}"``.
* The evaluator does NOT swallow upstream errors. If
  :func:`~kinetix_insights.mcp.tools.get_alert_thresholds.
  get_alert_thresholds` raises a ``KinetixHttpError``, it propagates —
  the Kafka consumer in § 7.3 owns the retry decision.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any

from kinetix_insights.clients.kinetix_http_client import KinetixHttpClient
from kinetix_insights.clients.user_context import UserContext
from kinetix_insights.mcp.tools.get_alert_thresholds import get_alert_thresholds

# Severity boundary: a breach at or above 1.5x the threshold is critical.
_CRITICAL_MULTIPLIER: float = 1.5

_WARNING: str = "warning"
_CRITICAL: str = "critical"

# Alert types the evaluator knows how to compute from a risk.results
# event (the v2 computable subset — see module docstring).
_VAR_BREACH: str = "VAR_BREACH"
_POSITION_DELTA: str = "POSITION_DELTA"
_LIMIT_UTILISATION: str = "LIMIT_UTILISATION"
_COMPUTABLE_ALERT_TYPES: frozenset[str] = frozenset(
    {_VAR_BREACH, _POSITION_DELTA, _LIMIT_UTILISATION}
)


def _default_now() -> datetime:
    """Return the current UTC time. Indirected so tests can inject a fake."""

    return datetime.now(timezone.utc)


@dataclass(frozen=True)
class IntradayAlert:
    """One intraday alert the evaluator decided should fire.

    Attributes:
        alert_type: The threshold's alert type (e.g. ``"VAR_BREACH"``).
        severity: ``"warning"`` or ``"critical"`` — ``"critical"`` when
            ``current >= 1.5 * threshold``.
        book_id: Book the breaching ``risk.results`` event belongs to.
        current: The measure computed from the event (already scaled,
            absolute-valued, etc. per the alert type).
        threshold: The configured ``threshold_value`` that was breached.
        cooldown_key: ``f"{book_id}:{alert_type}"`` — the key under which
            this alert's cooldown is tracked.
    """

    alert_type: str
    severity: str
    book_id: str
    current: float
    threshold: float
    cooldown_key: str


class _CooldownCache:
    """Process-local TTL set of cooldown keys with lazy expiry.

    Mirrors the timestamp-eviction idiom of
    :class:`~kinetix_insights.chat.conversation_store.
    InMemoryConversationStore`: each key carries an explicit expiry
    timestamp and entries are expired lazily on access using the
    injected ``now``. No background sweeper, no external dependency.

    Different alert types carry different cooldown windows, so the TTL is
    supplied per :meth:`record` call rather than fixed at construction.
    """

    def __init__(self, *, now: Callable[[], datetime]) -> None:
        self._now = now
        # cooldown_key -> wall-clock time at which the cooldown expires.
        self._expires_at: dict[str, datetime] = {}

    def is_cooling_down(self, key: str) -> bool:
        """Return ``True`` if ``key`` has a live (unexpired) cooldown.

        Expired entries are dropped on access so the dict does not grow
        without bound. Uses strict ``>``: an entry whose expiry is
        exactly ``now`` is considered expired (the cooldown window has
        elapsed).
        """

        expiry = self._expires_at.get(key)
        if expiry is None:
            return False
        if expiry > self._now():
            return True
        # Cooldown elapsed — drop the stale entry.
        del self._expires_at[key]
        return False

    def record(self, key: str, ttl: timedelta) -> None:
        """Start (or restart) a cooldown for ``key`` lasting ``ttl``."""

        self._expires_at[key] = self._now() + ttl


class IntradayThresholdEvaluator:
    """Evaluates a ``risk.results`` event against alert thresholds.

    Emits :class:`IntradayAlert`s for every threshold the event breaches,
    with per-(book, alert_type) cooldown dedupe so a sustained breach
    does not re-fire every tick. The cooldown cache is process-local and
    shared across all :meth:`evaluate` calls on this instance.
    """

    def __init__(
        self,
        *,
        http: KinetixHttpClient,
        user: UserContext,
        now: Callable[[], datetime] | None = None,
    ) -> None:
        """Construct the evaluator.

        Args:
            http: ``KinetixHttpClient`` used to fetch thresholds via
                :func:`get_alert_thresholds`.
            user: Caller identity forwarded to the threshold tool for
                header stamping. Thresholds are config, not book-scoped
                data, so no book-ACL check is performed.
            now: Injectable clock driving both the threshold citation
                timestamp and the cooldown cache TTL arithmetic.
                Defaults to ``datetime.now(timezone.utc)``.
        """

        self._http = http
        self._user = user
        self._now = now or _default_now
        self._cooldown = _CooldownCache(now=self._now)

    async def evaluate(self, event: dict[str, Any]) -> list[IntradayAlert]:
        """Return the alerts the event should fire NOW (cooldown-filtered).

        Fetches the GLOBAL alert thresholds, computes the relevant
        measure from ``event`` for every threshold in the computable
        subset, and emits an :class:`IntradayAlert` for each threshold
        breached. Alerts whose ``cooldown_key`` is still cooling down are
        suppressed; every emitted alert (re)starts its cooldown for the
        threshold's ``cooldown_minutes``.

        Args:
            event: A parsed ``risk.results`` Kafka event
                (``RiskResultEvent`` JSON shape).

        Returns:
            The list of :class:`IntradayAlert`s that passed cooldown.
            ``[]`` when nothing fires.

        Raises:
            KinetixHttpError: Propagated unmodified from
                :func:`get_alert_thresholds` — the evaluator does not
                swallow upstream errors.
        """

        thresholds_result = await get_alert_thresholds(
            scope="GLOBAL",
            user=self._user,
            http=self._http,
            now=self._now,
        )
        thresholds: list[dict[str, Any]] = thresholds_result["thresholds"]

        book_id = str(event["bookId"])
        alerts: list[IntradayAlert] = []

        for threshold in thresholds:
            alert_type = threshold["alert_type"]
            if alert_type not in _COMPUTABLE_ALERT_TYPES:
                # Not computable from a risk.results event — skip silently.
                continue

            current = _compute_current(alert_type, event)
            if current is None:
                # Event omits the field this alert type needs.
                continue

            threshold_value = float(threshold["threshold_value"])
            if current <= threshold_value:
                # Within threshold — no breach.
                continue

            cooldown_key = f"{book_id}:{alert_type}"
            if self._cooldown.is_cooling_down(cooldown_key):
                # Suppressed: a recent alert is still cooling down.
                continue

            severity = (
                _CRITICAL
                if current >= _CRITICAL_MULTIPLIER * threshold_value
                else _WARNING
            )
            alerts.append(
                IntradayAlert(
                    alert_type=alert_type,
                    severity=severity,
                    book_id=book_id,
                    current=current,
                    threshold=threshold_value,
                    cooldown_key=cooldown_key,
                )
            )
            self._cooldown.record(
                cooldown_key,
                timedelta(minutes=int(threshold["cooldown_minutes"])),
            )

        return alerts


def _compute_current(alert_type: str, event: dict[str, Any]) -> float | None:
    """Compute the comparison measure for ``alert_type`` from ``event``.

    Returns ``None`` when the event does not carry the data this alert
    type needs (e.g. a ``POSITION_DELTA`` threshold against an event with
    no ``aggregateDelta``), in which case the threshold contributes no
    alert.
    """

    if alert_type == _VAR_BREACH:
        return float(event["varValue"])

    if alert_type == _POSITION_DELTA:
        raw_delta = event.get("aggregateDelta")
        if raw_delta is None:
            return None
        return abs(float(raw_delta))

    if alert_type == _LIMIT_UTILISATION:
        margin = event.get("marginUtilisation")
        if margin is None:
            return None
        return float(margin) * 100.0

    # Unreachable for the computable subset, but keep the function total.
    return None

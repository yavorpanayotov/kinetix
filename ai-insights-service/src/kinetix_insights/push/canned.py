"""The canned :class:`CannedIntradayPushGenerator` — checkbox 7.4.

This generator backs the intraday-push pipeline in ``DEMO_MODE``. Per
the plan's "Demo mode" decision — *every new endpoint has a
``Canned*Client`` that emits the same shape from a fixture file* — it
loads ``fixtures/demo_intraday_push.json`` once at construction and
replays it as a fully-formed :class:`~kinetix_insights.push.models.
IntradayPush` on every alert. It satisfies the
:class:`~kinetix_insights.push.kafka_consumer.PushGenerator` protocol,
exactly like the live :class:`~kinetix_insights.push.push_generator.
IntradayPushGenerator`.

Design notes (mirrors :class:`~kinetix_insights.brief.canned.
CannedBriefClient` and :class:`~kinetix_insights.chat.canned.
CannedCopilotChatClient`):

* **Eager loading, fail fast.** The fixture is parsed once at
  construction. A missing or malformed file surfaces immediately as a
  clear error rather than poisoning a later alert.
* **Deterministic content, fresh correlation id.** The headline,
  bullets, and ``sources`` come verbatim from the fixture so the demo
  replays frame-for-frame. ``session_id`` is still a fresh UUID per
  push and ``generated_at`` is the live clock reading — those are
  per-push correlation values, not demo content, so two canned pushes
  never share a ``session_id``.
* **Alert is ignored.** The ``IntradayAlert`` argument is accepted to
  satisfy the protocol but intentionally unused — the canned fixture is
  fixed demo data, so every alert yields the same deterministic push.
"""

from __future__ import annotations

import json
import uuid
from collections.abc import Callable
from datetime import datetime, timezone
from pathlib import Path

from kinetix_insights.push.models import IntradayPush
from kinetix_insights.push.threshold_evaluator import IntradayAlert

_DEFAULT_FIXTURE_PATH = (
    Path(__file__).resolve().parent.parent / "fixtures" / "demo_intraday_push.json"
)


def _default_now() -> datetime:
    """Return the current UTC time. Indirected so tests can inject a fake."""

    return datetime.now(timezone.utc)


def _load_fixture(fixture_path: Path) -> dict[str, object]:
    """Parse ``fixture_path`` into the raw demo-push payload.

    Validates it as an :class:`IntradayPush` once at load time so a
    malformed fixture fails fast at construction. The ``session_id`` and
    ``generated_at`` from the fixture are placeholders — they are
    overwritten per push — so the file may carry any well-formed values.
    """

    raw = json.loads(fixture_path.read_text())
    if not isinstance(raw, dict):
        raise ValueError(
            f"demo intraday-push fixture {fixture_path!s} must be a JSON "
            f"object, got {type(raw).__name__}"
        )
    # Fail fast: the body (headline/bullets/sources) must be a valid push
    # once the per-push fields are supplied.
    IntradayPush.model_validate(
        {
            **raw,
            "session_id": "fixture-placeholder",
            "generated_at": "2026-05-20T09:00:00Z",
        }
    )
    return raw


class CannedIntradayPushGenerator:
    """Fixture-driven :class:`~kinetix_insights.push.kafka_consumer.PushGenerator`.

    Loads ``fixtures/demo_intraday_push.json`` eagerly at construction
    and returns it — with a fresh ``session_id`` and ``generated_at`` —
    on every :meth:`compose` / :meth:`handle_alert` call. The
    ``IntradayAlert`` argument is accepted for protocol compatibility
    but ignored: the fixture is deterministic demo data.
    """

    def __init__(
        self,
        *,
        fixture_path: Path | None = None,
        now: Callable[[], datetime] | None = None,
    ) -> None:
        """Construct the canned generator.

        Args:
            fixture_path: Override for the demo-push fixture. Defaults to
                ``fixtures/demo_intraday_push.json``.
            now: Injectable clock stamping ``generated_at``. Defaults to
                ``datetime.now(timezone.utc)``.
        """

        path = fixture_path if fixture_path is not None else _DEFAULT_FIXTURE_PATH
        self._fixture = _load_fixture(path)
        self._now = now or _default_now
        # Every push composed by handle_alert, newest last — mirrors the
        # live generator so 7.7's wiring and tests observe both modes
        # identically.
        self.composed_pushes: list[IntradayPush] = []

    async def compose(self, alert: IntradayAlert) -> IntradayPush:
        """Return the canned :class:`IntradayPush` for ``alert``.

        ``alert`` is accepted to satisfy the
        :class:`~kinetix_insights.push.kafka_consumer.PushGenerator`
        contract but intentionally unused — the fixture is fixed demo
        data. A fresh ``session_id`` and the live ``generated_at`` are
        stamped so two canned pushes never share a correlation id.
        """

        del alert  # the canned fixture is deterministic; alert is ignored
        return IntradayPush.model_validate(
            {
                **self._fixture,
                "session_id": str(uuid.uuid4()),
                "generated_at": self._now(),
            }
        )

    async def handle_alert(self, alert: IntradayAlert) -> None:
        """Compose the canned push and record it on :attr:`composed_pushes`.

        Satisfies the :class:`~kinetix_insights.push.kafka_consumer.
        PushGenerator` protocol. The canned generator has no downstream
        sink — the gateway POST is the live path's concern (checkbox
        7.7); in demo mode recording the push is sufficient.
        """

        push = await self.compose(alert)
        self.composed_pushes.append(push)

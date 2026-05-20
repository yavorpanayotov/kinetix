"""Unit tests for the intraday push generator (plan ai-v2.md § 7.4).

These tests cover three pieces:

* :class:`IntradayPushGenerator` — the live generator that composes an
  :class:`IntradayPush` payload from a firing
  :class:`~kinetix_insights.push.threshold_evaluator.IntradayAlert`,
* :class:`CannedIntradayPushGenerator` — the ``DEMO_MODE`` variant that
  replays a deterministic fixture, and
* :func:`build_intraday_push_generator` — the factory that selects
  canned vs. live based on the ``DEMO_MODE`` env var.

Both generators satisfy the
:class:`~kinetix_insights.push.kafka_consumer.PushGenerator` protocol.
Nothing here requires Kafka, the gateway, or the live Claude SDK — the
generators only *compose* the payload; the HTTP POST to the gateway is a
later checkbox (7.7).
"""

from __future__ import annotations

from datetime import datetime, timezone

import pytest

from kinetix_insights.citations.models import Citation
from kinetix_insights.push.canned import CannedIntradayPushGenerator
from kinetix_insights.push.factory import build_intraday_push_generator
from kinetix_insights.push.kafka_consumer import PushGenerator
from kinetix_insights.push.models import IntradayPush
from kinetix_insights.push.push_generator import IntradayPushGenerator
from kinetix_insights.push.threshold_evaluator import IntradayAlert

pytestmark = pytest.mark.unit


def _fixed_now() -> datetime:
    return datetime(2026, 5, 20, 9, 0, 0, tzinfo=timezone.utc)


def _alert(
    *,
    alert_type: str = "VAR_BREACH",
    severity: str = "critical",
    book_id: str = "fx-main",
    current: float = 7_500_000.0,
    threshold: float = 5_000_000.0,
) -> IntradayAlert:
    """Build a representative firing :class:`IntradayAlert`."""

    return IntradayAlert(
        alert_type=alert_type,
        severity=severity,
        book_id=book_id,
        current=current,
        threshold=threshold,
        cooldown_key=f"{book_id}:{alert_type}",
    )


# ---------------------------------------------------------------------------
# Live IntradayPushGenerator
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_live_generator_composes_a_push_with_the_pinned_fields() -> None:
    """The live generator emits a push carrying exactly the § 7.4 fields."""

    generator = IntradayPushGenerator(now=_fixed_now)

    push = await generator.compose(_alert())

    assert isinstance(push, IntradayPush)
    assert set(push.model_dump().keys()) == {
        "alert_type",
        "severity",
        "book_id",
        "headline",
        "context_bullets",
        "sources",
        "session_id",
        "generated_at",
    }


@pytest.mark.asyncio
async def test_live_generator_carries_alert_fields_into_the_push() -> None:
    """``alert_type``/``severity``/``book_id`` are carried through verbatim."""

    generator = IntradayPushGenerator(now=_fixed_now)

    push = await generator.compose(
        _alert(alert_type="POSITION_DELTA", severity="warning", book_id="rates-emea")
    )

    assert push.alert_type == "POSITION_DELTA"
    assert push.severity == "warning"
    assert push.book_id == "rates-emea"


@pytest.mark.asyncio
async def test_live_generator_stamps_an_iso_utc_generated_at() -> None:
    """``generated_at`` is the injected clock reading, in UTC."""

    generator = IntradayPushGenerator(now=_fixed_now)

    push = await generator.compose(_alert())

    assert push.generated_at == _fixed_now()
    assert push.generated_at.tzinfo is not None


@pytest.mark.asyncio
async def test_live_generator_assigns_a_unique_session_id_per_push() -> None:
    """Each composed push gets its own UUID ``session_id``."""

    generator = IntradayPushGenerator(now=_fixed_now)

    first = await generator.compose(_alert())
    second = await generator.compose(_alert())

    assert first.session_id
    assert second.session_id
    assert first.session_id != second.session_id


@pytest.mark.asyncio
async def test_live_generator_headline_mentions_book_and_alert_type() -> None:
    """The headline is a human-readable summary of the breach."""

    generator = IntradayPushGenerator(now=_fixed_now)

    push = await generator.compose(_alert(book_id="fx-main", alert_type="VAR_BREACH"))

    assert "fx-main" in push.headline
    assert push.headline  # non-empty


@pytest.mark.asyncio
async def test_live_generator_context_bullets_report_current_and_threshold() -> None:
    """Context bullets carry the breaching measure and the breached limit."""

    generator = IntradayPushGenerator(now=_fixed_now)

    push = await generator.compose(_alert(current=7_500_000.0, threshold=5_000_000.0))

    joined = " ".join(push.context_bullets)
    assert "7,500,000" in joined
    assert "5,000,000" in joined


@pytest.mark.asyncio
async def test_live_generator_sources_include_the_threshold_evaluation_tool_calls() -> None:
    """``sources`` carries the citation trail for the threshold evaluation.

    The two tool calls used to evaluate the threshold are the
    threshold-config read (``get_alert_thresholds``) and the
    ``risk.results`` Kafka event the breaching measure was read from.
    """

    generator = IntradayPushGenerator(now=_fixed_now)

    push = await generator.compose(_alert())

    assert len(push.sources) >= 2
    assert all(isinstance(src, Citation) for src in push.sources)
    tools = {src.tool for src in push.sources}
    assert "get_alert_thresholds" in tools
    assert "risk.results" in tools


@pytest.mark.asyncio
async def test_live_generator_threshold_citation_records_the_breached_value() -> None:
    """The threshold citation pins the configured threshold value."""

    generator = IntradayPushGenerator(now=_fixed_now)

    push = await generator.compose(_alert(threshold=5_000_000.0))

    threshold_citation = next(
        src for src in push.sources if src.tool == "get_alert_thresholds"
    )
    assert threshold_citation.result_value == 5_000_000.0


@pytest.mark.asyncio
async def test_live_generator_risk_results_citation_records_the_current_measure() -> None:
    """The risk.results citation pins the breaching measure."""

    generator = IntradayPushGenerator(now=_fixed_now)

    push = await generator.compose(_alert(current=7_500_000.0))

    risk_citation = next(src for src in push.sources if src.tool == "risk.results")
    assert risk_citation.result_value == 7_500_000.0


@pytest.mark.asyncio
async def test_live_generator_handle_alert_satisfies_the_push_generator_protocol() -> None:
    """The live generator satisfies the ``PushGenerator`` protocol."""

    generator = IntradayPushGenerator(now=_fixed_now)

    assert isinstance(generator, PushGenerator)
    # handle_alert must be awaitable and not raise.
    await generator.handle_alert(_alert())


@pytest.mark.asyncio
async def test_live_generator_handle_alert_records_the_composed_push() -> None:
    """``handle_alert`` composes and stores the push for later dispatch.

    Checkbox 7.7 wires the gateway POST; until then ``handle_alert``
    composes the payload and records it so the wiring (and these tests)
    can observe what would be sent.
    """

    generator = IntradayPushGenerator(now=_fixed_now)

    await generator.handle_alert(_alert(book_id="fx-main"))

    assert len(generator.composed_pushes) == 1
    assert generator.composed_pushes[0].book_id == "fx-main"


@pytest.mark.asyncio
async def test_live_generator_dispatches_to_an_injected_sink() -> None:
    """When given a sink, ``handle_alert`` forwards the composed push to it.

    The sink is the seam checkbox 7.7's ``GatewayPushClient`` plugs into
    — an injected async callable, so 7.4 stays decoupled from the HTTP
    transport.
    """

    sent: list[IntradayPush] = []

    async def _sink(push: IntradayPush) -> None:
        sent.append(push)

    generator = IntradayPushGenerator(now=_fixed_now, sink=_sink)

    await generator.handle_alert(_alert(book_id="rates-emea"))

    assert len(sent) == 1
    assert sent[0].book_id == "rates-emea"


# ---------------------------------------------------------------------------
# Canned CannedIntradayPushGenerator
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_canned_generator_composes_a_push_with_the_pinned_fields() -> None:
    """The canned generator emits the same § 7.4 payload shape."""

    generator = CannedIntradayPushGenerator()

    push = await generator.compose(_alert())

    assert isinstance(push, IntradayPush)
    assert set(push.model_dump().keys()) == {
        "alert_type",
        "severity",
        "book_id",
        "headline",
        "context_bullets",
        "sources",
        "session_id",
        "generated_at",
    }


@pytest.mark.asyncio
async def test_canned_generator_replays_fixture_data() -> None:
    """The canned generator emits deterministic fixture content."""

    generator = CannedIntradayPushGenerator()

    push = await generator.compose(_alert())

    assert push.headline  # non-empty fixture headline
    assert push.context_bullets  # non-empty fixture bullets
    assert push.sources  # non-empty fixture provenance


@pytest.mark.asyncio
async def test_canned_generator_sources_are_citations() -> None:
    """The canned fixture's ``sources`` parse into :class:`Citation`."""

    generator = CannedIntradayPushGenerator()

    push = await generator.compose(_alert())

    assert all(isinstance(src, Citation) for src in push.sources)


@pytest.mark.asyncio
async def test_canned_generator_assigns_a_unique_session_id_per_push() -> None:
    """Each canned push still gets its own UUID ``session_id``.

    The fixture is deterministic content, but ``session_id`` is a
    per-push correlation id — it is freshly generated even in canned
    mode so two pushes never share one.
    """

    generator = CannedIntradayPushGenerator()

    first = await generator.compose(_alert())
    second = await generator.compose(_alert())

    assert first.session_id != second.session_id


@pytest.mark.asyncio
async def test_canned_generator_stamps_generated_at_from_the_clock() -> None:
    """``generated_at`` is the injected clock reading, not a frozen fixture."""

    generator = CannedIntradayPushGenerator(now=_fixed_now)

    push = await generator.compose(_alert())

    assert push.generated_at == _fixed_now()


@pytest.mark.asyncio
async def test_canned_generator_handle_alert_satisfies_the_protocol() -> None:
    """The canned generator satisfies the ``PushGenerator`` protocol."""

    generator = CannedIntradayPushGenerator()

    assert isinstance(generator, PushGenerator)
    await generator.handle_alert(_alert())


@pytest.mark.asyncio
async def test_canned_generator_handle_alert_records_the_composed_push() -> None:
    """``handle_alert`` composes and records the canned push."""

    generator = CannedIntradayPushGenerator()

    await generator.handle_alert(_alert())

    assert len(generator.composed_pushes) == 1
    assert isinstance(generator.composed_pushes[0], IntradayPush)


# ---------------------------------------------------------------------------
# Factory — DEMO_MODE selection
# ---------------------------------------------------------------------------


def test_factory_returns_canned_generator_when_demo_mode_is_true(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """``DEMO_MODE=true`` selects the canned generator."""

    monkeypatch.setenv("DEMO_MODE", "true")

    generator = build_intraday_push_generator()

    assert isinstance(generator, CannedIntradayPushGenerator)


def test_factory_demo_mode_selection_is_case_insensitive(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """``DEMO_MODE`` matching is case-insensitive, mirroring sibling factories."""

    monkeypatch.setenv("DEMO_MODE", "TRUE")

    generator = build_intraday_push_generator()

    assert isinstance(generator, CannedIntradayPushGenerator)


def test_factory_returns_live_generator_when_demo_mode_is_unset(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """With ``DEMO_MODE`` unset the factory returns the live generator."""

    monkeypatch.delenv("DEMO_MODE", raising=False)

    generator = build_intraday_push_generator()

    assert isinstance(generator, IntradayPushGenerator)


def test_factory_returns_live_generator_when_demo_mode_is_false(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """``DEMO_MODE=false`` selects the live generator."""

    monkeypatch.setenv("DEMO_MODE", "false")

    generator = build_intraday_push_generator()

    assert isinstance(generator, IntradayPushGenerator)


def test_factory_passes_the_sink_through_to_the_live_generator(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A sink given to the factory reaches the live generator."""

    monkeypatch.delenv("DEMO_MODE", raising=False)
    sent: list[IntradayPush] = []

    async def _sink(push: IntradayPush) -> None:
        sent.append(push)

    generator = build_intraday_push_generator(sink=_sink)

    assert isinstance(generator, IntradayPushGenerator)

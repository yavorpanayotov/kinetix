"""Unit tests for the structured audit-logging facility (checkbox 10.3).

Every chat / brief / query / push call in ``ai-insights-service`` must
emit exactly ONE structured audit log line — a single-line JSON object
carrying ``user_id``, ``endpoint``, ``prompt_hash``, ``tool_calls``,
``tokens_estimated``, ``mode``, ``latency_ms``, and ``timestamp``. The
line goes through Python's :mod:`logging` with a Loki-compatible JSON
formatter so a log shipper can parse each record as one object per line.

These tests assert the log-line format and that the four real call
paths each emit exactly one such line. ``DEMO_MODE=true`` keeps the
chat / query paths deterministic (the canned client).
"""

from __future__ import annotations

import json
import logging
import os
from collections.abc import AsyncIterator
from datetime import datetime, timezone
from typing import Any

import httpx
import pytest
import pytest_asyncio
from httpx import ASGITransport

from kinetix_insights.audit.audit_logger import AUDIT_LOGGER_NAME, AuditLogger
from kinetix_insights.audit.audit_record import AuditRecord
from kinetix_insights.audit.json_formatter import JsonLogFormatter

pytestmark = pytest.mark.unit


_REQUIRED_FIELDS = {
    "user_id",
    "endpoint",
    "prompt_hash",
    "tool_calls",
    "tokens_estimated",
    "mode",
    "latency_ms",
    "timestamp",
}


# --------------------------------------------------------------------------
# Capture helper
# --------------------------------------------------------------------------
class _CapturingHandler(logging.Handler):
    """A logging handler that keeps every formatted record string."""

    def __init__(self, formatter: logging.Formatter) -> None:
        super().__init__()
        self.setFormatter(formatter)
        self.lines: list[str] = []

    def emit(self, record: logging.LogRecord) -> None:
        self.lines.append(self.format(record))


def _attach_capture() -> _CapturingHandler:
    """Attach a JSON-formatting capturing handler to the audit logger."""

    handler = _CapturingHandler(JsonLogFormatter())
    audit_log = logging.getLogger(AUDIT_LOGGER_NAME)
    audit_log.addHandler(handler)
    audit_log.setLevel(logging.INFO)
    audit_log.propagate = False
    return handler


def _detach_capture(handler: _CapturingHandler) -> None:
    """Remove a previously attached capturing handler."""

    logging.getLogger(AUDIT_LOGGER_NAME).removeHandler(handler)


# --------------------------------------------------------------------------
# AuditRecord / JsonLogFormatter unit tests
# --------------------------------------------------------------------------
def test_audit_record_carries_all_eight_fields() -> None:
    """An ``AuditRecord`` serialises to a dict with all eight audit fields."""

    record = AuditRecord(
        user_id="trader-1",
        endpoint="chat",
        prompt_hash=AuditRecord.hash_prompt("show my var"),
        tool_calls=["get_book_var"],
        tokens_estimated=42,
        mode="canned",
        latency_ms=12.5,
        timestamp=datetime(2026, 5, 20, 9, 0, tzinfo=timezone.utc),
    )

    payload = record.to_dict()
    assert set(payload) == _REQUIRED_FIELDS


def test_prompt_hash_is_a_hash_not_the_raw_prompt() -> None:
    """``hash_prompt`` returns a stable digest — never the raw prompt text."""

    prompt = "what is my biggest exposure?"
    digest = AuditRecord.hash_prompt(prompt)

    assert digest != prompt
    assert prompt not in digest
    # sha256 hex digest is 64 lowercase hex chars and is deterministic.
    assert len(digest) == 64
    assert all(c in "0123456789abcdef" for c in digest)
    assert digest == AuditRecord.hash_prompt(prompt)
    assert digest != AuditRecord.hash_prompt(prompt + " ")


def test_json_formatter_renders_one_line_json_object() -> None:
    """The JSON formatter renders an audit record as one-line JSON."""

    handler = _attach_capture()
    try:
        logger = AuditLogger()
        logger.emit(
            AuditRecord(
                user_id="trader-1",
                endpoint="brief",
                prompt_hash=AuditRecord.hash_prompt("brief"),
                tool_calls=[],
                tokens_estimated=10,
                mode="canned",
                latency_ms=3.0,
                timestamp=datetime.now(timezone.utc),
            )
        )
    finally:
        line = handler.lines[-1] if handler.lines else ""
        _detach_capture(handler)

    # Exactly one record, valid JSON, single physical line.
    assert "\n" not in line
    parsed = json.loads(line)
    assert isinstance(parsed, dict)
    assert _REQUIRED_FIELDS.issubset(parsed)


def _assert_audit_line_shape(line: str) -> dict[str, Any]:
    """Parse one captured audit line and assert every field's shape."""

    parsed = json.loads(line)
    assert _REQUIRED_FIELDS.issubset(parsed), parsed

    assert isinstance(parsed["user_id"], str) and parsed["user_id"]
    assert isinstance(parsed["endpoint"], str) and parsed["endpoint"]
    assert isinstance(parsed["prompt_hash"], str) and len(parsed["prompt_hash"]) == 64
    assert isinstance(parsed["tool_calls"], list)
    assert all(isinstance(t, str) for t in parsed["tool_calls"])
    assert isinstance(parsed["tokens_estimated"], (int, float))
    assert parsed["tokens_estimated"] >= 0
    assert isinstance(parsed["mode"], str) and parsed["mode"]
    assert isinstance(parsed["latency_ms"], (int, float))
    assert parsed["latency_ms"] >= 0
    # timestamp must be ISO-8601 parseable.
    assert isinstance(parsed["timestamp"], str)
    datetime.fromisoformat(parsed["timestamp"])
    return parsed


# --------------------------------------------------------------------------
# Route-level integration: chat / query call paths emit exactly one line
# --------------------------------------------------------------------------
def _force_demo_mode() -> Any:
    """Set ``DEMO_MODE=true`` and return the freshly imported FastAPI app."""

    os.environ["DEMO_MODE"] = "true"
    from kinetix_insights.app import app  # imported after env is set

    return app


@pytest_asyncio.fixture
async def client() -> AsyncIterator[httpx.AsyncClient]:
    """An in-process ASGI client against the canned-mode app."""

    app = _force_demo_mode()
    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(
        transport=transport, base_url="http://insights.test"
    ) as ac:
        async with app.router.lifespan_context(app):
            yield ac


@pytest.mark.asyncio
async def test_chat_call_emits_exactly_one_audit_line(
    client: httpx.AsyncClient,
) -> None:
    """A chat call writes exactly one structured audit log line."""

    handler = _attach_capture()
    try:
        response = await client.post(
            "/api/v1/insights/chat",
            headers={"X-User-Id": "trader-7", "X-User-Books": "fx-main"},
            json={"message": "what is my book VaR?"},
        )
        assert response.status_code == 200
        await response.aread()
    finally:
        lines = list(handler.lines)
        _detach_capture(handler)

    chat_lines = [
        ln for ln in lines if json.loads(ln).get("endpoint") == "chat"
    ]
    assert len(chat_lines) == 1
    parsed = _assert_audit_line_shape(chat_lines[0])
    assert parsed["user_id"] == "trader-7"
    assert parsed["endpoint"] == "chat"
    # The raw prompt must not appear anywhere in the audit line.
    assert "what is my book VaR?" not in chat_lines[0]


@pytest.mark.asyncio
async def test_query_call_emits_exactly_one_audit_line(
    client: httpx.AsyncClient,
) -> None:
    """A saved-query run writes exactly one structured audit log line."""

    from kinetix_insights.queries.loader import load_saved_query_templates

    templates = load_saved_query_templates()
    assert templates, "expected at least one built-in saved-query template"
    template = templates[0]
    params = {name: "fx-main" for name in template.required_params}

    handler = _attach_capture()
    try:
        response = await client.post(
            f"/api/v1/insights/queries/{template.id}/run",
            headers={"X-User-Id": "trader-9"},
            json={"params": params},
        )
        assert response.status_code == 200
        await response.aread()
    finally:
        lines = list(handler.lines)
        _detach_capture(handler)

    query_lines = [
        ln for ln in lines if json.loads(ln).get("endpoint") == "query"
    ]
    assert len(query_lines) == 1
    parsed = _assert_audit_line_shape(query_lines[0])
    assert parsed["user_id"] == "trader-9"
    assert parsed["endpoint"] == "query"


@pytest.mark.asyncio
async def test_brief_call_emits_exactly_one_audit_line(
    client: httpx.AsyncClient,
) -> None:
    """A morning-brief call writes exactly one structured audit log line."""

    handler = _attach_capture()
    try:
        response = await client.get(
            "/api/v1/insights/brief/today",
            headers={"X-User-Id": "trader-3", "X-User-Books": "fx-main"},
        )
        assert response.status_code == 200
    finally:
        lines = list(handler.lines)
        _detach_capture(handler)

    brief_lines = [
        ln for ln in lines if json.loads(ln).get("endpoint") == "brief"
    ]
    assert len(brief_lines) == 1
    parsed = _assert_audit_line_shape(brief_lines[0])
    assert parsed["user_id"] == "trader-3"
    assert parsed["endpoint"] == "brief"


# --------------------------------------------------------------------------
# Push call path: one audit line per dispatched intraday alert
# --------------------------------------------------------------------------
class _FakeKafkaMessage:
    """A minimal aiokafka-shaped record carrying a JSON-encoded value."""

    def __init__(self, value: bytes, topic: str = "risk.results") -> None:
        self.value = value
        self.topic = topic


class _FakeKafkaConsumer:
    """In-memory async-iterable stand-in for ``aiokafka.AIOKafkaConsumer``."""

    def __init__(self, messages: list[_FakeKafkaMessage]) -> None:
        self._messages = list(messages)
        self._index = 0

    async def start(self) -> None:
        return None

    async def stop(self) -> None:
        return None

    def __aiter__(self) -> "_FakeKafkaConsumer":
        return self

    async def __anext__(self) -> _FakeKafkaMessage:
        import asyncio

        await asyncio.sleep(0)
        if self._index >= len(self._messages):
            raise StopAsyncIteration
        message = self._messages[self._index]
        self._index += 1
        return message


class _StubEvaluator:
    """Evaluator double — returns one firing alert for any event."""

    async def evaluate(self, event: dict[str, Any]) -> list[Any]:
        from kinetix_insights.push.threshold_evaluator import IntradayAlert

        return [
            IntradayAlert(
                alert_type="VAR_BREACH",
                severity="critical",
                book_id="fx-main",
                current=6_200_000.0,
                threshold=4_000_000.0,
                cooldown_key="fx-main:VAR_BREACH",
            )
        ]


class _RecordingPushGenerator:
    """Push generator double — records each alert it is handed."""

    def __init__(self) -> None:
        self.alerts: list[Any] = []

    async def handle_alert(self, alert: Any) -> None:
        self.alerts.append(alert)


@pytest.mark.asyncio
async def test_push_call_emits_exactly_one_audit_line() -> None:
    """A dispatched intraday push writes exactly one structured audit line."""

    from kinetix_insights.push.kafka_consumer import IntradayKafkaConsumer

    fake_consumer = _FakeKafkaConsumer(
        [_FakeKafkaMessage(json.dumps({"bookId": "fx-main"}).encode("utf-8"))]
    )
    consumer = IntradayKafkaConsumer(
        evaluator=_StubEvaluator(),  # type: ignore[arg-type]
        push_generator=_RecordingPushGenerator(),
        consumer=fake_consumer,
        user_id="demo-trader",
    )

    handler = _attach_capture()
    try:
        await consumer.start()
        task = consumer._task
        assert task is not None
        await task
        await consumer.stop()
    finally:
        lines = list(handler.lines)
        _detach_capture(handler)

    push_lines = [
        ln for ln in lines if json.loads(ln).get("endpoint") == "push"
    ]
    assert len(push_lines) == 1
    parsed = _assert_audit_line_shape(push_lines[0])
    assert parsed["user_id"] == "demo-trader"
    assert parsed["endpoint"] == "push"
    assert parsed["mode"] == "push"

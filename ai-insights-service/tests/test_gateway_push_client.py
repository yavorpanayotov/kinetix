"""Unit tests for :mod:`kinetix_insights.clients.gateway_push_client`.

These tests never touch the network. ``HttpxGatewayPushClient`` is
exercised against a ``httpx.MockTransport`` so the full request
lifecycle — URL construction, the ``X-Internal-Token`` header, and the
JSON body — runs through real ``httpx`` machinery without a server.

The assertions pin checkbox 7.7's contract: the POST goes to
``{base}/internal/copilot/push``, carries the cluster-internal token
header, and the body matches the snake_case ``IntradayPush`` shape the
gateway's :class:`CopilotPushRequest` DTO expects.
"""

from __future__ import annotations

import json
from datetime import datetime, timezone

import httpx
import pytest

from kinetix_insights.citations.models import Citation
from kinetix_insights.clients.gateway_push_client import (
    INTERNAL_TOKEN_HEADER,
    PUSH_PATH,
    GatewayPushError,
    HttpxGatewayPushClient,
)
from kinetix_insights.push.models import IntradayPush

pytestmark = pytest.mark.unit


def _sample_push() -> IntradayPush:
    """Return a fully-populated :class:`IntradayPush` for assertions."""

    citation = Citation(
        tool="get_alert_thresholds",
        params={"scope": "GLOBAL", "alert_type": "VAR_BREACH"},
        result_field="threshold_value",
        result_value=1_000_000.0,
        result_currency=None,
        as_of_timestamp=datetime(2026, 5, 20, 9, 0, tzinfo=timezone.utc),
        data_source="risk-orchestrator",
        freshness_seconds=0,
        quality_flags=["EVALUATED_FROM_ALERT"],
    )
    return IntradayPush(
        alert_type="VAR_BREACH",
        severity="critical",
        book_id="fx-main",
        headline="Critical VAR_BREACH on fx-main",
        context_bullets=["Current VaR: 1,200,000", "Threshold: 1,000,000"],
        sources=[citation],
        session_id="session-abc",
        generated_at=datetime(2026, 5, 20, 9, 30, tzinfo=timezone.utc),
    )


@pytest.mark.asyncio
async def test_push_posts_to_internal_copilot_push_url() -> None:
    captured: dict[str, httpx.Request] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["request"] = request
        return httpx.Response(202, json={"status": "accepted"})

    client = HttpxGatewayPushClient(
        base_url="http://gateway:8080",
        internal_token="secret-token",
        transport=httpx.MockTransport(handler),
    )

    await client.push(_sample_push())

    request = captured["request"]
    assert request.method == "POST"
    assert str(request.url) == "http://gateway:8080/internal/copilot/push"


@pytest.mark.asyncio
async def test_push_trims_trailing_slash_from_base_url() -> None:
    captured: dict[str, httpx.Request] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["request"] = request
        return httpx.Response(202)

    client = HttpxGatewayPushClient(
        base_url="http://gateway:8080/",
        internal_token="secret-token",
        transport=httpx.MockTransport(handler),
    )

    await client.push(_sample_push())

    assert str(captured["request"].url) == "http://gateway:8080/internal/copilot/push"


@pytest.mark.asyncio
async def test_push_carries_internal_token_header() -> None:
    captured: dict[str, httpx.Request] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["request"] = request
        return httpx.Response(202)

    client = HttpxGatewayPushClient(
        base_url="http://gateway:8080",
        internal_token="secret-token",
        transport=httpx.MockTransport(handler),
    )

    await client.push(_sample_push())

    assert captured["request"].headers[INTERNAL_TOKEN_HEADER] == "secret-token"


@pytest.mark.asyncio
async def test_push_body_matches_intraday_push_snake_case_shape() -> None:
    captured: dict[str, httpx.Request] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["request"] = request
        return httpx.Response(202)

    client = HttpxGatewayPushClient(
        base_url="http://gateway:8080",
        internal_token="secret-token",
        transport=httpx.MockTransport(handler),
    )
    push = _sample_push()

    await client.push(push)

    body = json.loads(captured["request"].read())
    assert body["alert_type"] == "VAR_BREACH"
    assert body["severity"] == "critical"
    assert body["book_id"] == "fx-main"
    assert body["headline"] == "Critical VAR_BREACH on fx-main"
    assert body["context_bullets"] == [
        "Current VaR: 1,200,000",
        "Threshold: 1,000,000",
    ]
    assert body["session_id"] == "session-abc"
    assert body["generated_at"] == "2026-05-20T09:30:00Z"
    # sources is the nested Citation provenance trail, snake_case throughout.
    assert len(body["sources"]) == 1
    source = body["sources"][0]
    assert source["tool"] == "get_alert_thresholds"
    assert source["result_field"] == "threshold_value"
    assert source["as_of_timestamp"] == "2026-05-20T09:00:00Z"
    assert source["quality_flags"] == ["EVALUATED_FROM_ALERT"]
    # The body round-trips back into an IntradayPush unchanged.
    assert IntradayPush.model_validate(body) == push


@pytest.mark.asyncio
async def test_push_raises_on_non_2xx_response() -> None:
    client = HttpxGatewayPushClient(
        base_url="http://gateway:8080",
        internal_token="wrong-token",
        transport=httpx.MockTransport(lambda r: httpx.Response(403)),
    )

    with pytest.raises(GatewayPushError) as excinfo:
        await client.push(_sample_push())

    assert excinfo.value.status_code == 403


@pytest.mark.asyncio
async def test_push_accepts_any_2xx_status() -> None:
    # The gateway returns 202 Accepted; the client must not treat that as
    # a failure just because it is not 200.
    client = HttpxGatewayPushClient(
        base_url="http://gateway:8080",
        internal_token="secret-token",
        transport=httpx.MockTransport(lambda r: httpx.Response(202)),
    )

    await client.push(_sample_push())  # must not raise


def test_push_path_constant_matches_gateway_route() -> None:
    # The gateway route is POST /internal/copilot/push (CopilotInternalRoutes.kt).
    assert PUSH_PATH == "/internal/copilot/push"


def test_internal_token_header_constant_matches_gateway() -> None:
    # The gateway expects the X-Internal-Token header (CopilotInternalRoutes.kt).
    assert INTERNAL_TOKEN_HEADER == "X-Internal-Token"


@pytest.mark.asyncio
async def test_from_env_builds_client_from_environment(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("GATEWAY_INTERNAL_URL", "http://gateway:8080")
    monkeypatch.setenv("COPILOT_INTERNAL_TOKEN", "env-token")
    captured: dict[str, httpx.Request] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["request"] = request
        return httpx.Response(202)

    client = HttpxGatewayPushClient.from_env(transport=httpx.MockTransport(handler))
    assert client is not None

    await client.push(_sample_push())

    request = captured["request"]
    assert str(request.url) == "http://gateway:8080/internal/copilot/push"
    assert request.headers[INTERNAL_TOKEN_HEADER] == "env-token"


def test_from_env_returns_none_when_gateway_url_missing(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("GATEWAY_INTERNAL_URL", raising=False)
    monkeypatch.setenv("COPILOT_INTERNAL_TOKEN", "env-token")

    assert HttpxGatewayPushClient.from_env() is None


def test_from_env_returns_none_when_token_missing(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("GATEWAY_INTERNAL_URL", "http://gateway:8080")
    monkeypatch.delenv("COPILOT_INTERNAL_TOKEN", raising=False)

    assert HttpxGatewayPushClient.from_env() is None

"""Unit tests for :mod:`kinetix_insights.clients.kinetix_http_client`.

These tests never touch the network. ``HttpxKinetixHttpClient`` is
exercised against a ``httpx.MockTransport`` so the full request/response
lifecycle — including header stamping and status-code handling — runs
through real ``httpx`` machinery without a server.

``FakeKinetixHttpClient`` is exercised in isolation as a behaviour-
compatible double for downstream tests.
"""

from __future__ import annotations

import httpx
import pytest

from kinetix_insights.clients.kinetix_http_client import (
    HttpxKinetixHttpClient,
    KinetixHttpError,
    service_base_urls,
)
from kinetix_insights.clients.user_context import UserContext
from tests.fakes.fake_kinetix_http_client import FakeKinetixHttpClient

pytestmark = pytest.mark.unit


# ---------------------------------------------------------------------------
# UserContext
# ---------------------------------------------------------------------------


def test_user_context_to_headers_renders_id_and_books() -> None:
    user = UserContext(user_id="trader-1", books=("fx-main", "rates-emea"))

    headers = user.to_headers()

    assert headers == {
        "X-User-Id": "trader-1",
        "X-User-Books": "fx-main,rates-emea",
    }


def test_user_context_to_headers_handles_single_book() -> None:
    user = UserContext(user_id="trader-2", books=("fx-main",))

    headers = user.to_headers()

    assert headers == {"X-User-Id": "trader-2", "X-User-Books": "fx-main"}


def test_user_context_to_headers_handles_no_books() -> None:
    user = UserContext(user_id="trader-3", books=())

    headers = user.to_headers()

    assert headers == {"X-User-Id": "trader-3", "X-User-Books": ""}


# ---------------------------------------------------------------------------
# service_base_urls
# ---------------------------------------------------------------------------


def _set_service_env(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("POSITION_SERVICE_URL", "http://position:8080")
    monkeypatch.setenv("RISK_ORCHESTRATOR_URL", "http://risk-orchestrator:8080")
    monkeypatch.setenv("PRICE_SERVICE_URL", "http://price:8080")
    monkeypatch.setenv("VOLATILITY_SERVICE_URL", "http://volatility:8080")
    monkeypatch.setenv("CORRELATION_SERVICE_URL", "http://correlation:8080")
    monkeypatch.setenv("REFERENCE_DATA_SERVICE_URL", "http://reference-data:8080")
    monkeypatch.setenv("NOTIFICATION_SERVICE_URL", "http://notification:8080")
    monkeypatch.setenv("AUDIT_SERVICE_URL", "http://audit:8080")


def test_service_base_urls_reads_all_env_vars(monkeypatch: pytest.MonkeyPatch) -> None:
    _set_service_env(monkeypatch)

    urls = service_base_urls()

    assert urls == {
        "position": "http://position:8080",
        "risk-orchestrator": "http://risk-orchestrator:8080",
        "price": "http://price:8080",
        "volatility": "http://volatility:8080",
        "correlation": "http://correlation:8080",
        "reference-data": "http://reference-data:8080",
        "notification": "http://notification:8080",
        "audit": "http://audit:8080",
    }


def test_service_base_urls_skips_unset_env_vars(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    for var in (
        "POSITION_SERVICE_URL",
        "RISK_ORCHESTRATOR_URL",
        "PRICE_SERVICE_URL",
        "VOLATILITY_SERVICE_URL",
        "CORRELATION_SERVICE_URL",
        "REFERENCE_DATA_SERVICE_URL",
        "NOTIFICATION_SERVICE_URL",
        "AUDIT_SERVICE_URL",
    ):
        monkeypatch.delenv(var, raising=False)
    monkeypatch.setenv("POSITION_SERVICE_URL", "http://position:8080")

    urls = service_base_urls()

    assert urls == {"position": "http://position:8080"}


# ---------------------------------------------------------------------------
# HttpxKinetixHttpClient — GET
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_stamps_user_id_and_books_headers(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _set_service_env(monkeypatch)
    captured: dict[str, httpx.Request] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["request"] = request
        return httpx.Response(200, json={"ok": True})

    transport = httpx.MockTransport(handler)
    client = HttpxKinetixHttpClient(transport=transport)
    user = UserContext(user_id="trader-1", books=("fx-main", "rates-emea"))

    body = await client.get("position", "/positions", params={"book": "fx-main"}, user=user)

    assert body == {"ok": True}
    request = captured["request"]
    assert request.method == "GET"
    assert str(request.url) == "http://position:8080/positions?book=fx-main"
    assert request.headers["X-User-Id"] == "trader-1"
    assert request.headers["X-User-Books"] == "fx-main,rates-emea"


@pytest.mark.asyncio
async def test_get_without_params_omits_query_string(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _set_service_env(monkeypatch)
    captured: dict[str, httpx.Request] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["request"] = request
        return httpx.Response(200, json={})

    client = HttpxKinetixHttpClient(transport=httpx.MockTransport(handler))
    user = UserContext(user_id="trader-1", books=("fx-main",))

    await client.get("position", "/positions", params=None, user=user)

    request = captured["request"]
    assert str(request.url) == "http://position:8080/positions"


# ---------------------------------------------------------------------------
# HttpxKinetixHttpClient — POST
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_post_stamps_user_id_and_books_headers(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _set_service_env(monkeypatch)
    captured: dict[str, httpx.Request] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["request"] = request
        return httpx.Response(200, json={"jobId": "job-42"})

    client = HttpxKinetixHttpClient(transport=httpx.MockTransport(handler))
    user = UserContext(user_id="trader-1", books=("fx-main",))

    body = await client.post(
        "risk-orchestrator",
        "/valuation",
        json={"bookId": "fx-main"},
        user=user,
    )

    assert body == {"jobId": "job-42"}
    request = captured["request"]
    assert request.method == "POST"
    assert str(request.url) == "http://risk-orchestrator:8080/valuation"
    assert request.headers["X-User-Id"] == "trader-1"
    assert request.headers["X-User-Books"] == "fx-main"
    assert request.read() == b'{"bookId":"fx-main"}'


# ---------------------------------------------------------------------------
# Service routing
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_unknown_service_raises_value_error(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _set_service_env(monkeypatch)
    client = HttpxKinetixHttpClient(
        transport=httpx.MockTransport(lambda r: httpx.Response(200, json={}))
    )
    user = UserContext(user_id="trader-1", books=("fx-main",))

    with pytest.raises(ValueError, match="unknown service"):
        await client.get("does-not-exist", "/x", params=None, user=user)


@pytest.mark.asyncio
async def test_unconfigured_known_service_raises_value_error(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    for var in (
        "POSITION_SERVICE_URL",
        "RISK_ORCHESTRATOR_URL",
        "PRICE_SERVICE_URL",
        "VOLATILITY_SERVICE_URL",
        "CORRELATION_SERVICE_URL",
        "REFERENCE_DATA_SERVICE_URL",
        "NOTIFICATION_SERVICE_URL",
        "AUDIT_SERVICE_URL",
    ):
        monkeypatch.delenv(var, raising=False)
    client = HttpxKinetixHttpClient(
        transport=httpx.MockTransport(lambda r: httpx.Response(200, json={}))
    )
    user = UserContext(user_id="trader-1", books=("fx-main",))

    with pytest.raises(ValueError, match="unknown service|not configured"):
        await client.get("position", "/positions", params=None, user=user)


# ---------------------------------------------------------------------------
# Error handling
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_404_raises_kinetix_http_error_with_not_found(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _set_service_env(monkeypatch)
    transport = httpx.MockTransport(
        lambda r: httpx.Response(404, json={"error": "missing"})
    )
    client = HttpxKinetixHttpClient(transport=transport)
    user = UserContext(user_id="trader-1", books=("fx-main",))

    with pytest.raises(KinetixHttpError) as excinfo:
        await client.get("position", "/positions/unknown", params=None, user=user)

    err = excinfo.value
    assert err.status_code == 404
    assert err.service == "position"
    assert err.path == "/positions/unknown"
    assert err.code == "NOT_FOUND"


@pytest.mark.asyncio
async def test_500_raises_kinetix_http_error_with_upstream_error(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _set_service_env(monkeypatch)
    transport = httpx.MockTransport(
        lambda r: httpx.Response(500, json={"error": "boom"})
    )
    client = HttpxKinetixHttpClient(transport=transport)
    user = UserContext(user_id="trader-1", books=("fx-main",))

    with pytest.raises(KinetixHttpError) as excinfo:
        await client.get("position", "/positions", params=None, user=user)

    err = excinfo.value
    assert err.status_code == 500
    assert err.service == "position"
    assert err.path == "/positions"
    assert err.code == "UPSTREAM_ERROR"


# ---------------------------------------------------------------------------
# FakeKinetixHttpClient
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_fake_records_get_calls() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", "position", "/positions", {"items": []})
    user = UserContext(user_id="trader-1", books=("fx-main",))

    result = await fake.get("position", "/positions", params={"book": "fx-main"}, user=user)

    assert result == {"items": []}
    assert len(fake.recorded_calls) == 1
    call = fake.recorded_calls[0]
    assert call.method == "GET"
    assert call.service == "position"
    assert call.path == "/positions"
    assert call.params == {"book": "fx-main"}
    assert call.user == user


@pytest.mark.asyncio
async def test_fake_records_post_calls() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "POST", "risk-orchestrator", "/valuation", {"jobId": "job-1"}
    )
    user = UserContext(user_id="trader-1", books=("fx-main",))

    result = await fake.post(
        "risk-orchestrator",
        "/valuation",
        json={"bookId": "fx-main"},
        user=user,
    )

    assert result == {"jobId": "job-1"}
    assert len(fake.recorded_calls) == 1
    call = fake.recorded_calls[0]
    assert call.method == "POST"
    assert call.service == "risk-orchestrator"
    assert call.path == "/valuation"
    assert call.json == {"bookId": "fx-main"}
    assert call.user == user


@pytest.mark.asyncio
async def test_fake_raises_registered_exception() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        "position",
        "/positions/unknown",
        KinetixHttpError(
            status_code=404,
            code="NOT_FOUND",
            message="missing",
            service="position",
            path="/positions/unknown",
        ),
    )
    user = UserContext(user_id="trader-1", books=("fx-main",))

    with pytest.raises(KinetixHttpError) as excinfo:
        await fake.get("position", "/positions/unknown", params=None, user=user)

    assert excinfo.value.code == "NOT_FOUND"


@pytest.mark.asyncio
async def test_fake_defaults_to_not_found_when_no_response_registered() -> None:
    fake = FakeKinetixHttpClient()
    user = UserContext(user_id="trader-1", books=("fx-main",))

    with pytest.raises(KinetixHttpError) as excinfo:
        await fake.get("position", "/anything", params=None, user=user)

    assert excinfo.value.status_code == 404
    assert excinfo.value.code == "NOT_FOUND"


@pytest.mark.asyncio
async def test_fake_returns_distinct_responses_per_endpoint() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", "position", "/a", {"a": 1})
    fake.register_response("GET", "position", "/b", {"b": 2})
    user = UserContext(user_id="trader-1", books=("fx-main",))

    a = await fake.get("position", "/a", params=None, user=user)
    b = await fake.get("position", "/b", params=None, user=user)

    assert a == {"a": 1}
    assert b == {"b": 2}

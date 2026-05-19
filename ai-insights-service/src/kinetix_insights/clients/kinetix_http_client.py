"""Async HTTP abstraction for calling Kinetix backend services.

MCP tools running inside ``ai-insights-service`` need to fan out to
position-service, risk-orchestrator, price-service, and friends. Rather
than each tool wiring up its own ``httpx.AsyncClient`` and remembering to
stamp ``X-User-Id`` / ``X-User-Books`` on every request, they depend on a
:class:`KinetixHttpClient` — a narrow protocol with two methods (``get``
and ``post``) that handles service resolution, header stamping, and
error normalisation in one place.

``HttpxKinetixHttpClient`` is the production implementation backed by
``httpx``. Tests substitute :class:`tests.fakes.fake_kinetix_http_client.
FakeKinetixHttpClient` so they can assert on the calls a tool issued
without standing up real services.

Service URLs are resolved from environment variables at call time
(``POSITION_SERVICE_URL``, ``RISK_ORCHESTRATOR_URL``, etc.) so the
client can be constructed at startup and pick up env changes per call
in tests using ``monkeypatch.setenv``.

Non-2xx responses are normalised to :class:`KinetixHttpError` carrying
both the HTTP status code and a coarse string ``code`` (``NOT_FOUND``,
``UPSTREAM_ERROR``, etc.) so MCP tools can map upstream failures into
the citation error contract without re-deriving the meaning of each
status code.
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Any, Protocol

import httpx

from .user_context import UserContext

# Mapping from short service name (used by tool authors) to the env var
# that must be set to make the service reachable. The short name is what
# tools pass as the ``service`` argument; the env var is what ops sets
# in the deployment.
_SERVICE_ENV_VARS: dict[str, str] = {
    "position": "POSITION_SERVICE_URL",
    "risk-orchestrator": "RISK_ORCHESTRATOR_URL",
    "price": "PRICE_SERVICE_URL",
    "volatility": "VOLATILITY_SERVICE_URL",
    "correlation": "CORRELATION_SERVICE_URL",
    "reference-data": "REFERENCE_DATA_SERVICE_URL",
    "notification": "NOTIFICATION_SERVICE_URL",
    "audit": "AUDIT_SERVICE_URL",
}


def service_base_urls() -> dict[str, str]:
    """Return the configured base URL for every known service.

    Only services whose env var is set appear in the returned dict — a
    deployment that omits ``AUDIT_SERVICE_URL`` simply won't expose
    audit-service to MCP tools, and an attempt to call it will surface
    a clear ``ValueError`` rather than silently routing somewhere wrong.
    """

    return {
        short: os.environ[env_var]
        for short, env_var in _SERVICE_ENV_VARS.items()
        if env_var in os.environ
    }


@dataclass
class KinetixHttpError(Exception):
    """Normalised error raised when a downstream call fails.

    Attributes:
        status_code: HTTP status code returned by the upstream service.
        code: Coarse error category (``NOT_FOUND``, ``UPSTREAM_ERROR``,
            etc.) used by MCP tools to map into the citation error
            contract without re-deriving meaning from status codes.
        message: Best-effort human-readable description.
        service: Short service name the call was routed to.
        path: Path portion of the request, for diagnostics.
    """

    status_code: int
    code: str
    message: str
    service: str
    path: str

    def __post_init__(self) -> None:
        super().__init__(
            f"{self.code} ({self.status_code}) from {self.service}{self.path}: {self.message}"
        )


def _status_to_code(status_code: int) -> str:
    """Map an HTTP status code to a coarse error category.

    The category is what MCP tools assert against — they shouldn't care
    whether the upstream returned 500 vs 502 vs 503, only that it was
    "the upstream service blew up".
    """

    if status_code == 404:
        return "NOT_FOUND"
    if status_code == 401 or status_code == 403:
        return "UNAUTHORIZED"
    if 400 <= status_code < 500:
        return "BAD_REQUEST"
    return "UPSTREAM_ERROR"


class KinetixHttpClient(Protocol):
    """Narrow async HTTP surface MCP tools call into.

    Implementations resolve ``service`` to a base URL, stamp the
    user-context headers, dispatch the request, and normalise non-2xx
    responses to :class:`KinetixHttpError`. Tools never construct URLs
    or headers themselves.
    """

    async def get(
        self,
        service: str,
        path: str,
        *,
        params: dict[str, Any] | None,
        user: UserContext,
    ) -> dict[str, Any]: ...  # pragma: no cover - structural only

    async def post(
        self,
        service: str,
        path: str,
        *,
        json: dict[str, Any],
        user: UserContext,
    ) -> dict[str, Any]: ...  # pragma: no cover - structural only


class HttpxKinetixHttpClient:
    """Production :class:`KinetixHttpClient` backed by ``httpx``.

    Service URLs are resolved per call so tests can use
    ``monkeypatch.setenv`` between requests. ``transport`` is exposed
    purely so unit tests can plug in :class:`httpx.MockTransport` and
    exercise the full request-construction path without a network.
    """

    def __init__(
        self,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
        timeout: float = 5.0,
    ) -> None:
        self._transport = transport
        self._timeout = timeout

    def _resolve(self, service: str) -> str:
        urls = service_base_urls()
        if service not in _SERVICE_ENV_VARS:
            raise ValueError(f"unknown service: {service}")
        if service not in urls:
            raise ValueError(
                f"unknown service: {service} (env var "
                f"{_SERVICE_ENV_VARS[service]} not configured)"
            )
        return urls[service]

    async def _request(
        self,
        method: str,
        service: str,
        path: str,
        *,
        user: UserContext,
        params: dict[str, Any] | None = None,
        json: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        base = self._resolve(service)
        url = f"{base}{path}"
        headers = user.to_headers()
        async with httpx.AsyncClient(
            transport=self._transport, timeout=self._timeout
        ) as client:
            response = await client.request(
                method,
                url,
                params=params,
                json=json,
                headers=headers,
            )
        if response.status_code >= 400:
            raise KinetixHttpError(
                status_code=response.status_code,
                code=_status_to_code(response.status_code),
                message=response.text,
                service=service,
                path=path,
            )
        return response.json()  # type: ignore[no-any-return]

    async def get(
        self,
        service: str,
        path: str,
        *,
        params: dict[str, Any] | None,
        user: UserContext,
    ) -> dict[str, Any]:
        return await self._request(
            "GET", service, path, user=user, params=params
        )

    async def post(
        self,
        service: str,
        path: str,
        *,
        json: dict[str, Any],
        user: UserContext,
    ) -> dict[str, Any]:
        return await self._request(
            "POST", service, path, user=user, json=json
        )

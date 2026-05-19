"""In-memory ``KinetixHttpClient`` double for unit tests.

MCP tool unit tests need to assert on the calls a tool issued
(``method``, ``service``, ``path``, ``params``/``json``, ``user``)
without standing up real backend services. ``FakeKinetixHttpClient``
gives them three primitives:

* :meth:`register_response` — seed a payload or exception for a
  ``(method, service, path)`` tuple.
* :attr:`recorded_calls` — every call the tool made, in order.
* Async :meth:`get` / :meth:`post` — match the production protocol so
  the fake can be substituted at the dependency-injection boundary.

If a tool calls an endpoint that hasn't been registered, the fake
raises ``KinetixHttpError(NOT_FOUND, ...)``. That mirrors what the
real client returns for missing resources and makes tests fail loudly
when a tool reaches for an endpoint nobody seeded — far more useful
than returning ``{}``.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from kinetix_insights.clients.kinetix_http_client import KinetixHttpError
from kinetix_insights.clients.user_context import UserContext


@dataclass(frozen=True)
class RecordedCall:
    """One downstream call captured by :class:`FakeKinetixHttpClient`."""

    method: str
    service: str
    path: str
    user: UserContext
    params: dict[str, Any] | None = None
    json: dict[str, Any] | None = None


@dataclass
class FakeKinetixHttpClient:
    """Behaviour-compatible double for :class:`KinetixHttpClient`."""

    recorded_calls: list[RecordedCall] = field(default_factory=list)
    _responses: dict[
        tuple[str, str, str], dict[str, Any] | list[Any] | Exception
    ] = field(default_factory=dict)

    def register_response(
        self,
        method: str,
        service: str,
        path: str,
        response: dict[str, Any] | list[Any] | Exception,
    ) -> None:
        """Seed a payload or exception for a future ``(method, service, path)``."""

        self._responses[(method.upper(), service, path)] = response

    def _resolve(
        self, method: str, service: str, path: str
    ) -> dict[str, Any] | list[Any]:
        key = (method.upper(), service, path)
        if key not in self._responses:
            raise KinetixHttpError(
                status_code=404,
                code="NOT_FOUND",
                message=f"no fake response registered for {method} {service}{path}",
                service=service,
                path=path,
            )
        value = self._responses[key]
        if isinstance(value, Exception):
            raise value
        return value

    async def get(
        self,
        service: str,
        path: str,
        *,
        params: dict[str, Any] | None,
        user: UserContext,
    ) -> dict[str, Any] | list[Any]:
        self.recorded_calls.append(
            RecordedCall(
                method="GET",
                service=service,
                path=path,
                user=user,
                params=params,
            )
        )
        return self._resolve("GET", service, path)

    async def post(
        self,
        service: str,
        path: str,
        *,
        json: dict[str, Any],
        user: UserContext,
    ) -> dict[str, Any] | list[Any]:
        self.recorded_calls.append(
            RecordedCall(
                method="POST",
                service=service,
                path=path,
                user=user,
                json=json,
            )
        )
        return self._resolve("POST", service, path)

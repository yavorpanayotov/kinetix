"""Async HTTP client for the gateway intraday-push endpoint (plan ai-v2.md § 7.7).

When the intraday-push pipeline fires — :class:`~kinetix_insights.push.
kafka_consumer.IntradayKafkaConsumer` decodes a ``risk.results`` event,
:class:`~kinetix_insights.push.threshold_evaluator.IntradayThresholdEvaluator`
detects a breach, and :class:`~kinetix_insights.push.push_generator.
IntradayPushGenerator` composes an :class:`~kinetix_insights.push.models.
IntradayPush` — the composed push must reach the UI. It gets there via
the gateway's cluster-internal route ``POST /internal/copilot/push``,
which fans the payload out over the ``/ws/copilot`` WebSocket.

:class:`HttpxGatewayPushClient` is the ``httpx`` wrapper that performs
that POST. It plugs into :class:`IntradayPushGenerator`'s ``sink`` seam —
the generator stays decoupled from HTTP transport, and this client owns
URL construction, the cluster-internal auth header, and serialisation.

Configuration
-------------
The target is resolved from two environment variables, mirroring how
:class:`~kinetix_insights.clients.kinetix_http_client.HttpxKinetixHttpClient`
reads ``POSITION_SERVICE_URL`` and friends:

* ``GATEWAY_INTERNAL_URL`` — base URL of the gateway's cluster-internal
  surface (e.g. ``http://gateway:8080``).
* ``COPILOT_INTERNAL_TOKEN`` — the shared secret the gateway's
  ``CopilotInternalAuth`` guard matches against. Sent on every request
  as the :data:`INTERNAL_TOKEN_HEADER` header.

:meth:`HttpxGatewayPushClient.from_env` builds a client from those vars
and returns ``None`` when either is unset — the caller (the FastAPI
lifespan) uses that to decide whether to wire a live gateway sink. In
``DEMO_MODE`` / CI the vars are unset, so no live HTTP call is ever made.

Wire shape
----------
The gateway DTO ``CopilotPushRequest`` expects snake_case field names.
:class:`IntradayPush` is a Pydantic model whose field names are already
snake_case, so ``model_dump(mode="json")`` yields exactly the body the
gateway deserialises — no manual key mapping required.
"""

from __future__ import annotations

import logging
import os
from dataclasses import dataclass

import httpx

from kinetix_insights.push.models import IntradayPush
from kinetix_insights.push.push_generator import PushSink

logger = logging.getLogger("kinetix_insights.push")

# Cluster-internal route the gateway exposes for intraday push. Matches
# `POST /internal/copilot/push` in gateway CopilotInternalRoutes.kt.
PUSH_PATH: str = "/internal/copilot/push"

# Shared-secret header the gateway's CopilotInternalAuth guard checks.
# Matches INTERNAL_REQUEST_TOKEN_HEADER in gateway CopilotInternalRoutes.kt.
INTERNAL_TOKEN_HEADER: str = "X-Internal-Token"

# Environment variables the production client is configured from.
_BASE_URL_ENV_VAR: str = "GATEWAY_INTERNAL_URL"
_TOKEN_ENV_VAR: str = "COPILOT_INTERNAL_TOKEN"


@dataclass
class GatewayPushError(Exception):
    """Raised when the gateway rejects an intraday-push POST.

    Attributes:
        status_code: HTTP status code returned by the gateway.
        message: Best-effort human-readable description (the response body).
    """

    status_code: int
    message: str

    def __post_init__(self) -> None:
        super().__init__(
            f"gateway rejected intraday push ({self.status_code}): {self.message}"
        )


class HttpxGatewayPushClient:
    """POSTs an :class:`IntradayPush` to the gateway internal endpoint.

    Construction is cheap and side-effect free — no network call. Each
    :meth:`push` opens a short-lived ``httpx.AsyncClient``, mirroring
    :class:`~kinetix_insights.clients.kinetix_http_client.
    HttpxKinetixHttpClient`. ``transport`` is exposed purely so unit
    tests can plug in :class:`httpx.MockTransport` and exercise the full
    request-construction path without a network.
    """

    def __init__(
        self,
        *,
        base_url: str,
        internal_token: str,
        transport: httpx.AsyncBaseTransport | None = None,
        timeout: float = 5.0,
    ) -> None:
        """Construct the client.

        Args:
            base_url: Base URL of the gateway's cluster-internal surface
                (e.g. ``http://gateway:8080``). A trailing slash is
                trimmed so the joined path is well-formed.
            internal_token: Shared secret sent on the
                :data:`INTERNAL_TOKEN_HEADER` header; must match the
                gateway's ``COPILOT_INTERNAL_TOKEN``.
            transport: Optional ``httpx`` transport — tests inject a
                :class:`httpx.MockTransport`; production leaves it
                ``None`` for the default network transport.
            timeout: Per-request timeout in seconds.
        """

        self._base_url = base_url.rstrip("/")
        self._internal_token = internal_token
        self._transport = transport
        self._timeout = timeout

    @classmethod
    def from_env(
        cls,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
        timeout: float = 5.0,
    ) -> HttpxGatewayPushClient | None:
        """Build a client from the environment, or ``None`` if unconfigured.

        Reads ``GATEWAY_INTERNAL_URL`` and ``COPILOT_INTERNAL_TOKEN``.
        Returns ``None`` when either is unset — DEMO_MODE, CI, and the
        app acceptance tests leave them unset, so the caller wires no
        live gateway sink and no real HTTP call is ever attempted.
        """

        base_url = os.environ.get(_BASE_URL_ENV_VAR, "").strip()
        token = os.environ.get(_TOKEN_ENV_VAR, "").strip()
        if not base_url or not token:
            logger.info(
                "gateway push client not configured "
                "(set %s and %s to enable intraday gateway dispatch)",
                _BASE_URL_ENV_VAR,
                _TOKEN_ENV_VAR,
            )
            return None
        return cls(
            base_url=base_url,
            internal_token=token,
            transport=transport,
            timeout=timeout,
        )

    async def push(self, push: IntradayPush) -> None:
        """POST ``push`` to the gateway's ``/internal/copilot/push`` route.

        The payload is serialised with ``model_dump(mode="json")`` — the
        snake_case shape the gateway's ``CopilotPushRequest`` DTO
        expects. The cluster-internal token is stamped on the
        :data:`INTERNAL_TOKEN_HEADER` header so the gateway's
        ``CopilotInternalAuth`` guard admits the call.

        Raises:
            GatewayPushError: when the gateway responds with a non-2xx
                status (e.g. ``403`` for a wrong/missing token).
        """

        url = f"{self._base_url}{PUSH_PATH}"
        body = push.model_dump(mode="json")
        headers = {INTERNAL_TOKEN_HEADER: self._internal_token}
        async with httpx.AsyncClient(
            transport=self._transport, timeout=self._timeout
        ) as client:
            response = await client.post(url, json=body, headers=headers)
        if response.status_code >= 400:
            raise GatewayPushError(
                status_code=response.status_code,
                message=response.text,
            )

    def as_sink(self) -> PushSink:
        """Return this client as an :class:`IntradayPushGenerator` ``sink``.

        :meth:`push` already satisfies the ``PushSink`` callable
        signature (``async def (IntradayPush) -> None``); this method
        names the intent at the wiring site so the FastAPI lifespan reads
        clearly.
        """

        return self.push

"""In-process MCP server scaffold and tool registry for the Kinetix Copilot.

This module exposes two public surfaces:

* :func:`build_mcp_server` — constructs a named :class:`FastMCP`
  instance (``"kinetix-copilot"``) that the FastAPI lifespan attaches
  to ``app.state.mcp_server``. Bare-bones by design: the lifespan and
  ``/mcp/health`` route can be wired and tested in isolation, and the
  no-arg signature is preserved so callers that just need a discovery
  surface don't have to construct a ``UserContext`` /
  ``KinetixHttpClient`` upfront.
* :func:`register_tools` — adds the 10 PR-2 read tools to a
  ``FastMCP`` instance. The route handler that owns a per-request
  ``UserContext`` calls this to expose the tools to the MCP client,
  closing over ``http`` / ``user`` / ``now`` so each adapter
  preserves the user-facing keyword args only. Re-registering the
  same tool name is idempotent: FastMCP's tool manager warns and
  keeps the existing entry rather than raising, so callers can call
  this function multiple times on the same server without coordinating.

:data:`MCP_PORT` (8096) is exported as the canonical internal bind
port; Docker Compose, Helm, and the eventual ``mcp.run("streamable-http",
port=...)`` call all import it rather than hard-coding the number.

The scaffold deliberately stops short of starting a separate uvicorn
process bound to ``MCP_PORT`` inside the lifespan — that complicates
``TestClient``-based tests and isn't needed until the first MCP tool
actually has a consumer. The FastMCP instance is held ready on
``app.state`` and surfaced via ``/mcp/health`` (see
:mod:`kinetix_insights.mcp.health`).
"""

from __future__ import annotations

import functools
import time
from datetime import datetime
from typing import Any, Awaitable, Callable, Literal

from mcp.server.fastmcp import FastMCP

from kinetix_insights.clients.kinetix_http_client import (
    KinetixHttpClient,
    KinetixHttpError,
)
from kinetix_insights.clients.user_context import UserContext
from kinetix_insights.metrics.copilot_metrics import (
    COPILOT_TOOL_CALL_DURATION_SECONDS,
    COPILOT_TOOL_NOT_FOUND_TOTAL,
)
from kinetix_insights.mcp.tools.get_active_alerts import get_active_alerts
from kinetix_insights.mcp.tools.get_book_var import get_book_var
from kinetix_insights.mcp.tools.get_correlation_matrix import (
    get_correlation_matrix,
)
from kinetix_insights.mcp.tools.get_greeks_summary import get_greeks_summary
from kinetix_insights.mcp.tools.get_limit_utilisation import (
    get_limit_utilisation,
)
from kinetix_insights.mcp.tools.get_market_data_snapshot import (
    get_market_data_snapshot,
)
from kinetix_insights.mcp.tools.get_pnl_attribution import get_pnl_attribution
from kinetix_insights.mcp.tools.get_positions import get_positions
from kinetix_insights.mcp.tools.get_stress_scenarios import get_stress_scenarios
from kinetix_insights.mcp.tools.get_vol_surface import get_vol_surface

MCP_PORT: int = 8096
"""Canonical internal bind port for the in-process MCP server."""

MCP_SERVER_NAME: str = "kinetix-copilot"
"""Name advertised by the FastMCP instance to MCP clients."""


def _instrument_tool(
    name: str, fn: Callable[..., Awaitable[dict[str, Any]]]
) -> Callable[..., Awaitable[dict[str, Any]]]:
    """Wrap a tool adapter so each call feeds the ``copilot_*`` metrics.

    Every call observes :data:`COPILOT_TOOL_CALL_DURATION_SECONDS`
    (labelled by tool ``name``) regardless of outcome, and a
    :class:`~kinetix_insights.clients.kinetix_http_client.
    KinetixHttpError` with a ``NOT_FOUND`` code bumps
    :data:`COPILOT_TOOL_NOT_FOUND_TOTAL` before being re-raised
    unchanged.

    ``functools.wraps`` copies ``__wrapped__`` / ``__doc__`` /
    ``__annotations__`` so FastMCP — which resolves the tool's input
    schema via :func:`inspect.signature` (it follows ``__wrapped__``)
    — sees the original keyword-only signature, not ``*args/**kwargs``.
    The instrumentation is therefore transparent to the MCP client.
    """

    @functools.wraps(fn)
    async def _wrapped(*args: Any, **kwargs: Any) -> dict[str, Any]:
        started = time.monotonic()
        try:
            return await fn(*args, **kwargs)
        except KinetixHttpError as exc:
            if exc.code == "NOT_FOUND":
                COPILOT_TOOL_NOT_FOUND_TOTAL.inc()
            raise
        finally:
            COPILOT_TOOL_CALL_DURATION_SECONDS.labels(tool=name).observe(
                time.monotonic() - started
            )

    return _wrapped


def build_mcp_server() -> FastMCP:
    """Build the in-process FastMCP instance for the Kinetix Copilot.

    Returns a fresh, tool-less FastMCP named ``kinetix-copilot``. Tool
    registration is a separate step (:func:`register_tools`) so this
    factory stays cheap to call from the FastAPI lifespan and from
    discovery-only tests that don't need a wired-in ``UserContext`` or
    ``KinetixHttpClient``.
    """
    return FastMCP(MCP_SERVER_NAME)


def register_tools(
    mcp: FastMCP,
    *,
    http: KinetixHttpClient,
    user: UserContext,
    now: Callable[[], datetime] | None = None,
) -> list[str]:
    """Register the 10 PR-2 read tools on ``mcp`` and return their names.

    Each tool gets a thin async adapter whose signature exposes ONLY
    the user-facing keyword arguments. The adapter closes over
    ``http`` / ``user`` / ``now`` from this function's parameters and
    awaits the underlying tool. FastMCP infers the JSON ``inputSchema``
    from the adapter's type hints — the collaborator parameters are
    therefore hidden from the MCP client, which is what we want.

    Adapter docstrings feed FastMCP's tool ``description``; we copy a
    one-liner per tool that mirrors the underlying implementation's
    purpose. Return-type annotations are ``dict[str, Any]`` across the
    board so FastMCP creates a generic structured-output model for
    each tool — hand-crafting per-tool output schemas is deferred (see
    ``plans/ai-v2.md`` § PR 2).

    Re-registering the same tool name on the same ``FastMCP`` instance
    is idempotent — FastMCP's tool manager warns and keeps the
    existing entry rather than raising. Callers can therefore invoke
    this function multiple times (e.g. per-request) without
    coordinating.

    Args:
        mcp: The MCP server to register tools on.
        http: The HTTP client every tool dispatches upstream calls
            through. Closed over by each adapter.
        user: The caller's identity / book scopes. Closed over by
            each adapter so book-scoped tools (e.g. ``get_book_var``)
            can fail-closed without re-deriving the user.
        now: Injectable clock forwarded to every tool. Defaults to
            ``datetime.now(timezone.utc)`` inside each tool when
            ``None``.

    Returns:
        Tool names in registration order. The set should match the
        10 PR-2 read tools; callers can assert on this for discovery
        checks without listing the server.
    """

    async def _get_book_var(
        *,
        book_id: str,
        as_of: str | None = None,
        method: str | None = None,
    ) -> dict[str, Any]:
        """Return the latest VaR for ``book_id`` with provenance citation."""

        return await get_book_var(
            book_id=book_id,
            as_of=as_of,
            method=method,
            user=user,
            http=http,
            now=now,
        )

    async def _get_positions(
        *,
        book_id: str,
        instrument_id: str | None = None,
        asset_class: str | None = None,
        top_n: int | None = None,
    ) -> dict[str, Any]:
        """Return the current positions for ``book_id`` with provenance citation."""

        return await get_positions(
            book_id=book_id,
            instrument_id=instrument_id,
            asset_class=asset_class,
            top_n=top_n,
            user=user,
            http=http,
            now=now,
        )

    async def _get_greeks_summary(
        *,
        book_id: str,
        as_of: str | None = None,
        underlier: str | None = None,
    ) -> dict[str, Any]:
        """Return aggregate + by-underlier Greeks for ``book_id`` with citation."""

        return await get_greeks_summary(
            book_id=book_id,
            as_of=as_of,
            underlier=underlier,
            user=user,
            http=http,
            now=now,
        )

    async def _get_limit_utilisation(
        *,
        book_id: str,
        limit_type: str | None = None,
    ) -> dict[str, Any]:
        """Return the BOOK-level limit definitions for ``book_id`` with citation."""

        return await get_limit_utilisation(
            book_id=book_id,
            limit_type=limit_type,
            user=user,
            http=http,
            now=now,
        )

    async def _get_pnl_attribution(
        *,
        book_id: str,
        date: str | None = None,
        period: Literal["daily", "intraday"] | None = None,
    ) -> dict[str, Any]:
        """Return P&L attribution for ``book_id`` with provenance citation."""

        return await get_pnl_attribution(
            book_id=book_id,
            date=date,
            period=period,
            user=user,
            http=http,
            now=now,
        )

    async def _get_vol_surface(
        *,
        underlier: str,
        as_of: str | None = None,
    ) -> dict[str, Any]:
        """Return the volatility surface for ``underlier`` with provenance citation."""

        return await get_vol_surface(
            underlier=underlier,
            as_of=as_of,
            user=user,
            http=http,
            now=now,
        )

    async def _get_stress_scenarios(
        *,
        book_id: str,
        scenarios: list[str] | None = None,
    ) -> dict[str, Any]:
        """Return worst-first named-scenario stress impacts for ``book_id``."""

        return await get_stress_scenarios(
            book_id=book_id,
            scenarios=scenarios,
            user=user,
            http=http,
            now=now,
        )

    async def _get_correlation_matrix(
        *,
        asset_pair: list[str] | None = None,
        as_of: str | None = None,
        lookback_days: int | None = None,
    ) -> dict[str, Any]:
        """Return the latest correlation matrix for ``asset_pair`` (or default labels).

        The adapter narrows the underlying tool's
        ``list[str] | tuple[str, str] | None`` to ``list[str] | None``
        so FastMCP can infer a clean JSON schema. The underlying tool
        accepts both shapes — list is the JSON-friendly canonical form.
        """

        return await get_correlation_matrix(
            asset_pair=asset_pair,
            as_of=as_of,
            lookback_days=lookback_days,
            user=user,
            http=http,
            now=now,
        )

    async def _get_active_alerts(
        *,
        book_id: str,
        severity: str | None = None,
        since: str | None = None,
    ) -> dict[str, Any]:
        """Return the active alerts for ``book_id`` with provenance citation."""

        return await get_active_alerts(
            book_id=book_id,
            severity=severity,
            since=since,
            user=user,
            http=http,
            now=now,
        )

    async def _get_market_data_snapshot(
        *,
        instruments: list[str],
        fields: list[str] | None = None,
    ) -> dict[str, Any]:
        """Return latest quotes (with day-over-day change) for ``instruments``."""

        return await get_market_data_snapshot(
            instruments=instruments,
            fields=fields,
            user=user,
            http=http,
            now=now,
        )

    # Registration order is intentional: bookend cohort first (the
    # most common chat surface), reference-data tools last. The list
    # is what we return so callers can assert deterministically.
    registrations: list[tuple[str, Any, str]] = [
        ("get_book_var", _get_book_var, _get_book_var.__doc__ or ""),
        ("get_positions", _get_positions, _get_positions.__doc__ or ""),
        (
            "get_greeks_summary",
            _get_greeks_summary,
            _get_greeks_summary.__doc__ or "",
        ),
        (
            "get_limit_utilisation",
            _get_limit_utilisation,
            _get_limit_utilisation.__doc__ or "",
        ),
        (
            "get_pnl_attribution",
            _get_pnl_attribution,
            _get_pnl_attribution.__doc__ or "",
        ),
        ("get_vol_surface", _get_vol_surface, _get_vol_surface.__doc__ or ""),
        (
            "get_stress_scenarios",
            _get_stress_scenarios,
            _get_stress_scenarios.__doc__ or "",
        ),
        (
            "get_correlation_matrix",
            _get_correlation_matrix,
            (_get_correlation_matrix.__doc__ or "").strip().splitlines()[0],
        ),
        (
            "get_active_alerts",
            _get_active_alerts,
            _get_active_alerts.__doc__ or "",
        ),
        (
            "get_market_data_snapshot",
            _get_market_data_snapshot,
            _get_market_data_snapshot.__doc__ or "",
        ),
    ]

    for name, fn, description in registrations:
        mcp.add_tool(
            _instrument_tool(name, fn),
            name=name,
            description=description.strip(),
        )

    return [name for name, _, _ in registrations]

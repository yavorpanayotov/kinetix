"""Scaffold tests for the in-process MCP server.

These tests pin down the smallest viable contract for the MCP scaffold
introduced in ai-v2 PR 1 (checkbox 1.7):

* A factory ``build_mcp_server()`` returns a :class:`FastMCP` instance
  named ``"kinetix-copilot"`` with no tools registered yet (tools land
  in PR 2).
* A module-level constant ``MCP_PORT`` pins the internal bind port
  (8096) so other modules (Compose, Helm, eventual ``mcp.run`` call)
  can import a single source of truth.
* The FastAPI app exposes ``GET /mcp/health`` returning
  ``{"status": "ok", "tools_registered": 0}`` while the scaffold is
  toolless. The lifespan attaches the FastMCP instance to
  ``app.state.mcp_server`` so later PRs can reach it.

``DEMO_MODE=true`` is forced inside the helper (and the acceptance
command sets it externally) so the existing lifespan picks the canned
insight client — these tests do not exercise the live SDK path.
"""

from __future__ import annotations

import os

import pytest
from fastapi.testclient import TestClient
from mcp.server.fastmcp import FastMCP

pytestmark = pytest.mark.unit


def _build_client() -> TestClient:
    """Construct a TestClient with DEMO_MODE forced on.

    Mirrors the helper in ``test_var_explainer_acceptance.py`` so the
    existing insight client lifespan picks ``CannedInsightClient`` and
    no external dependencies are touched.
    """
    os.environ["DEMO_MODE"] = "true"
    from kinetix_insights.app import app  # imported after env is set

    return TestClient(app)


def test_build_mcp_server_returns_named_fastmcp_instance() -> None:
    """``build_mcp_server()`` returns a FastMCP named ``kinetix-copilot``."""
    from kinetix_insights.mcp.server import build_mcp_server

    server = build_mcp_server()
    assert isinstance(server, FastMCP)
    assert server.name == "kinetix-copilot"


def test_mcp_port_constant_is_8096() -> None:
    """``MCP_PORT`` pins the internal bind port for the MCP server."""
    from kinetix_insights.mcp.server import MCP_PORT

    assert MCP_PORT == 8096


def test_mcp_health_route_reports_zero_tools_in_scaffold() -> None:
    """``GET /mcp/health`` returns OK and zero registered tools."""
    with _build_client() as client:
        response = client.get("/mcp/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok", "tools_registered": 0}


def test_mcp_server_is_attached_to_app_state() -> None:
    """The lifespan attaches the FastMCP instance to ``app.state``."""
    with _build_client() as client:
        # Touch any route so the lifespan has finished starting.
        client.get("/health")
        server = client.app.state.mcp_server  # type: ignore[union-attr]

    assert isinstance(server, FastMCP)
    assert server.name == "kinetix-copilot"

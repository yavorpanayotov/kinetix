"""In-process MCP server scaffold for the Kinetix Copilot.

This module is the v2 PR 1 scaffold (plan checkbox 1.7). It provides:

* :func:`build_mcp_server` — constructs a named :class:`FastMCP`
  instance (``"kinetix-copilot"``) that the FastAPI lifespan attaches
  to ``app.state.mcp_server``. No tools are registered yet — the read
  tools land one-per-checkbox in PR 2 (see ``plans/ai-v2.md`` § PR 2).
* :data:`MCP_PORT` — the canonical internal bind port (8096). All
  callers (Docker Compose, Helm chart, the eventual
  ``mcp.run("streamable-http", port=...)`` call) import this constant
  rather than hard-coding the number so there is a single source of
  truth.

The scaffold deliberately stops short of starting a separate uvicorn
process bound to ``MCP_PORT`` inside the lifespan: doing that
complicates ``TestClient``-based tests and isn't needed until the
first MCP tool actually has a consumer. The FastMCP instance is held
ready on ``app.state`` and surfaced via ``/mcp/health`` (see
:mod:`kinetix_insights.mcp.health`).
"""

from mcp.server.fastmcp import FastMCP

MCP_PORT: int = 8096
"""Canonical internal bind port for the in-process MCP server."""

MCP_SERVER_NAME: str = "kinetix-copilot"
"""Name advertised by the FastMCP instance to MCP clients."""


def build_mcp_server() -> FastMCP:
    """Build the in-process FastMCP instance for the Kinetix Copilot.

    Returns a fresh, tool-less FastMCP named ``kinetix-copilot``. Tool
    registration happens in plan PR 2 (``plans/ai-v2.md`` checkboxes
    2.1 – 2.11); this scaffold intentionally returns the bare instance
    so the lifespan and health route can be wired and tested in
    isolation.
    """
    return FastMCP(MCP_SERVER_NAME)

"""Health route for the in-process MCP server scaffold.

Exposes ``GET /mcp/health`` on the main FastAPI app (not on the
internal MCP port). The probe reports the live count of tools
registered against the :class:`FastMCP` instance stored on
``app.state.mcp_server``, which lets ops and tests confirm the
scaffold is wired and — once PR 2 lands — that each tool has
registered as expected.

The route lives on the main app rather than on the eventual port-8096
streamable-HTTP MCP app so it can be reached via the same gateway and
testing surface as the existing ``/health`` and ``/ready`` probes.
"""

from fastapi import APIRouter, Request

router = APIRouter(tags=["mcp"])


@router.get("/mcp/health")
async def mcp_health(request: Request) -> dict[str, object]:
    """Report MCP scaffold liveness and registered-tool count."""
    server = request.app.state.mcp_server
    tools = await server.list_tools()
    return {"status": "ok", "tools_registered": len(tools)}

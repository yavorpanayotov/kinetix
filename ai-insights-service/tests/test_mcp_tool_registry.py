"""Integration tests for the MCP tool registry.

Plan checkbox 2.11 — wire all 10 PR-2 tools into the in-process MCP
server so an MCP SDK client can discover them by name, list their
input schemas, and round-trip a call against a fake
``KinetixHttpClient``.

The tests pin down the registry contract:

* :func:`register_tools(mcp, http=..., user=..., now=...)` adds one
  adapter per tool and returns the registered names in order.
* The MCP SDK can list each tool, every tool has a non-empty
  description and a non-empty input-schema ``properties`` map.
* The adapter for ``get_book_var`` exposes the user-facing args
  (``book_id``, ``as_of``, ``method``) and does NOT leak the internal
  ``user`` / ``http`` / ``now`` collaborators into the input schema.
* Calling a tool via ``mcp.call_tool`` runs through the adapter,
  reaches the fake HTTP client, and returns a structured-content
  dict with the expected keys.
* Re-running ``register_tools`` on the same ``FastMCP`` instance is
  idempotent (FastMCP's tool manager warns-and-keeps on duplicate
  names) and never raises — this matches the documented duplicate-tool
  behaviour of the SDK.

No network, no live ``KinetixHttpClient`` — everything is wired
against :class:`FakeKinetixHttpClient`.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

import pytest
from mcp.server.fastmcp import FastMCP

from kinetix_insights.clients.user_context import UserContext
from kinetix_insights.mcp.server import build_mcp_server, register_tools
from tests.fakes.fake_kinetix_http_client import FakeKinetixHttpClient

pytestmark = pytest.mark.unit


# ---------------------------------------------------------------------------
# Constants and fixtures
# ---------------------------------------------------------------------------


_EXPECTED_TOOLS: frozenset[str] = frozenset(
    {
        "get_book_var",
        "get_positions",
        "get_greeks_summary",
        "get_limit_utilisation",
        "get_pnl_attribution",
        "get_vol_surface",
        "get_stress_scenarios",
        "get_correlation_matrix",
        "get_active_alerts",
        "get_market_data_snapshot",
    }
)

_DEFAULT_USER = UserContext(user_id="trader-1", books=("fx-main", "rates-emea"))


def _fixed_now() -> datetime:
    """Pinned clock so freshness arithmetic is deterministic."""

    return datetime(2026, 5, 19, 16, 0, 0, tzinfo=timezone.utc)


def _sample_var_response() -> dict[str, Any]:
    """A representative upstream ``VaRResultResponse`` payload."""

    return {
        "bookId": "fx-main",
        "calculationType": "PARAMETRIC",
        "confidenceLevel": "CL_95",
        "varValue": "1234567.89",
        "expectedShortfall": "1500000.00",
        "componentBreakdown": [
            {
                "assetClass": "FX",
                "varContribution": "1000000.00",
                "percentageOfTotal": "81.00",
            }
        ],
        "calculatedAt": "2026-05-19T08:00:00Z",
        "marketDataComplete": True,
    }


def _sample_position_response() -> list[dict[str, Any]]:
    """A representative upstream ``List[PositionResponse]`` payload."""

    return [
        {
            "bookId": "fx-main",
            "instrumentId": "EURUSD",
            "assetClass": "FX",
            "quantity": "1000000.00",
            "averageCost": {"amount": "1.0850", "currency": "USD"},
            "marketPrice": {"amount": "1.0900", "currency": "USD"},
            "marketValue": {"amount": "1090000.00", "currency": "USD"},
            "unrealizedPnl": {"amount": "5000.00", "currency": "USD"},
            "realizedPnl": {"amount": "1200.00", "currency": "USD"},
            "instrumentType": "FX_SPOT",
            "strategyId": None,
            "strategyType": None,
            "strategyName": None,
        }
    ]


def _sample_vol_surface() -> dict[str, Any]:
    """A representative upstream ``VolSurfaceResponse`` payload."""

    return {
        "instrumentId": "EURUSD",
        "asOfDate": "2026-05-19T08:00:00Z",
        "points": [
            {"strike": 1.08, "maturityDays": 30, "impliedVol": 13.0},
            {"strike": 1.10, "maturityDays": 30, "impliedVol": 12.0},
            {"strike": 1.12, "maturityDays": 30, "impliedVol": 13.2},
        ],
        "source": "MARKET",
        "lastUpdatedAt": "2026-05-19T08:00:00Z",
    }


def _build_registered_server() -> tuple[FastMCP, FakeKinetixHttpClient, list[str]]:
    """Construct a fresh FastMCP with all 10 tools registered.

    Returns the server, the fake HTTP client (so callers can seed
    upstream responses), and the list of registered tool names.
    """

    mcp = build_mcp_server()
    fake = FakeKinetixHttpClient()
    names = register_tools(mcp, http=fake, user=_DEFAULT_USER, now=_fixed_now)
    return mcp, fake, names


# ---------------------------------------------------------------------------
# Registration contract
# ---------------------------------------------------------------------------


def test_register_tools_returns_all_ten_names() -> None:
    """``register_tools`` returns the 10 expected tool names."""
    mcp = build_mcp_server()
    fake = FakeKinetixHttpClient()

    names = register_tools(mcp, http=fake, user=_DEFAULT_USER, now=_fixed_now)

    assert set(names) == set(_EXPECTED_TOOLS)
    assert len(names) == 10


@pytest.mark.asyncio
async def test_register_tools_makes_tools_discoverable() -> None:
    """``mcp.list_tools()`` surfaces every registered tool by name."""
    mcp, _, _ = _build_registered_server()

    tools = await mcp.list_tools()

    assert {tool.name for tool in tools} == set(_EXPECTED_TOOLS)


@pytest.mark.asyncio
async def test_each_tool_has_description() -> None:
    """Every registered tool has a non-empty description for MCP clients."""
    mcp, _, _ = _build_registered_server()

    tools = await mcp.list_tools()

    for tool in tools:
        assert tool.description is not None, f"{tool.name} missing description"
        assert tool.description.strip(), f"{tool.name} has blank description"


@pytest.mark.asyncio
async def test_each_tool_has_input_schema() -> None:
    """Every registered tool has a non-empty inputSchema.properties map."""
    mcp, _, _ = _build_registered_server()

    tools = await mcp.list_tools()

    for tool in tools:
        assert isinstance(tool.inputSchema, dict), (
            f"{tool.name} inputSchema must be a dict, got {type(tool.inputSchema).__name__}"
        )
        properties = tool.inputSchema.get("properties")
        assert isinstance(properties, dict), (
            f"{tool.name} inputSchema.properties must be a dict"
        )
        assert properties, f"{tool.name} inputSchema.properties is empty"


@pytest.mark.asyncio
async def test_get_book_var_schema_includes_book_id_and_omits_collaborators() -> None:
    """``get_book_var`` exposes ``book_id`` and hides ``user``/``http``/``now``."""
    mcp, _, _ = _build_registered_server()

    tools = await mcp.list_tools()
    by_name = {tool.name: tool for tool in tools}
    schema = by_name["get_book_var"].inputSchema
    properties = schema["properties"]

    assert "book_id" in properties
    assert "user" not in properties
    assert "http" not in properties
    assert "now" not in properties


# ---------------------------------------------------------------------------
# Call round-tripping
# ---------------------------------------------------------------------------


def _extract_structured(result: Any) -> dict[str, Any]:
    """Pull the structured-content dict out of ``mcp.call_tool``'s return.

    FastMCP returns ``(unstructured_content, structured_content)`` for
    tools whose return annotation is ``dict[str, Any]``. The
    structured_content is the dict we want to assert on.
    """

    if isinstance(result, dict):
        return result
    if isinstance(result, tuple) and len(result) == 2:
        _, structured = result
        assert isinstance(structured, dict), (
            f"expected structured-content dict, got {type(structured).__name__}"
        )
        return structured
    raise AssertionError(
        f"unexpected call_tool result shape: {type(result).__name__}: {result!r}"
    )


@pytest.mark.asyncio
async def test_calls_get_book_var_via_mcp_round_trips_to_fake() -> None:
    """Calling ``get_book_var`` via MCP reaches the fake HTTP client."""
    mcp, fake, _ = _build_registered_server()
    fake.register_response(
        "GET",
        "risk-orchestrator",
        "/api/v1/risk/var/fx-main",
        _sample_var_response(),
    )

    result = await mcp.call_tool("get_book_var", {"book_id": "fx-main"})

    # The upstream call was made with the right path/service.
    assert len(fake.recorded_calls) == 1
    call = fake.recorded_calls[0]
    assert call.method == "GET"
    assert call.service == "risk-orchestrator"
    assert call.path == "/api/v1/risk/var/fx-main"
    assert call.user == _DEFAULT_USER

    structured = _extract_structured(result)
    assert structured["total_var"] == pytest.approx(1234567.89)
    assert structured["confidence_level"] == "CL_95"


@pytest.mark.asyncio
async def test_calls_get_positions_via_mcp_returns_structured() -> None:
    """Calling ``get_positions`` via MCP returns the expected structured dict."""
    mcp, fake, _ = _build_registered_server()
    fake.register_response(
        "GET",
        "position",
        "/api/v1/books/fx-main/positions",
        _sample_position_response(),
    )

    result = await mcp.call_tool("get_positions", {"book_id": "fx-main"})

    assert len(fake.recorded_calls) == 1
    structured = _extract_structured(result)
    assert structured["total_count"] == 1
    assert structured["returned_count"] == 1
    assert isinstance(structured["positions"], list)
    assert structured["positions"][0]["instrument_id"] == "EURUSD"


@pytest.mark.asyncio
async def test_calls_get_vol_surface_via_mcp_without_book_scope() -> None:
    """``get_vol_surface`` runs without enforcing book ACL."""
    # User with NO books — vol surface is reference-data scoped, not book-scoped.
    no_book_user = UserContext(user_id="trader-2", books=())

    mcp = build_mcp_server()
    fake = FakeKinetixHttpClient()
    register_tools(mcp, http=fake, user=no_book_user, now=_fixed_now)

    fake.register_response(
        "GET",
        "volatility",
        "/api/v1/volatility/EURUSD/surface/latest",
        _sample_vol_surface(),
    )

    result = await mcp.call_tool("get_vol_surface", {"underlier": "EURUSD"})

    # No exception raised — succeeded without book scope.
    structured = _extract_structured(result)
    assert structured["underlier"] == "EURUSD"
    assert structured["tenors"], "expected at least one tenor"


@pytest.mark.asyncio
async def test_calls_get_active_alerts_propagates_acl_failure() -> None:
    """Book-scoped tool surfaces ``UNAUTHORIZED`` via MCP when out of scope."""
    mcp, _, _ = _build_registered_server()

    # ``out-of-scope`` is NOT in _DEFAULT_USER.books — the tool fails closed.
    from mcp.server.fastmcp.exceptions import ToolError

    with pytest.raises(ToolError) as exc_info:
        await mcp.call_tool(
            "get_active_alerts", {"book_id": "out-of-scope"}
        )

    # FastMCP wraps the underlying KinetixHttpError as a ToolError; the
    # underlying error code surfaces in the message.
    assert "UNAUTHORIZED" in str(exc_info.value)


# ---------------------------------------------------------------------------
# Idempotency
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_register_tools_is_idempotent() -> None:
    """Calling ``register_tools`` twice on the same server is idempotent.

    FastMCP's tool manager warns-and-keeps on duplicate names rather
    than raising, so a second registration pass must complete cleanly
    and leave the tool count unchanged.
    """

    mcp = build_mcp_server()
    fake = FakeKinetixHttpClient()

    first = register_tools(mcp, http=fake, user=_DEFAULT_USER, now=_fixed_now)
    second = register_tools(mcp, http=fake, user=_DEFAULT_USER, now=_fixed_now)

    assert set(first) == set(_EXPECTED_TOOLS)
    assert set(second) == set(_EXPECTED_TOOLS)

    tools = await mcp.list_tools()
    assert {tool.name for tool in tools} == set(_EXPECTED_TOOLS)
    assert len(tools) == 10

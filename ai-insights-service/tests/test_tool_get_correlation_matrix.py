"""Unit tests for the ``get_correlation_matrix`` MCP tool.

These tests stub the ``KinetixHttpClient`` with
:class:`tests.fakes.fake_kinetix_http_client.FakeKinetixHttpClient` and
assert that:

* the tool routes to the correct correlation-service endpoint
  (``GET /api/v1/correlations/latest``) with the upstream-required
  ``labels`` (CSV) and ``window`` (int) query parameters,
* the upstream ``CorrelationMatrixResponse`` row-major ``values``
  payload is decoded to per-pair correlations, with only the
  off-diagonal upper triangle returned and sorted by
  ``abs(correlation)`` descending,
* the tool is NOT book-scoped (correlations are reference data, not
  book-specific) — the ``user`` is forwarded to the HTTP client for
  audit but no ACL is enforced at the tool level,
* the v2 limitations are surfaced via citation ``quality_flags``:
  ``CORRELATION_BREAK_UNAVAILABLE`` always, and
  ``AS_OF_NOT_SUPPORTED`` when the caller supplied an ``as_of`` (the
  upstream exposes only ``/latest`` today),
* ``broken_pairs`` is always ``[]`` in v2 — the field exists so the
  AI gets a structurally-stable shape; population lands once a
  ``findAtOrBefore`` endpoint exists,
* input validation fails closed with ``BAD_REQUEST`` BEFORE any HTTP
  call: ``asset_pair`` wrong-length, duplicate labels, and
  non-positive ``lookback_days``,
* upstream ``NOT_FOUND`` / ``UPSTREAM_ERROR`` errors propagate
  unmodified, and a non-dict payload raises ``UPSTREAM_ERROR``.

No network, no FastMCP wiring — that lands in checkbox 2.11.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

import pytest

from kinetix_insights.clients.kinetix_http_client import KinetixHttpError
from kinetix_insights.clients.user_context import UserContext
from kinetix_insights.mcp.tools.get_correlation_matrix import (
    get_correlation_matrix,
)
from tests.fakes.fake_kinetix_http_client import FakeKinetixHttpClient

pytestmark = pytest.mark.unit


# ---------------------------------------------------------------------------
# Helpers and module-level fixture constants
# ---------------------------------------------------------------------------


_DEFAULT_USER = UserContext(user_id="trader-1", books=("fx-main", "rates-emea"))

_CORRELATION_PATH = "/api/v1/correlations/latest"
_SERVICE_SHORT = "correlation"


# Canonical 5x5 matrix matching the plan example. Off-diagonal abs
# values (upper triangle):
#   EURUSD/GBPUSD  =  0.65
#   EURUSD/USDJPY  = -0.30  (abs 0.30)
#   EURUSD/GOLD    =  0.10
#   EURUSD/SPX     =  0.20
#   GBPUSD/USDJPY  = -0.25  (abs 0.25)
#   GBPUSD/GOLD    =  0.05
#   GBPUSD/SPX     =  0.15
#   USDJPY/GOLD    = -0.10  (abs 0.10)
#   USDJPY/SPX     = -0.05  (abs 0.05)
#   GOLD/SPX       =  0.30
# Strongest abs == 0.65 (EURUSD/GBPUSD).
_CANONICAL_5X5: dict[str, Any] = {
    "labels": ["EURUSD", "GBPUSD", "USDJPY", "GOLD", "SPX"],
    "values": [
        1.00, 0.65, -0.30,  0.10, 0.20,
        0.65, 1.00, -0.25,  0.05, 0.15,
       -0.30,-0.25,  1.00, -0.10,-0.05,
        0.10, 0.05, -0.10,  1.00, 0.30,
        0.20, 0.15, -0.05,  0.30, 1.00,
    ],
    "windowDays": 60,
    "asOfDate": "2026-05-19T08:00:00Z",
    "method": "PEARSON",
    "lastUpdatedAt": "2026-05-19T08:00:00Z",
}

# Smaller 2x2 used for asset_pair scenarios.
_CANONICAL_2X2: dict[str, Any] = {
    "labels": ["EURUSD", "GBPUSD"],
    "values": [
        1.00, 0.65,
        0.65, 1.00,
    ],
    "windowDays": 60,
    "asOfDate": "2026-05-19T08:00:00Z",
    "method": "PEARSON",
    "lastUpdatedAt": "2026-05-19T08:00:00Z",
}

# A 3x3 matrix with a single label used to exercise the degenerate
# "no off-diagonal entries" branch for the headline citation value.
_SINGLE_LABEL_MATRIX: dict[str, Any] = {
    "labels": ["EURUSD"],
    "values": [1.00],
    "windowDays": 60,
    "asOfDate": "2026-05-19T08:00:00Z",
    "method": "PEARSON",
    "lastUpdatedAt": "2026-05-19T08:00:00Z",
}

# A bespoke 3x3 used in `test_get_correlation_matrix_extracts_correlation_from_row_major_values`
# to exercise the i*n+j extraction explicitly.
_ABC_MATRIX: dict[str, Any] = {
    "labels": ["A", "B", "C"],
    "values": [
        1.0, 0.5, -0.3,
        0.5, 1.0,  0.7,
       -0.3, 0.7,  1.0,
    ],
    "windowDays": 60,
    "asOfDate": "2026-05-19T08:00:00Z",
    "method": "PEARSON",
    "lastUpdatedAt": "2026-05-19T08:00:00Z",
}


def _fixed_now() -> datetime:
    # 10 hours after the upstream asOfDate of 08:00:00Z -> 36_000s.
    return datetime(2026, 5, 19, 18, 0, 0, tzinfo=timezone.utc)


# ---------------------------------------------------------------------------
# Endpoint and parameter wiring
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_correlation_matrix_calls_correct_endpoint_with_defaults() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE_SHORT, _CORRELATION_PATH, _CANONICAL_5X5)

    await get_correlation_matrix(
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert len(fake.recorded_calls) == 1
    call = fake.recorded_calls[0]
    assert call.method == "GET"
    assert call.service == _SERVICE_SHORT
    assert call.path == _CORRELATION_PATH
    assert call.params == {
        "labels": "EURUSD,GBPUSD,USDJPY,GOLD,SPX",
        "window": 60,
    }
    # ``window`` must be the int 60 — upstream takes an int, not a string.
    assert call.params is not None
    assert isinstance(call.params["window"], int)
    assert call.user == _DEFAULT_USER


@pytest.mark.asyncio
async def test_get_correlation_matrix_forwards_asset_pair_as_two_label_csv() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE_SHORT, _CORRELATION_PATH, _CANONICAL_2X2)

    await get_correlation_matrix(
        asset_pair=("EURUSD", "GBPUSD"),
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    call = fake.recorded_calls[0]
    assert call.params is not None
    assert call.params["labels"] == "EURUSD,GBPUSD"


@pytest.mark.asyncio
async def test_get_correlation_matrix_forwards_lookback_days_as_window() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE_SHORT, _CORRELATION_PATH, _CANONICAL_5X5)

    await get_correlation_matrix(
        lookback_days=120,
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    call = fake.recorded_calls[0]
    assert call.params is not None
    assert call.params["window"] == 120


# ---------------------------------------------------------------------------
# Response mapping
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_correlation_matrix_returns_off_diagonal_pairs_only() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE_SHORT, _CORRELATION_PATH, _CANONICAL_5X5)

    result = await get_correlation_matrix(
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    # 5*(5-1)/2 == 10 off-diagonal upper-triangle entries.
    assert len(result["pairs"]) == 10
    for pair in result["pairs"]:
        assert pair["a"] != pair["b"]


@pytest.mark.asyncio
async def test_get_correlation_matrix_pairs_sorted_by_abs_correlation_desc() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE_SHORT, _CORRELATION_PATH, _CANONICAL_5X5)

    result = await get_correlation_matrix(
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    abs_values = [abs(pair["correlation"]) for pair in result["pairs"]]
    assert abs_values == sorted(abs_values, reverse=True)
    # Strongest signal first — EURUSD/GBPUSD == 0.65.
    assert result["pairs"][0]["correlation"] == pytest.approx(0.65)


@pytest.mark.asyncio
async def test_get_correlation_matrix_extracts_correlation_from_row_major_values() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE_SHORT, _CORRELATION_PATH, _ABC_MATRIX)

    result = await get_correlation_matrix(
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    by_pair = {(pair["a"], pair["b"]): pair["correlation"] for pair in result["pairs"]}
    assert by_pair[("A", "B")] == pytest.approx(0.5)
    assert by_pair[("A", "C")] == pytest.approx(-0.3)
    assert by_pair[("B", "C")] == pytest.approx(0.7)


@pytest.mark.asyncio
async def test_get_correlation_matrix_broken_pairs_always_empty_in_v2() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE_SHORT, _CORRELATION_PATH, _CANONICAL_5X5)

    result = await get_correlation_matrix(
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    # v2 cannot compute correlation breaks (no prior-day endpoint).
    assert result["broken_pairs"] == []


# ---------------------------------------------------------------------------
# Citation
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_correlation_matrix_citation_always_flags_correlation_break_unavailable() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE_SHORT, _CORRELATION_PATH, _CANONICAL_5X5)

    result = await get_correlation_matrix(
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert "CORRELATION_BREAK_UNAVAILABLE" in result["citation"].quality_flags


@pytest.mark.asyncio
async def test_get_correlation_matrix_citation_flags_as_of_not_supported_when_supplied() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE_SHORT, _CORRELATION_PATH, _CANONICAL_5X5)

    result = await get_correlation_matrix(
        as_of="2026-05-18",
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert "CORRELATION_BREAK_UNAVAILABLE" in result["citation"].quality_flags
    assert "AS_OF_NOT_SUPPORTED" in result["citation"].quality_flags


@pytest.mark.asyncio
async def test_get_correlation_matrix_citation_does_not_flag_as_of_when_not_supplied() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE_SHORT, _CORRELATION_PATH, _CANONICAL_5X5)

    result = await get_correlation_matrix(
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert "AS_OF_NOT_SUPPORTED" not in result["citation"].quality_flags


@pytest.mark.asyncio
async def test_get_correlation_matrix_citation_result_value_is_max_abs_off_diagonal() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE_SHORT, _CORRELATION_PATH, _CANONICAL_5X5)

    result = await get_correlation_matrix(
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    citation = result["citation"]
    assert citation.result_field == "max_abs_off_diagonal_correlation"
    # max abs across the off-diagonal upper triangle is 0.65 (EURUSD/GBPUSD).
    assert citation.result_value == pytest.approx(0.65)


@pytest.mark.asyncio
async def test_get_correlation_matrix_citation_result_currency_is_none() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE_SHORT, _CORRELATION_PATH, _CANONICAL_5X5)

    result = await get_correlation_matrix(
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["citation"].result_currency is None


@pytest.mark.asyncio
async def test_get_correlation_matrix_citation_records_call_params() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE_SHORT, _CORRELATION_PATH, _CANONICAL_2X2)

    result = await get_correlation_matrix(
        asset_pair=("EURUSD", "GBPUSD"),
        as_of="2026-05-18",
        lookback_days=90,
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    citation = result["citation"]
    # Tuple inputs are normalised to lists for serialisable citation params.
    assert citation.params == {
        "asset_pair": ["EURUSD", "GBPUSD"],
        "as_of": "2026-05-18",
        "lookback_days": 90,
    }
    assert citation.tool == "get_correlation_matrix"
    assert citation.data_source == "correlation-service"
    # asOfDate 08:00:00Z, _fixed_now 18:00:00Z -> 10h staleness.
    assert citation.freshness_seconds == 10 * 3600
    assert citation.as_of_timestamp == datetime(
        2026, 5, 19, 8, 0, 0, tzinfo=timezone.utc
    )


# ---------------------------------------------------------------------------
# Input validation
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_correlation_matrix_asset_pair_wrong_length_raises_bad_request() -> None:
    fake = FakeKinetixHttpClient()

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_correlation_matrix(
            asset_pair=["EURUSD"],
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "BAD_REQUEST"
    assert excinfo.value.status_code == 400
    assert fake.recorded_calls == []


@pytest.mark.asyncio
async def test_get_correlation_matrix_asset_pair_duplicate_labels_raises_bad_request() -> None:
    fake = FakeKinetixHttpClient()

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_correlation_matrix(
            asset_pair=["EURUSD", "EURUSD"],
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "BAD_REQUEST"
    assert excinfo.value.status_code == 400
    assert fake.recorded_calls == []


@pytest.mark.asyncio
@pytest.mark.parametrize("lookback_days", [0, -1])
async def test_get_correlation_matrix_negative_lookback_days_raises_bad_request(
    lookback_days: int,
) -> None:
    fake = FakeKinetixHttpClient()

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_correlation_matrix(
            lookback_days=lookback_days,
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "BAD_REQUEST"
    assert excinfo.value.status_code == 400
    assert fake.recorded_calls == []


# ---------------------------------------------------------------------------
# Upstream error propagation
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_correlation_matrix_propagates_not_found() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE_SHORT,
        _CORRELATION_PATH,
        KinetixHttpError(
            status_code=404,
            code="NOT_FOUND",
            message="no matrix for labels/window",
            service=_SERVICE_SHORT,
            path=_CORRELATION_PATH,
        ),
    )

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_correlation_matrix(
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "NOT_FOUND"
    assert excinfo.value.status_code == 404


@pytest.mark.asyncio
async def test_get_correlation_matrix_propagates_upstream_error() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _SERVICE_SHORT,
        _CORRELATION_PATH,
        KinetixHttpError(
            status_code=502,
            code="UPSTREAM_ERROR",
            message="correlation service unreachable",
            service=_SERVICE_SHORT,
            path=_CORRELATION_PATH,
        ),
    )

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_correlation_matrix(
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "UPSTREAM_ERROR"
    assert excinfo.value.status_code == 502


@pytest.mark.asyncio
async def test_get_correlation_matrix_raises_upstream_error_on_non_dict_payload() -> None:
    fake = FakeKinetixHttpClient()
    # Upstream contract is a single object; a list is a shape drift.
    fake.register_response("GET", _SERVICE_SHORT, _CORRELATION_PATH, [_CANONICAL_5X5])

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_correlation_matrix(
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "UPSTREAM_ERROR"
    assert excinfo.value.status_code == 502


# ---------------------------------------------------------------------------
# ACL — this tool is NOT book-scoped
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_correlation_matrix_does_not_book_scope() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE_SHORT, _CORRELATION_PATH, _CANONICAL_5X5)

    # Correlations are reference data, not book-scoped — a user with no
    # matching books must still be able to query. UserContext is forwarded
    # so downstream services can run their own audit.
    user = UserContext(user_id="trader-1", books=("any-book",))

    await get_correlation_matrix(
        user=user,
        http=fake,
        now=_fixed_now,
    )

    assert len(fake.recorded_calls) == 1
    assert fake.recorded_calls[0].user == user


# ---------------------------------------------------------------------------
# Degenerate shapes
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_correlation_matrix_single_label_has_zero_headline() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response("GET", _SERVICE_SHORT, _CORRELATION_PATH, _SINGLE_LABEL_MATRIX)

    result = await get_correlation_matrix(
        asset_pair=None,
        # Force the upstream to be queried with a single label by passing
        # a one-element asset_pair would be a 400 — so we rely on the fake
        # registering a single-label response regardless of params. This
        # only exercises the headline-on-empty branch.
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["pairs"] == []
    # With no off-diagonal entries, headline falls back to 0.0.
    assert result["citation"].result_value == 0.0

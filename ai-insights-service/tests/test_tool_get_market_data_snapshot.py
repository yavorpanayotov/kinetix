"""Unit tests for the ``get_market_data_snapshot`` MCP tool.

These tests stub the ``KinetixHttpClient`` with
:class:`tests.fakes.fake_kinetix_http_client.FakeKinetixHttpClient` and
assert that:

* the tool fans out to the correct price-service endpoints
  (``GET /api/v1/prices/{id}/latest`` and
  ``GET /api/v1/prices/{id}/history``) once per unique instrument,
* the upstream ``PricePointResponse`` payloads are mapped onto the v2
  tool-output shape defined in ``docs/plans/ai-v2.md`` § PR 2 with
  ``change_abs`` / ``change_pct`` derived from the last historical
  point of the synthesised two-day window,
* missing or failing per-instrument calls are tolerated — a 404 on
  ``/latest`` lands in ``not_found``, any other upstream error lands in
  ``failed``, and history failures simply leave the change fields
  ``None`` without taking down the call,
* duplicate ``instruments`` are de-duped preserving first-seen order,
* an empty ``instruments`` list raises ``BAD_REQUEST`` BEFORE any HTTP
  call,
* a single :class:`Citation` describing the ``quote_count`` value is
  populated with the expected provenance fields and quality flags
  (always ``MULTI_CURRENCY_AGGREGATE``; conditionally
  ``PRIOR_CLOSE_PARTIAL`` / ``PRIOR_CLOSE_UNAVAILABLE`` /
  ``FIELDS_FILTER_NOT_APPLIED`` / ``PARTIAL_FAILURE``),
* the ``resolve_counterparty`` helper performs an exact-then-fuzzy
  case-insensitive match across ``legalName`` / ``shortName`` and
  refuses to guess on ambiguous matches.

No network, no FastMCP wiring — that lands in checkbox 2.11.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

import pytest

from kinetix_insights.clients.kinetix_http_client import KinetixHttpError
from kinetix_insights.clients.user_context import UserContext
from kinetix_insights.mcp.tools.get_market_data_snapshot import (
    get_market_data_snapshot,
    resolve_counterparty,
)
from tests.fakes.fake_kinetix_http_client import FakeKinetixHttpClient

pytestmark = pytest.mark.unit


# ---------------------------------------------------------------------------
# Fixture constants
# ---------------------------------------------------------------------------


_DEFAULT_USER = UserContext(user_id="trader-1", books=("fx-main", "rates-emea"))

_PRICE_SERVICE = "price"
_REFERENCE_DATA_SERVICE = "reference-data"
_COUNTERPARTIES_PATH = "/api/v1/counterparties"


def _fixed_now() -> datetime:
    return datetime(2026, 5, 19, 16, 0, 0, tzinfo=timezone.utc)


def _latest_path(instrument_id: str) -> str:
    return f"/api/v1/prices/{instrument_id}/latest"


def _history_path(instrument_id: str) -> str:
    return f"/api/v1/prices/{instrument_id}/history"


def _price_point(
    *,
    instrument_id: str,
    amount: str,
    currency: str = "USD",
    timestamp: str = "2026-05-19T15:55:00Z",
    source: str = "BLOOMBERG",
) -> dict[str, Any]:
    """Build a representative upstream ``PricePointResponse`` row."""

    return {
        "instrumentId": instrument_id,
        "price": {"amount": amount, "currency": currency},
        "timestamp": timestamp,
        "source": source,
    }


def _counterparty(
    *,
    counterparty_id: str,
    legal_name: str,
    short_name: str,
) -> dict[str, Any]:
    """Build a representative upstream ``CounterpartyResponse`` row."""

    return {
        "counterpartyId": counterparty_id,
        "legalName": legal_name,
        "shortName": short_name,
        "lei": None,
        "ratingSp": None,
        "ratingMoodys": None,
        "ratingFitch": None,
        "sector": "BANKS",
        "country": "US",
        "isFinancial": True,
        "pd1y": None,
        "lgd": 0.4,
        "cdsSpreadBps": None,
        "createdAt": "2026-01-01T00:00:00Z",
        "updatedAt": "2026-01-01T00:00:00Z",
    }


# Counterparty fixture covering the ambiguous, case, and short-name cases.
_COUNTERPARTIES: list[dict[str, Any]] = [
    _counterparty(
        counterparty_id="CP-GS",
        legal_name="Goldman Sachs International",
        short_name="GS",
    ),
    _counterparty(
        counterparty_id="CP-JPM",
        legal_name="JPMorgan Chase Bank",
        short_name="JPM",
    ),
    _counterparty(
        counterparty_id="CP-BARC",
        legal_name="Barclays Bank PLC",
        short_name="Barclays",
    ),
    _counterparty(
        counterparty_id="CP-BARCAP",
        legal_name="Barclays Capital Inc",
        short_name="BarCap",
    ),
]


def _seed_two_good_instruments(fake: FakeKinetixHttpClient) -> None:
    """Seed canonical ``AAPL`` + ``MSFT`` quotes with history."""

    fake.register_response(
        "GET",
        _PRICE_SERVICE,
        _latest_path("AAPL"),
        _price_point(instrument_id="AAPL", amount="180.50"),
    )
    fake.register_response(
        "GET",
        _PRICE_SERVICE,
        _history_path("AAPL"),
        [
            _price_point(
                instrument_id="AAPL",
                amount="178.00",
                timestamp="2026-05-17T20:00:00Z",
            ),
            _price_point(
                instrument_id="AAPL",
                amount="179.00",
                timestamp="2026-05-18T20:00:00Z",
            ),
        ],
    )
    fake.register_response(
        "GET",
        _PRICE_SERVICE,
        _latest_path("MSFT"),
        _price_point(
            instrument_id="MSFT",
            amount="420.10",
            timestamp="2026-05-19T15:50:00Z",
        ),
    )
    fake.register_response(
        "GET",
        _PRICE_SERVICE,
        _history_path("MSFT"),
        [
            _price_point(
                instrument_id="MSFT",
                amount="415.00",
                timestamp="2026-05-17T20:00:00Z",
            ),
            _price_point(
                instrument_id="MSFT",
                amount="418.00",
                timestamp="2026-05-18T20:00:00Z",
            ),
        ],
    )


# ---------------------------------------------------------------------------
# Endpoint and parameter wiring
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_calls_latest_for_each_instrument() -> None:
    fake = FakeKinetixHttpClient()
    _seed_two_good_instruments(fake)

    await get_market_data_snapshot(
        instruments=["AAPL", "MSFT"],
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    latest_calls = [
        call for call in fake.recorded_calls if call.path.endswith("/latest")
    ]
    assert len(latest_calls) == 2
    paths = {call.path for call in latest_calls}
    assert paths == {_latest_path("AAPL"), _latest_path("MSFT")}
    for call in latest_calls:
        assert call.method == "GET"
        assert call.service == _PRICE_SERVICE
        assert call.user == _DEFAULT_USER


@pytest.mark.asyncio
async def test_calls_history_with_correct_window_and_interval() -> None:
    fake = FakeKinetixHttpClient()
    _seed_two_good_instruments(fake)

    await get_market_data_snapshot(
        instruments=["AAPL"],
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    history_calls = [
        call for call in fake.recorded_calls if call.path.endswith("/history")
    ]
    assert len(history_calls) == 1
    history = history_calls[0]
    assert history.path == _history_path("AAPL")
    assert history.params is not None
    assert history.params.get("interval") == "1d"
    # today_utc = 2026-05-19T00:00:00; from = today - 2 days
    assert history.params.get("from") == "2026-05-17T00:00:00Z"
    assert history.params.get("to") == "2026-05-19T00:00:00Z"


# ---------------------------------------------------------------------------
# Quote shape and change computation
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_computes_change_abs_and_pct() -> None:
    fake = FakeKinetixHttpClient()
    _seed_two_good_instruments(fake)

    result = await get_market_data_snapshot(
        instruments=["AAPL"],
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    quote = result["quotes"][0]
    assert quote["instrument_id"] == "AAPL"
    assert quote["price"] == pytest.approx(180.50)
    assert quote["currency"] == "USD"
    assert quote["prior_close"] == pytest.approx(179.00)
    assert quote["change_abs"] == pytest.approx(1.50)
    assert quote["change_pct"] == pytest.approx(
        (1.50 / 179.00) * 100
    )


@pytest.mark.asyncio
async def test_change_none_when_history_empty() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _PRICE_SERVICE,
        _latest_path("AAPL"),
        _price_point(instrument_id="AAPL", amount="180.50"),
    )
    fake.register_response(
        "GET",
        _PRICE_SERVICE,
        _history_path("AAPL"),
        [],
    )

    result = await get_market_data_snapshot(
        instruments=["AAPL"],
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    quote = result["quotes"][0]
    assert quote["change_abs"] is None
    assert quote["change_pct"] is None
    assert quote["prior_close"] is None
    assert quote["prior_close_timestamp"] is None


@pytest.mark.asyncio
async def test_change_none_when_history_404() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _PRICE_SERVICE,
        _latest_path("AAPL"),
        _price_point(instrument_id="AAPL", amount="180.50"),
    )
    fake.register_response(
        "GET",
        _PRICE_SERVICE,
        _history_path("AAPL"),
        KinetixHttpError(
            status_code=404,
            code="NOT_FOUND",
            message="no history",
            service=_PRICE_SERVICE,
            path=_history_path("AAPL"),
        ),
    )

    result = await get_market_data_snapshot(
        instruments=["AAPL"],
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    quote = result["quotes"][0]
    assert quote["change_abs"] is None
    assert quote["change_pct"] is None
    assert quote["prior_close"] is None


# ---------------------------------------------------------------------------
# Per-instrument fault tolerance
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_per_instrument_not_found_tolerated() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _PRICE_SERVICE,
        _latest_path("AAPL"),
        _price_point(instrument_id="AAPL", amount="180.50"),
    )
    fake.register_response(
        "GET",
        _PRICE_SERVICE,
        _history_path("AAPL"),
        [],
    )
    fake.register_response(
        "GET",
        _PRICE_SERVICE,
        _latest_path("ZZZZ"),
        KinetixHttpError(
            status_code=404,
            code="NOT_FOUND",
            message="no quote",
            service=_PRICE_SERVICE,
            path=_latest_path("ZZZZ"),
        ),
    )

    result = await get_market_data_snapshot(
        instruments=["AAPL", "ZZZZ"],
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert len(result["quotes"]) == 1
    assert result["quotes"][0]["instrument_id"] == "AAPL"
    assert result["not_found"] == [
        {"instrument_id": "ZZZZ", "reason": "no_quote"}
    ]
    assert result["failed"] == []


@pytest.mark.asyncio
async def test_per_instrument_upstream_error_tolerated() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _PRICE_SERVICE,
        _latest_path("AAPL"),
        _price_point(instrument_id="AAPL", amount="180.50"),
    )
    fake.register_response(
        "GET",
        _PRICE_SERVICE,
        _history_path("AAPL"),
        [],
    )
    fake.register_response(
        "GET",
        _PRICE_SERVICE,
        _latest_path("BREAK"),
        KinetixHttpError(
            status_code=502,
            code="UPSTREAM_ERROR",
            message="price-service unreachable",
            service=_PRICE_SERVICE,
            path=_latest_path("BREAK"),
        ),
    )

    result = await get_market_data_snapshot(
        instruments=["AAPL", "BREAK"],
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert len(result["quotes"]) == 1
    assert result["failed"] == [
        {"instrument_id": "BREAK", "reason": "UPSTREAM_ERROR"}
    ]
    assert result["not_found"] == []


@pytest.mark.asyncio
async def test_dedupes_instruments() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _PRICE_SERVICE,
        _latest_path("AAPL"),
        _price_point(instrument_id="AAPL", amount="180.50"),
    )
    fake.register_response(
        "GET",
        _PRICE_SERVICE,
        _history_path("AAPL"),
        [],
    )

    result = await get_market_data_snapshot(
        instruments=["AAPL", "AAPL", "AAPL"],
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    latest_calls = [
        call for call in fake.recorded_calls if call.path.endswith("/latest")
    ]
    assert len(latest_calls) == 1
    assert result["requested_count"] == 1
    assert result["returned_count"] == 1


# ---------------------------------------------------------------------------
# Validation
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_empty_instruments_raises_bad_request_before_http() -> None:
    fake = FakeKinetixHttpClient()

    with pytest.raises(KinetixHttpError) as excinfo:
        await get_market_data_snapshot(
            instruments=[],
            user=_DEFAULT_USER,
            http=fake,
            now=_fixed_now,
        )

    assert excinfo.value.code == "BAD_REQUEST"
    assert excinfo.value.status_code == 400
    assert fake.recorded_calls == []


# ---------------------------------------------------------------------------
# Counts
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_returned_count_matches_quotes_length() -> None:
    fake = FakeKinetixHttpClient()
    _seed_two_good_instruments(fake)

    result = await get_market_data_snapshot(
        instruments=["AAPL", "MSFT"],
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["returned_count"] == len(result["quotes"])
    assert result["returned_count"] == 2
    assert result["requested_count"] == 2


# ---------------------------------------------------------------------------
# Citation population
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_citation_records_call_params() -> None:
    fake = FakeKinetixHttpClient()
    _seed_two_good_instruments(fake)

    result = await get_market_data_snapshot(
        instruments=["AAPL", "MSFT"],
        fields=None,
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    citation = result["citation"]
    assert citation.tool == "get_market_data_snapshot"
    assert citation.params == {
        "instruments": ["AAPL", "MSFT"],
        "fields": None,
    }


@pytest.mark.asyncio
async def test_citation_result_field_and_value() -> None:
    fake = FakeKinetixHttpClient()
    _seed_two_good_instruments(fake)

    result = await get_market_data_snapshot(
        instruments=["AAPL", "MSFT"],
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    citation = result["citation"]
    assert citation.result_field == "quote_count"
    assert citation.result_value == float(result["returned_count"])
    assert citation.data_source == "price-service"


@pytest.mark.asyncio
async def test_citation_result_currency_is_none() -> None:
    fake = FakeKinetixHttpClient()
    _seed_two_good_instruments(fake)

    result = await get_market_data_snapshot(
        instruments=["AAPL", "MSFT"],
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["citation"].result_currency is None


@pytest.mark.asyncio
async def test_citation_as_of_uses_latest_quote_timestamp() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _PRICE_SERVICE,
        _latest_path("AAPL"),
        _price_point(
            instrument_id="AAPL",
            amount="180.50",
            timestamp="2026-05-19T15:30:00Z",
        ),
    )
    fake.register_response(
        "GET",
        _PRICE_SERVICE,
        _history_path("AAPL"),
        [],
    )
    fake.register_response(
        "GET",
        _PRICE_SERVICE,
        _latest_path("MSFT"),
        _price_point(
            instrument_id="MSFT",
            amount="420.00",
            timestamp="2026-05-19T15:55:00Z",
        ),
    )
    fake.register_response(
        "GET",
        _PRICE_SERVICE,
        _history_path("MSFT"),
        [],
    )

    result = await get_market_data_snapshot(
        instruments=["AAPL", "MSFT"],
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    citation = result["citation"]
    # Latest timestamp across successful quotes is 15:55.
    assert citation.as_of_timestamp == datetime(
        2026, 5, 19, 15, 55, 0, tzinfo=timezone.utc
    )


@pytest.mark.asyncio
async def test_citation_as_of_falls_back_to_now_when_no_quotes() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _PRICE_SERVICE,
        _latest_path("ZZZZ"),
        KinetixHttpError(
            status_code=404,
            code="NOT_FOUND",
            message="no quote",
            service=_PRICE_SERVICE,
            path=_latest_path("ZZZZ"),
        ),
    )

    result = await get_market_data_snapshot(
        instruments=["ZZZZ"],
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert result["quotes"] == []
    assert result["citation"].as_of_timestamp == _fixed_now()
    assert result["citation"].freshness_seconds == 0


@pytest.mark.asyncio
async def test_citation_always_flags_multi_currency_aggregate() -> None:
    fake = FakeKinetixHttpClient()
    _seed_two_good_instruments(fake)

    result = await get_market_data_snapshot(
        instruments=["AAPL", "MSFT"],
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert "MULTI_CURRENCY_AGGREGATE" in result["citation"].quality_flags


@pytest.mark.asyncio
async def test_citation_flags_prior_close_partial_when_mixed() -> None:
    fake = FakeKinetixHttpClient()
    # AAPL has history; MSFT does not.
    fake.register_response(
        "GET",
        _PRICE_SERVICE,
        _latest_path("AAPL"),
        _price_point(instrument_id="AAPL", amount="180.50"),
    )
    fake.register_response(
        "GET",
        _PRICE_SERVICE,
        _history_path("AAPL"),
        [
            _price_point(
                instrument_id="AAPL",
                amount="179.00",
                timestamp="2026-05-18T20:00:00Z",
            )
        ],
    )
    fake.register_response(
        "GET",
        _PRICE_SERVICE,
        _latest_path("MSFT"),
        _price_point(instrument_id="MSFT", amount="420.10"),
    )
    fake.register_response(
        "GET",
        _PRICE_SERVICE,
        _history_path("MSFT"),
        [],
    )

    result = await get_market_data_snapshot(
        instruments=["AAPL", "MSFT"],
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    flags = result["citation"].quality_flags
    assert "PRIOR_CLOSE_PARTIAL" in flags
    assert "PRIOR_CLOSE_UNAVAILABLE" not in flags


@pytest.mark.asyncio
async def test_citation_flags_prior_close_unavailable_when_all_missing() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _PRICE_SERVICE,
        _latest_path("AAPL"),
        _price_point(instrument_id="AAPL", amount="180.50"),
    )
    fake.register_response(
        "GET",
        _PRICE_SERVICE,
        _history_path("AAPL"),
        [],
    )

    result = await get_market_data_snapshot(
        instruments=["AAPL"],
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    flags = result["citation"].quality_flags
    assert "PRIOR_CLOSE_UNAVAILABLE" in flags
    assert "PRIOR_CLOSE_PARTIAL" not in flags


@pytest.mark.asyncio
async def test_citation_no_prior_close_flags_when_all_have_prior_close() -> None:
    fake = FakeKinetixHttpClient()
    _seed_two_good_instruments(fake)

    result = await get_market_data_snapshot(
        instruments=["AAPL", "MSFT"],
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    flags = result["citation"].quality_flags
    assert "PRIOR_CLOSE_PARTIAL" not in flags
    assert "PRIOR_CLOSE_UNAVAILABLE" not in flags


@pytest.mark.asyncio
async def test_citation_flags_fields_filter_when_supplied() -> None:
    fake = FakeKinetixHttpClient()
    _seed_two_good_instruments(fake)

    result = await get_market_data_snapshot(
        instruments=["AAPL", "MSFT"],
        fields=["price", "change_pct"],
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert "FIELDS_FILTER_NOT_APPLIED" in result["citation"].quality_flags


@pytest.mark.asyncio
async def test_citation_flags_partial_failure_when_any_failed() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _PRICE_SERVICE,
        _latest_path("AAPL"),
        _price_point(instrument_id="AAPL", amount="180.50"),
    )
    fake.register_response(
        "GET",
        _PRICE_SERVICE,
        _history_path("AAPL"),
        [],
    )
    fake.register_response(
        "GET",
        _PRICE_SERVICE,
        _latest_path("BREAK"),
        KinetixHttpError(
            status_code=502,
            code="UPSTREAM_ERROR",
            message="boom",
            service=_PRICE_SERVICE,
            path=_latest_path("BREAK"),
        ),
    )

    result = await get_market_data_snapshot(
        instruments=["AAPL", "BREAK"],
        user=_DEFAULT_USER,
        http=fake,
        now=_fixed_now,
    )

    assert "PARTIAL_FAILURE" in result["citation"].quality_flags


# ---------------------------------------------------------------------------
# resolve_counterparty helper
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_resolve_counterparty_returns_id_on_unique_match() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET", _REFERENCE_DATA_SERVICE, _COUNTERPARTIES_PATH, _COUNTERPARTIES
    )

    result = await resolve_counterparty(
        "Goldman Sachs",
        user=_DEFAULT_USER,
        http=fake,
    )

    assert result == "CP-GS"


@pytest.mark.asyncio
async def test_resolve_counterparty_matches_short_name() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET", _REFERENCE_DATA_SERVICE, _COUNTERPARTIES_PATH, _COUNTERPARTIES
    )

    result = await resolve_counterparty(
        "JPM",
        user=_DEFAULT_USER,
        http=fake,
    )

    assert result == "CP-JPM"


@pytest.mark.asyncio
async def test_resolve_counterparty_returns_none_on_no_match() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET", _REFERENCE_DATA_SERVICE, _COUNTERPARTIES_PATH, _COUNTERPARTIES
    )

    result = await resolve_counterparty(
        "Nonexistent Bank Plc",
        user=_DEFAULT_USER,
        http=fake,
    )

    assert result is None


@pytest.mark.asyncio
async def test_resolve_counterparty_returns_none_on_ambiguous_match() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET", _REFERENCE_DATA_SERVICE, _COUNTERPARTIES_PATH, _COUNTERPARTIES
    )

    # "Barclays" matches both CP-BARC (legalName) and CP-BARCAP (legalName).
    result = await resolve_counterparty(
        "Barclays",
        user=_DEFAULT_USER,
        http=fake,
    )

    assert result is None


@pytest.mark.asyncio
async def test_resolve_counterparty_handles_empty_string() -> None:
    fake = FakeKinetixHttpClient()

    result = await resolve_counterparty(
        "   ",
        user=_DEFAULT_USER,
        http=fake,
    )

    assert result is None
    assert fake.recorded_calls == []


@pytest.mark.asyncio
async def test_resolve_counterparty_is_case_insensitive() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET", _REFERENCE_DATA_SERVICE, _COUNTERPARTIES_PATH, _COUNTERPARTIES
    )

    result = await resolve_counterparty(
        "goldman sachs",
        user=_DEFAULT_USER,
        http=fake,
    )

    assert result == "CP-GS"


@pytest.mark.asyncio
async def test_resolve_counterparty_uses_correct_endpoint() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET", _REFERENCE_DATA_SERVICE, _COUNTERPARTIES_PATH, _COUNTERPARTIES
    )

    await resolve_counterparty(
        "JPM",
        user=_DEFAULT_USER,
        http=fake,
    )

    assert len(fake.recorded_calls) == 1
    call = fake.recorded_calls[0]
    assert call.method == "GET"
    assert call.service == _REFERENCE_DATA_SERVICE
    assert call.path == _COUNTERPARTIES_PATH
    assert call.params is None


@pytest.mark.asyncio
async def test_resolve_counterparty_propagates_upstream_error() -> None:
    fake = FakeKinetixHttpClient()
    fake.register_response(
        "GET",
        _REFERENCE_DATA_SERVICE,
        _COUNTERPARTIES_PATH,
        KinetixHttpError(
            status_code=502,
            code="UPSTREAM_ERROR",
            message="reference-data unreachable",
            service=_REFERENCE_DATA_SERVICE,
            path=_COUNTERPARTIES_PATH,
        ),
    )

    with pytest.raises(KinetixHttpError) as excinfo:
        await resolve_counterparty(
            "JPM",
            user=_DEFAULT_USER,
            http=fake,
        )

    assert excinfo.value.code == "UPSTREAM_ERROR"
    assert excinfo.value.status_code == 502

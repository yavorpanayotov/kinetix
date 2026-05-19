"""MCP tool ``get_market_data_snapshot`` — multi-instrument market data read.

Reads latest quotes from price-service and computes day-over-day change
metrics by reaching back to ``/history`` for a prior close. The upstream
``PricePointResponse`` payloads are mapped to the v2 tool-output shape
defined in ``plans/ai-v2.md`` § PR 2:

    {
        "quotes": [
            {
                "instrument_id": str,
                "price": float,
                "currency": str,
                "as_of": str,
                "source": str,
                "change_abs": float | None,
                "change_pct": float | None,
                "prior_close": float | None,
                "prior_close_timestamp": str | None,
            },
            ...                       # ordered as input (post-dedup)
        ],
        "not_found": [{"instrument_id": str, "reason": "no_quote"}, ...],
        "failed": [{"instrument_id": str, "reason": str}, ...],
        "requested_count": int,       # len(deduped instruments)
        "returned_count": int,        # len(quotes)
        "citation": Citation,
    }

Two upstream endpoints are fanned out per instrument:

* **Latest** — ``GET /api/v1/prices/{instrument_id}/latest`` returns a
  single ``PricePointResponse``. 404 maps to a ``not_found`` row; any
  other :class:`KinetixHttpError` lands in ``failed`` (so a single
  upstream blip doesn't fail the entire call). Non-``KinetixHttpError``
  exceptions propagate.
* **Prior close** — ``GET /api/v1/prices/{instrument_id}/history?from=…
  &to=…&interval=1d`` returns a list of daily-close
  ``PricePointResponse`` rows for a two-day window
  ``[today_utc - 2d, today_utc]``. The LAST entry is treated as the
  prior close (most recent within the window). Any error or an empty
  list leaves the change fields ``None`` rather than failing the
  per-instrument quote — change metrics are best-effort.

ACL note
--------

``get_market_data_snapshot`` is NOT book-scoped. Market data keys on
**instrument** (reference data), not a ``book_id``, and applying a
``user.books`` filter here would block legitimate lookups of any
instrument outside the caller's book scopes. The price-service is free
to enforce its own per-instrument ACL — the user context is still
forwarded so the downstream service receives ``X-User-Id`` /
``X-User-Books`` headers for audit. This matches ``get_vol_surface``
and the rest of the reference-data-shaped PR 2 cohort.

Sequential fan-out
------------------

Per-instrument calls are made sequentially. The v2 plan favours
simplicity over throughput at this stage; an ``asyncio.gather`` rewrite
is deferred until we have a profiling reason to switch.

Citation
--------

The single :class:`Citation` returned describes the headline numeric
``quote_count`` — the number of successful quotes in the response.
Market data is multi-currency by definition, so ``result_currency`` is
``None`` (NOT ``"USD"``) and ``MULTI_CURRENCY_AGGREGATE`` is always
present in ``quality_flags``. The citation also surfaces:

* ``PRIOR_CLOSE_PARTIAL`` — some, but not all, successful quotes lack
  prior-close metrics.
* ``PRIOR_CLOSE_UNAVAILABLE`` — every successful quote lacks
  prior-close metrics. Mutually exclusive with ``PRIOR_CLOSE_PARTIAL``.
* ``FIELDS_FILTER_NOT_APPLIED`` — caller supplied ``fields`` but the
  tool does not yet honour field-level projection (the response is
  always the full shape). Surfacing this in quality flags rather than
  silently ignoring the parameter avoids consumer surprise.
* ``PARTIAL_FAILURE`` — at least one instrument landed in ``failed``.

``as_of_timestamp`` is the latest upstream ``timestamp`` across
successful quotes; when ``quotes`` is empty it falls back to the
injected ``now()`` so the citation is always well-formed.

The companion :func:`resolve_counterparty` helper is exported from the
same module so AI prompts that need to map a fuzzy issuer/counterparty
name onto a stable ``counterpartyId`` can do so without inventing one
— it returns ``None`` rather than guessing on ambiguous matches.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Any, Callable

from kinetix_insights.citations.models import Citation
from kinetix_insights.clients.kinetix_http_client import (
    KinetixHttpClient,
    KinetixHttpError,
)
from kinetix_insights.clients.user_context import UserContext

_PRICE_SERVICE: str = "price"
_REFERENCE_DATA_SERVICE: str = "reference-data"
_DATA_SOURCE: str = "price-service"
_COUNTERPARTIES_PATH: str = "/api/v1/counterparties"

_MULTI_CURRENCY_AGGREGATE_FLAG: str = "MULTI_CURRENCY_AGGREGATE"
_PRIOR_CLOSE_PARTIAL_FLAG: str = "PRIOR_CLOSE_PARTIAL"
_PRIOR_CLOSE_UNAVAILABLE_FLAG: str = "PRIOR_CLOSE_UNAVAILABLE"
_FIELDS_FILTER_NOT_APPLIED_FLAG: str = "FIELDS_FILTER_NOT_APPLIED"
_PARTIAL_FAILURE_FLAG: str = "PARTIAL_FAILURE"

_HISTORY_WINDOW_DAYS: int = 2


def _default_now() -> datetime:
    return datetime.now(timezone.utc)


def _parse_iso_instant(raw: str) -> datetime:
    """Parse an upstream ISO 8601 instant, normalising trailing ``Z``."""

    normalised = raw.replace("Z", "+00:00") if raw.endswith("Z") else raw
    parsed = datetime.fromisoformat(normalised)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed


def _dedupe_preserve_order(values: list[str]) -> list[str]:
    """Return ``values`` with duplicates removed, preserving first-seen order."""

    seen: set[str] = set()
    out: list[str] = []
    for v in values:
        if v not in seen:
            seen.add(v)
            out.append(v)
    return out


def _history_window(now_value: datetime) -> tuple[str, str]:
    """Return ``(from, to)`` ISO 8601 strings for the prior-close lookup.

    ``to`` is start-of-UTC-day of ``now``; ``from`` is two days earlier.
    Both are serialised with a trailing ``Z`` to match upstream
    expectations.
    """

    today_utc = now_value.replace(hour=0, minute=0, second=0, microsecond=0)
    from_dt = today_utc - timedelta(days=_HISTORY_WINDOW_DAYS)
    return (
        f"{from_dt.strftime('%Y-%m-%dT%H:%M:%S')}Z",
        f"{today_utc.strftime('%Y-%m-%dT%H:%M:%S')}Z",
    )


def _extract_price_amount(payload: dict[str, Any]) -> float:
    """Pull the numeric ``amount`` out of an upstream ``MoneyDto``."""

    money = payload["price"]
    if not isinstance(money, dict):
        raise KinetixHttpError(
            status_code=502,
            code="UPSTREAM_ERROR",
            message=(
                "expected price object with amount/currency, "
                f"got {type(money).__name__}"
            ),
            service=_PRICE_SERVICE,
            path="",
        )
    return float(money["amount"])


def _extract_price_currency(payload: dict[str, Any]) -> str:
    """Pull the ISO 4217 ``currency`` out of an upstream ``MoneyDto``."""

    money = payload["price"]
    if not isinstance(money, dict):
        raise KinetixHttpError(
            status_code=502,
            code="UPSTREAM_ERROR",
            message=(
                "expected price object with amount/currency, "
                f"got {type(money).__name__}"
            ),
            service=_PRICE_SERVICE,
            path="",
        )
    return str(money["currency"])


async def _fetch_prior_close(
    instrument_id: str,
    *,
    user: UserContext,
    http: KinetixHttpClient,
    now_value: datetime,
) -> tuple[float | None, str | None]:
    """Return ``(prior_close_amount, prior_close_timestamp)``.

    Any :class:`KinetixHttpError` or shape drift maps to ``(None, None)``
    — prior-close metrics are best-effort and must never take down a
    per-instrument quote.
    """

    path = f"/api/v1/prices/{instrument_id}/history"
    from_param, to_param = _history_window(now_value)
    params = {"from": from_param, "to": to_param, "interval": "1d"}

    try:
        raw = await http.get(_PRICE_SERVICE, path, params=params, user=user)
    except KinetixHttpError:
        return (None, None)

    if not isinstance(raw, list) or not raw:
        return (None, None)

    last = raw[-1]
    if not isinstance(last, dict):
        return (None, None)
    try:
        amount = _extract_price_amount(last)
    except (KinetixHttpError, KeyError, TypeError, ValueError):
        return (None, None)
    timestamp = last.get("timestamp")
    if not isinstance(timestamp, str):
        return (None, None)
    return (amount, timestamp)


async def get_market_data_snapshot(
    *,
    instruments: list[str],
    fields: list[str] | None = None,
    user: UserContext,
    http: KinetixHttpClient,
    now: Callable[[], datetime] | None = None,
) -> dict[str, Any]:
    """Return latest quotes (with day-over-day change) for ``instruments``.

    Args:
        instruments: Instrument identifiers. Must be non-empty;
            duplicates are removed preserving first-seen order. An
            empty list raises ``BAD_REQUEST`` before any HTTP call.
        fields: Optional projection hint. The tool does not yet honour
            field-level projection — when supplied, the citation
            surfaces ``FIELDS_FILTER_NOT_APPLIED`` so consumers know
            the response is the full shape regardless.
        user: Caller identity and book scopes; forwarded to the HTTP
            client which stamps ``X-User-Id`` / ``X-User-Books``.
            Market data is NOT book-scoped — see module docstring.
        http: ``KinetixHttpClient`` to dispatch upstream calls.
        now: Injectable clock used to derive the history window and to
            stamp ``as_of_timestamp`` on the citation when ``quotes``
            is empty. Defaults to ``datetime.now(timezone.utc)``.

    Returns:
        A dict matching the module docstring shape with ``citation``
        covering ``quote_count``.

    Raises:
        KinetixHttpError: ``BAD_REQUEST``/400 when ``instruments`` is
            empty; ``UPSTREAM_ERROR``/502 when a ``/latest`` payload is
            not a JSON object. Per-instrument upstream errors do NOT
            raise — they are funnelled into ``not_found`` / ``failed``.
    """

    if not instruments:
        raise KinetixHttpError(
            status_code=400,
            code="BAD_REQUEST",
            message="instruments must contain at least one ID",
            service=_PRICE_SERVICE,
            path="",
        )

    deduped = _dedupe_preserve_order(instruments)
    now_fn = now or _default_now
    now_value = now_fn()

    quotes: list[dict[str, Any]] = []
    not_found: list[dict[str, str]] = []
    failed: list[dict[str, str]] = []
    prior_close_present: list[bool] = []

    for instrument_id in deduped:
        latest_path = f"/api/v1/prices/{instrument_id}/latest"
        try:
            raw = await http.get(
                _PRICE_SERVICE, latest_path, params=None, user=user
            )
        except KinetixHttpError as err:
            if err.code == "NOT_FOUND":
                not_found.append(
                    {"instrument_id": instrument_id, "reason": "no_quote"}
                )
            else:
                failed.append(
                    {"instrument_id": instrument_id, "reason": err.code}
                )
            continue

        if not isinstance(raw, dict):
            raise KinetixHttpError(
                status_code=502,
                code="UPSTREAM_ERROR",
                message=(
                    f"expected object from {_PRICE_SERVICE}{latest_path}, "
                    f"got {type(raw).__name__}"
                ),
                service=_PRICE_SERVICE,
                path=latest_path,
            )

        price_amount = _extract_price_amount(raw)
        currency = _extract_price_currency(raw)
        timestamp = str(raw["timestamp"])
        source = str(raw["source"])

        prior_close, prior_close_timestamp = await _fetch_prior_close(
            instrument_id,
            user=user,
            http=http,
            now_value=now_value,
        )

        if prior_close is not None and prior_close != 0.0:
            change_abs: float | None = price_amount - prior_close
            change_pct: float | None = (change_abs / prior_close) * 100
        else:
            change_abs = None
            change_pct = None
            # If prior_close is exactly 0.0 we have no meaningful prior
            # close to anchor a percentage change to; treat as absent.
            if prior_close == 0.0:
                prior_close = None
                prior_close_timestamp = None

        prior_close_present.append(prior_close is not None)

        quotes.append(
            {
                "instrument_id": instrument_id,
                "price": price_amount,
                "currency": currency,
                "as_of": timestamp,
                "source": source,
                "change_abs": change_abs,
                "change_pct": change_pct,
                "prior_close": prior_close,
                "prior_close_timestamp": prior_close_timestamp,
            }
        )

    quality_flags: list[str] = [_MULTI_CURRENCY_AGGREGATE_FLAG]
    if prior_close_present:
        any_present = any(prior_close_present)
        all_present = all(prior_close_present)
        if not any_present:
            quality_flags.append(_PRIOR_CLOSE_UNAVAILABLE_FLAG)
        elif not all_present:
            quality_flags.append(_PRIOR_CLOSE_PARTIAL_FLAG)

    if fields is not None:
        quality_flags.append(_FIELDS_FILTER_NOT_APPLIED_FLAG)
    if failed:
        quality_flags.append(_PARTIAL_FAILURE_FLAG)

    if quotes:
        as_of_timestamp = max(
            _parse_iso_instant(q["as_of"]) for q in quotes
        )
    else:
        as_of_timestamp = now_value

    freshness_seconds = int((now_value - as_of_timestamp).total_seconds())

    citation = Citation(
        tool="get_market_data_snapshot",
        params={"instruments": deduped, "fields": fields},
        result_field="quote_count",
        result_value=float(len(quotes)),
        result_currency=None,
        as_of_timestamp=as_of_timestamp,
        data_source=_DATA_SOURCE,
        freshness_seconds=freshness_seconds,
        quality_flags=quality_flags,
    )

    return {
        "quotes": quotes,
        "not_found": not_found,
        "failed": failed,
        "requested_count": len(deduped),
        "returned_count": len(quotes),
        "citation": citation,
    }


async def resolve_counterparty(
    name: str,
    *,
    user: UserContext,
    http: KinetixHttpClient,
) -> str | None:
    """Resolve a fuzzy issuer/counterparty name to a stable ``counterpartyId``.

    Performs a case-insensitive substring match against the upstream
    ``legalName`` and ``shortName`` columns. Returns the matched
    ``counterpartyId`` only when the match is unique — both empty
    input and ambiguous matches return ``None`` rather than guessing.

    Args:
        name: Free-form counterparty / issuer name. Whitespace-trimmed
            and lower-cased before matching. An empty / whitespace-only
            string returns ``None`` without invoking HTTP.
        user: Caller identity; forwarded to the HTTP client for
            ``X-User-Id`` / ``X-User-Books`` header stamping.
        http: ``KinetixHttpClient`` to dispatch the upstream call.

    Returns:
        The matched ``counterpartyId`` on a unique hit, otherwise
        ``None``.

    Raises:
        KinetixHttpError: Propagated unmodified from the upstream call
            so callers can map failures into the citation error
            contract.
    """

    query = name.strip().lower()
    if not query:
        return None

    raw = await http.get(
        _REFERENCE_DATA_SERVICE, _COUNTERPARTIES_PATH, params=None, user=user
    )
    if not isinstance(raw, list):
        return None

    matches: list[str] = []
    for row in raw:
        if not isinstance(row, dict):
            continue
        legal_name = str(row.get("legalName", "")).lower()
        short_name = str(row.get("shortName", "")).lower()
        if query in legal_name or query in short_name:
            matches.append(str(row["counterpartyId"]))

    if len(matches) == 1:
        return matches[0]
    return None

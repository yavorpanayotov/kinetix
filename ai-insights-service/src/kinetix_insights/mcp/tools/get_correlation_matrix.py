"""MCP tool ``get_correlation_matrix`` — correlation matrix read with citation.

Reads the latest correlation matrix for a set of instrument labels
from correlation-service (``GET /api/v1/correlations/latest``) and
projects the upstream ``CorrelationMatrixResponse`` payload onto the
v2 tool-output shape defined in ``plans/ai-v2.md`` § PR 2:

    {
        "labels": list[str],          # echoes upstream order
        "as_of_timestamp": str,       # ISO 8601 from upstream asOfDate
        "window_days": int,           # echoes upstream windowDays
        "method": str,                # echoes upstream method (e.g. PEARSON)
        "pairs": [
            {"a": str, "b": str, "correlation": float},
            ...   # ONLY off-diagonal upper triangle (i < j) so each
                  # unordered pair appears once; sorted by abs(corr)
                  # descending so the strongest signal lands first.
        ],
        "broken_pairs": [],           # always empty in v2 — see below
        "citation": Citation,
    }

ACL note
--------

``get_correlation_matrix`` is NOT book-scoped. Correlations key on
instrument labels (reference data), not a ``book_id``, and applying a
``user.books`` filter here would block legitimate cross-asset lookups
for any caller. The correlation-service is free to enforce its own
ACL — the user context is still forwarded so the downstream service
receives ``X-User-Id`` / ``X-User-Books`` headers for audit. This is
a deliberate departure from book-scoped PR 2 tools (e.g.
``get_book_var``, ``get_limit_utilisation``) and matches the
``get_vol_surface`` posture.

Default labels & defaults
-------------------------

The upstream requires ``labels`` and ``window`` on every call. When
the caller supplies ``asset_pair=[X, Y]`` (exactly two labels), the
tool uses those. When ``asset_pair`` is ``None``, the tool falls back
to the canonical v2 multi-asset reference set:

    _DEFAULT_LABELS = ("EURUSD", "GBPUSD", "USDJPY", "GOLD", "SPX")

``lookback_days`` maps to the upstream ``window`` query parameter.
When ``None``, it defaults to ``60``.

Input validation runs BEFORE any HTTP call:

* ``asset_pair`` supplied with ``len(asset_pair) != 2`` -> 400
  ``BAD_REQUEST``.
* ``asset_pair`` containing duplicate labels -> 400 ``BAD_REQUEST``.
* ``lookback_days`` supplied and ``<= 0`` -> 400 ``BAD_REQUEST``.

These are cheap checks that catch malformed callers without a network
round trip — and they don't depend on the user's identity so they
must fail before the ACL-style upstream call. The
``FakeKinetixHttpClient.recorded_calls`` list is therefore empty in
all three failure modes (asserted by the unit tests).

V2 gaps surfaced via citation ``quality_flags``
-----------------------------------------------

The plan asks for two capabilities the upstream cannot provide today:

1. **Historical ``as_of`` lookup.** The only correlation-service
   endpoint is ``/latest`` — there is no ``findAtOrBefore`` or
   ``?asOf=`` exposed. When ``as_of`` is supplied, the tool issues
   the same ``/latest`` call (no historical query) and surfaces
   ``"AS_OF_NOT_SUPPORTED"`` in the citation ``quality_flags``. The
   v2 follow-up is a ``findAtOrBefore`` HTTP endpoint on
   correlation-service.

2. **Correlation-break detection** ("pairs that moved >0.15 from
   prior day"). Would require a second upstream call for the
   prior-day matrix, which is unavailable for the same reason (no
   historical lookup). The tool always surfaces
   ``"CORRELATION_BREAK_UNAVAILABLE"`` in the citation
   ``quality_flags`` and always returns ``broken_pairs == []`` — the
   field exists in the output shape so the downstream consumer (the
   AI) gets a structurally-stable contract today and the field will
   populate transparently once the prior-day endpoint lands.

Citation
--------

The single :class:`Citation` returned describes a headline numeric:
``max_abs_off_diagonal_correlation`` — the strongest absolute
off-diagonal correlation in the matrix. Correlations are
dimensionless, so ``result_currency`` is ``None``.

Upstream ``KinetixHttpError`` (``NOT_FOUND``, ``UPSTREAM_ERROR``, ...)
is propagated unmodified so callers can map upstream failures into
the citation error contract uniformly. A non-``dict`` payload from
the upstream raises a synthetic
``KinetixHttpError(UPSTREAM_ERROR, 502)`` because the upstream
contract is a single object, not an array.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Callable

from kinetix_insights.citations.models import Citation
from kinetix_insights.clients.kinetix_http_client import (
    KinetixHttpClient,
    KinetixHttpError,
)
from kinetix_insights.clients.user_context import UserContext

_SERVICE: str = "correlation"
_DATA_SOURCE: str = "correlation-service"
_LATEST_PATH: str = "/api/v1/correlations/latest"

_DEFAULT_LABELS: tuple[str, ...] = (
    "EURUSD",
    "GBPUSD",
    "USDJPY",
    "GOLD",
    "SPX",
)
_DEFAULT_WINDOW_DAYS: int = 60

_CORRELATION_BREAK_UNAVAILABLE_FLAG: str = "CORRELATION_BREAK_UNAVAILABLE"
_AS_OF_NOT_SUPPORTED_FLAG: str = "AS_OF_NOT_SUPPORTED"


def _default_now() -> datetime:
    return datetime.now(timezone.utc)


def _parse_iso_instant(raw: str) -> datetime:
    """Parse an upstream ISO 8601 instant, normalising trailing ``Z``.

    ``asOfDate`` is serialised with a trailing ``Z``; ``fromisoformat``
    accepts ``+00:00`` natively, so we normalise.
    """

    normalised = raw.replace("Z", "+00:00") if raw.endswith("Z") else raw
    parsed = datetime.fromisoformat(normalised)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed


def _validate_inputs(
    asset_pair: list[str] | tuple[str, ...] | None,
    lookback_days: int | None,
) -> None:
    """Fail closed on malformed inputs BEFORE any HTTP call."""

    if asset_pair is not None:
        if len(asset_pair) != 2:
            raise KinetixHttpError(
                status_code=400,
                code="BAD_REQUEST",
                message="asset_pair must be exactly two labels when supplied",
                service=_SERVICE,
                path=_LATEST_PATH,
            )
        if asset_pair[0] == asset_pair[1]:
            raise KinetixHttpError(
                status_code=400,
                code="BAD_REQUEST",
                message="asset_pair labels must be distinct",
                service=_SERVICE,
                path=_LATEST_PATH,
            )

    if lookback_days is not None and lookback_days <= 0:
        raise KinetixHttpError(
            status_code=400,
            code="BAD_REQUEST",
            message="lookback_days must be positive",
            service=_SERVICE,
            path=_LATEST_PATH,
        )


def _build_pairs(
    labels: list[str], values: list[float]
) -> list[dict[str, Any]]:
    """Decode the row-major upper triangle into off-diagonal pair dicts.

    For labels ``[A, B, C]`` the upstream ``values`` is a length-9
    row-major flat list — ``values[i * n + j]`` is the correlation
    between ``labels[i]`` and ``labels[j]``. The matrix is symmetric
    and the diagonal is 1.0 by construction, so we only emit the
    strict upper triangle (``i < j``) and sort by ``abs(correlation)``
    descending — the strongest signal lands first in the output for
    the AI consumer.
    """

    n = len(labels)
    pairs: list[dict[str, Any]] = []
    for i in range(n):
        for j in range(i + 1, n):
            corr = float(values[i * n + j])
            pairs.append({
                "a": labels[i],
                "b": labels[j],
                "correlation": corr,
            })
    pairs.sort(key=lambda row: abs(row["correlation"]), reverse=True)
    return pairs


async def get_correlation_matrix(
    *,
    asset_pair: list[str] | tuple[str, ...] | None = None,
    as_of: str | None = None,
    lookback_days: int | None = None,
    user: UserContext,
    http: KinetixHttpClient,
    now: Callable[[], datetime] | None = None,
) -> dict[str, Any]:
    """Return the latest correlation matrix for ``asset_pair`` (or default labels).

    Args:
        asset_pair: Optional pair of labels (e.g. ``("EURUSD", "GBPUSD")``).
            Must be exactly two distinct labels when supplied; otherwise
            the tool defaults to the canonical multi-asset reference
            set ``_DEFAULT_LABELS``.
        as_of: Optional ISO date ``YYYY-MM-DD``. NOT supported by the
            upstream today — the tool always queries ``/latest`` and
            surfaces ``AS_OF_NOT_SUPPORTED`` in the citation flags when
            supplied. Documented as a v2 limitation.
        lookback_days: Optional window in days; maps to the upstream
            ``window`` query parameter. Defaults to ``60``. Must be
            positive when supplied.
        user: Caller identity and book scopes; forwarded to the HTTP
            client which stamps ``X-User-Id`` / ``X-User-Books``.
            Correlations are NOT book-scoped — see module docstring.
        http: ``KinetixHttpClient`` to dispatch the upstream call.
        now: Injectable clock used to compute ``freshness_seconds``.
            Defaults to ``datetime.now(timezone.utc)``.

    Returns:
        A dict matching the module docstring shape with a
        :class:`Citation` covering
        ``max_abs_off_diagonal_correlation``.

    Raises:
        KinetixHttpError: ``BAD_REQUEST``/400 on malformed inputs;
            ``NOT_FOUND``/404 when no matrix matches the labels/window;
            ``UPSTREAM_ERROR``/502 on payload-shape drift; upstream
            errors propagate unmodified.
    """

    _validate_inputs(asset_pair, lookback_days)

    if asset_pair is not None:
        labels_csv = ",".join(asset_pair)
    else:
        labels_csv = ",".join(_DEFAULT_LABELS)
    window = lookback_days if lookback_days is not None else _DEFAULT_WINDOW_DAYS

    params: dict[str, Any] = {"labels": labels_csv, "window": window}

    raw = await http.get(_SERVICE, _LATEST_PATH, params=params, user=user)
    if not isinstance(raw, dict):
        raise KinetixHttpError(
            status_code=502,
            code="UPSTREAM_ERROR",
            message=(
                f"expected object from {_SERVICE}{_LATEST_PATH}, "
                f"got {type(raw).__name__}"
            ),
            service=_SERVICE,
            path=_LATEST_PATH,
        )

    payload: dict[str, Any] = raw
    labels: list[str] = list(payload["labels"])
    values: list[float] = [float(v) for v in payload["values"]]
    window_days: int = int(payload["windowDays"])
    method: str = str(payload["method"])
    as_of_raw: str = payload["asOfDate"]
    as_of_timestamp = _parse_iso_instant(as_of_raw)

    pairs = _build_pairs(labels, values)

    # Headline numeric for the citation: largest absolute off-diagonal
    # correlation. Falls back to 0.0 for degenerate single-label
    # responses where there are no off-diagonal entries — the citation
    # contract still needs a numeric anchor.
    if pairs:
        headline = max(abs(row["correlation"]) for row in pairs)
    else:
        headline = 0.0

    now_fn = now or _default_now
    freshness_seconds = int((now_fn() - as_of_timestamp).total_seconds())

    quality_flags: list[str] = [_CORRELATION_BREAK_UNAVAILABLE_FLAG]
    if as_of is not None:
        quality_flags.append(_AS_OF_NOT_SUPPORTED_FLAG)

    citation = Citation(
        tool="get_correlation_matrix",
        params={
            "asset_pair": list(asset_pair) if asset_pair is not None else None,
            "as_of": as_of,
            "lookback_days": lookback_days,
        },
        result_field="max_abs_off_diagonal_correlation",
        result_value=headline,
        result_currency=None,
        as_of_timestamp=as_of_timestamp,
        data_source=_DATA_SOURCE,
        freshness_seconds=freshness_seconds,
        quality_flags=quality_flags,
    )

    return {
        "labels": labels,
        "as_of_timestamp": as_of_raw,
        "window_days": window_days,
        "method": method,
        "pairs": pairs,
        "broken_pairs": [],
        "citation": citation,
    }

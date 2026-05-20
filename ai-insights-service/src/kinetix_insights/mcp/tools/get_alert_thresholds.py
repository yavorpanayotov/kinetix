"""MCP tool ``get_alert_thresholds`` — copilot alert-threshold config read.

Reads the ``copilot_alert_thresholds`` configuration table and projects
the rows onto the v2 tool-output shape defined in ``plans/ai-v2.md``
§ PR 7:

    {
        "scope": str,                  # echoed request scope
        "scope_id": str | None,        # echoed request scope id
        "thresholds": [
            {
                "id": str,
                "scope_type": str,     # "GLOBAL" | "BOOK" | "USER"
                "scope_id": str | None,
                "alert_type": str,     # e.g. "VAR_BREACH"
                "threshold_value": float,
                "cooldown_minutes": int,
            },
            ...
        ],
        "count": int,
        "citation": Citation,
    }

The single :class:`Citation` returned describes the headline
``threshold_count`` value so callers and the citation verifier have one
canonical numeric anchor for the response. The value is a count, not a
monetary quantity, so ``result_currency`` is ``None``.

Upstream HTTP contract — IMPORTANT v2 limitation
------------------------------------------------
Checkbox 7.1's ``V69`` migration created the ``copilot_alert_thresholds``
table on risk-orchestrator but did NOT add an HTTP read route. Like
checkbox 6.3's ``get_recent_breaches``, this tool targets the
*conventional* endpoint

    GET /api/v1/risk/copilot-alert-thresholds

on service short-name ``risk-orchestrator``. The unit tests exercise the
tool fully via :class:`tests.fakes.fake_kinetix_http_client.
FakeKinetixHttpClient`, so the tool is testable without the route
existing. Exposing that read route on risk-orchestrator is a v2
follow-up — the :class:`~kinetix_insights.push.threshold_evaluator.
IntradayThresholdEvaluator` that consumes this tool will need it before
the tool works end-to-end against a real deployment. Because the
endpoint is a documented pending follow-up, the citation always carries
the ``ENDPOINT_PENDING`` quality flag.

Assumptions and known limitations (v2 scope)
--------------------------------------------
* **Config, not book-scoped data** — alert thresholds are platform
  configuration, not per-book risk data. The tool is therefore NOT
  book-ACL-scoped: it does not check ``book_id`` membership. ``user`` is
  forwarded purely so the HTTP client can stamp ``X-User-Id`` /
  ``X-User-Books`` headers for downstream auditing.

* **Server-side scope filter via query params** — the tool sends
  ``scope`` (and, when non-null, ``scopeId``) as query parameters so the
  upstream route can filter server-side once it exists. The upstream
  array is otherwise consumed as-is; the tool does not re-filter
  client-side.

* **Not wired into the FastMCP registry** — registry wiring for the
  original PR 2 tools was checkbox 2.11. Wiring this tool into
  ``server.py`` is a separate v2 follow-up, out of scope for 7.2.

* **No upstream timestamp** — the endpoint returns a list with no
  per-call timestamp, so the citation pins ``as_of_timestamp`` to the
  call time (injected ``now``) and ``freshness_seconds`` is ``0``.

Validation: ``scope`` must be one of ``GLOBAL`` / ``BOOK`` / ``USER`` —
any other value raises ``KinetixHttpError(BAD_REQUEST, 400)`` BEFORE any
HTTP call. Upstream ``KinetixHttpError`` (``NOT_FOUND``,
``UPSTREAM_ERROR``, ...) is propagated unmodified so callers can map into
the citation error contract uniformly; a non-array payload is rejected as
``UPSTREAM_ERROR``/502.
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

_SERVICE_SHORT_NAME: str = "risk-orchestrator"
_DATA_SOURCE: str = "risk-orchestrator"
_THRESHOLDS_PATH: str = "/api/v1/risk/copilot-alert-thresholds"

_VALID_SCOPES: frozenset[str] = frozenset({"GLOBAL", "BOOK", "USER"})

_ENDPOINT_PENDING_FLAG: str = "ENDPOINT_PENDING"


def _default_now() -> datetime:
    return datetime.now(timezone.utc)


def _map_row(raw: dict[str, Any]) -> dict[str, Any]:
    """Project an upstream ``copilot_alert_thresholds`` row onto the tool row.

    ``thresholdValue`` is a string-formatted decimal upstream — coerced to
    ``float`` so the evaluator and citation arithmetic are numeric.
    """

    return {
        "id": raw["id"],
        "scope_type": raw["scopeType"],
        "scope_id": raw.get("scopeId"),
        "alert_type": raw["alertType"],
        "threshold_value": float(raw["thresholdValue"]),
        "cooldown_minutes": int(raw["cooldownMinutes"]),
    }


async def get_alert_thresholds(
    *,
    scope: str = "GLOBAL",
    scope_id: str | None = None,
    user: UserContext,
    http: KinetixHttpClient,
    now: Callable[[], datetime] | None = None,
) -> dict[str, Any]:
    """Return the configured copilot alert thresholds with a citation.

    Args:
        scope: Threshold scope — one of ``"GLOBAL"`` / ``"BOOK"`` /
            ``"USER"``. Any other value raises ``BAD_REQUEST`` before the
            HTTP call. Sent on the wire as the ``scope`` query parameter.
        scope_id: Book or user identifier for ``BOOK`` / ``USER`` scopes;
            ``None`` for ``GLOBAL``. Sent as the ``scopeId`` query
            parameter only when non-null.
        user: Caller identity; forwarded to the HTTP client which stamps
            ``X-User-Id`` / ``X-User-Books``. Thresholds are config, not
            book-scoped data, so no book-ACL check is performed.
        http: ``KinetixHttpClient`` to dispatch the upstream call.
        now: Injectable clock used to stamp ``as_of_timestamp`` on the
            citation. Defaults to ``datetime.now(timezone.utc)``.

    Returns:
        A dict with ``scope``, ``scope_id`` (both echoed from the
        request), ``thresholds`` (rows in tool output shape), ``count``,
        and ``citation`` (a :class:`Citation` covering
        ``threshold_count``).

    Raises:
        KinetixHttpError: ``BAD_REQUEST``/400 for an invalid ``scope``;
            ``UPSTREAM_ERROR``/502 when the upstream payload is not a
            JSON array; ``NOT_FOUND``, ``UPSTREAM_ERROR`` (and other
            coarse categories) when the upstream call itself fails. All
            propagated unmodified.
    """

    # Validate scope BEFORE any HTTP call.
    if scope not in _VALID_SCOPES:
        raise KinetixHttpError(
            status_code=400,
            code="BAD_REQUEST",
            message=(
                f"invalid scope {scope!r}; expected one of "
                f"{sorted(_VALID_SCOPES)}"
            ),
            service=_SERVICE_SHORT_NAME,
            path=_THRESHOLDS_PATH,
        )

    params: dict[str, Any] = {"scope": scope}
    if scope_id is not None:
        params["scopeId"] = scope_id

    now_fn = now or _default_now
    as_of_timestamp = now_fn()

    raw_payload = await http.get(
        _SERVICE_SHORT_NAME, _THRESHOLDS_PATH, params=params, user=user
    )
    if not isinstance(raw_payload, list):
        raise KinetixHttpError(
            status_code=502,
            code="UPSTREAM_ERROR",
            message=(
                f"expected array from {_SERVICE_SHORT_NAME}"
                f"{_THRESHOLDS_PATH}, got {type(raw_payload).__name__}"
            ),
            service=_SERVICE_SHORT_NAME,
            path=_THRESHOLDS_PATH,
        )

    thresholds = [_map_row(row) for row in raw_payload]
    count = len(thresholds)

    citation = Citation(
        tool="get_alert_thresholds",
        params={"scope": scope, "scope_id": scope_id},
        result_field="threshold_count",
        result_value=float(count),
        result_currency=None,
        as_of_timestamp=as_of_timestamp,
        data_source=_DATA_SOURCE,
        freshness_seconds=0,
        quality_flags=[_ENDPOINT_PENDING_FLAG],
    )

    return {
        "scope": scope,
        "scope_id": scope_id,
        "thresholds": thresholds,
        "count": count,
        "citation": citation,
    }

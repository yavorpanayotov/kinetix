"""The `Citation` model — provenance for every numeric token in an insight.

Every number an AI response emits must carry a `Citation` linking it
back to the upstream tool call, the exact field within that call's
result, the underlying data's `as_of` timestamp, the data source, and
any data-quality caveats. The UI uses these citations to render
inline footnotes and staleness indicators; a post-generation
`citation_verifier` flags numeric tokens that lack a matching
citation.

Citations are frozen so that downstream code can pass them around
(into audit logs, into the verifier, into SSE frames) without fear of
in-flight mutation.
"""

from datetime import datetime
from typing import Any, Final

from pydantic import BaseModel, ConfigDict, Field

# Sentinel ``quality_flags`` entry signalling that the tool call backing
# this citation did not complete within its per-message budget. Used by
# the chat client (see :mod:`kinetix_insights.chat.claude_agent_chat_client`)
# to surface a timeout WITHOUT growing the :class:`Citation` schema —
# the existing ``quality_flags`` list is the channel for these
# non-blocking caveats. A timeout citation pairs ``TIMEOUT_FLAG`` with
# ``result_value="timeout"`` so consumers can detect either signal.
TIMEOUT_FLAG: Final[str] = "TIMEOUT"


class Citation(BaseModel):
    """Machine-readable provenance for a single numeric value in an insight.

    `tool` and `params` together identify the MCP tool call that
    produced the value; `result_field` is a dotted/JSON-pointer path
    into that call's result (e.g. `"total_var"` or
    `"var_by_asset_class[0].value"`). `result_value` is the value
    itself — usually a number, but a pre-formatted string like
    `"5.2M USD"` is accepted for cases where the model emits a
    human-readable form. `result_currency` is the ISO 4217 code when
    the value is monetary; `None` for ratios and dimensionless
    quantities. `as_of_timestamp` is when the underlying data was
    captured (distinct from when the citation object was created);
    `freshness_seconds` is the integer staleness at citation time and
    drives the UI's staleness indicator. `quality_flags` carries
    non-blocking caveats such as `STALE_PRICE`, `EXTRAPOLATED`, or
    `INCOMPLETE_BOOK`.
    """

    model_config = ConfigDict(frozen=True)

    tool: str
    params: dict[str, Any]
    result_field: str
    result_value: float | int | str
    result_currency: str | None = None
    as_of_timestamp: datetime
    data_source: str
    freshness_seconds: int
    quality_flags: list[str] = Field(default_factory=list)

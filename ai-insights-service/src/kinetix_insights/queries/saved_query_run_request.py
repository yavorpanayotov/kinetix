"""The :class:`SavedQueryRunRequest` model — body for the run endpoint.

``POST /api/v1/insights/queries/{id}/run`` takes the template id from the
path and the param values from this body. The template id is *not* a
field here — it is a path parameter — so the body carries only the
``params`` map whose keys/values are interpolated into the template's
``prompt_template``.

``params`` is intentionally a free-form ``dict``: which keys are
*required* is declared per-template by ``required_params`` in the JSON
resource, so the body model cannot pin them statically. The run route
validates the supplied params against the resolved template's
``required_params`` and returns a client error if any are missing.
Optional session / conversation ids mirror :class:`ChatRequest` so a run
can be threaded into an existing conversation.
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field


class SavedQueryRunRequest(BaseModel):
    """Inbound body for the saved-query run endpoint.

    ``params`` supplies the values interpolated into the template's
    ``{placeholder}`` slots. ``session_id`` / ``conversation_id`` are
    opaque strings the route stamps in and out for continuity, exactly as
    :class:`kinetix_insights.chat.models.ChatRequest` does; both default
    to ``None`` and are generated server-side when absent.
    """

    params: dict[str, Any] = Field(default_factory=dict)
    session_id: str | None = None
    conversation_id: str | None = None

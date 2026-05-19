"""Pydantic models for the conversational chat endpoint.

The chat endpoint is a Server-Sent Events stream: the UI POSTs a
`ChatRequest` (the user's message plus opaque session/conversation
identifiers and free-form page context) and the service streams back
a sequence of `ChatChunk` frames, each serialised as
``data: <chunk-json>\\n\\n`` on the wire. Incremental text arrives in
`delta`, the terminal frame carries `done=True` along with the
`model` and `mode` that produced the response (and, when applicable,
`citations` or an `error_code`). Both models are intentionally
mutable so they can be assembled frame-by-frame as a response is
generated.
"""

from typing import Any

from pydantic import BaseModel, Field, field_validator, model_validator

from kinetix_insights.citations.models import Citation


class ChatRequest(BaseModel):
    """Inbound chat request from the UI.

    `message` is the user's prompt and must be non-empty (whitespace
    alone is rejected). `page_context` is a free-form bag of metadata
    captured from wherever the user opened the chat — current route,
    selected book, filters, etc. — and is intentionally untyped so
    the UI can evolve it without a model migration. `session_id` and
    `conversation_id` are opaque strings the route handler stamps in
    and out for continuity; they carry no format constraints here.
    """

    message: str
    page_context: dict[str, Any] = Field(default_factory=dict)
    session_id: str | None = None
    conversation_id: str | None = None

    @field_validator("message")
    @classmethod
    def _message_must_not_be_blank(cls, value: str) -> str:
        """Reject empty or whitespace-only messages so they fail fast."""
        if not value or not value.strip():
            raise ValueError("message must be a non-empty, non-whitespace string")
        return value


class ChatChunk(BaseModel):
    """One frame of the outbound SSE stream.

    `done` is the only required field and marks whether this is the
    terminal frame of the stream. `delta` carries an incremental piece
    of generated text for non-terminal frames and is `None` on pure
    metadata frames. `citations` populates terminal frames (or
    dedicated `event: source` frames) with provenance for the numbers
    the response cites. `model` and `mode` are stamped on the final
    chunk to record which LLM produced the output and whether it came
    from a live call or a canned fixture. `error_code` is set when an
    upstream failure, policy block, or unverifiable citation aborts
    the stream — when it is set, `done` must be `True` because error
    frames are always terminal.
    """

    delta: str | None = None
    done: bool
    citations: list[Citation] | None = None
    model: str | None = None
    mode: str | None = None
    error_code: str | None = None

    @model_validator(mode="after")
    def _error_frames_are_terminal(self) -> "ChatChunk":
        """When `error_code` is set, the frame must also have `done=True`."""
        if self.error_code is not None and self.done is not True:
            raise ValueError(
                "error_code may only appear on terminal frames (done must be True)"
            )
        return self

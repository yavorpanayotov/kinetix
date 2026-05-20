"""The :class:`BriefClient` protocol and its canned implementation.

A :class:`BriefClient` produces the list of per-book
:class:`MorningBrief` objects a trader sees at the start of the day.
Two implementations satisfy it: :class:`CannedBriefClient` (this file)
replays a deterministic fixture for the 90-second demo and CI, and
:class:`kinetix_insights.brief.claude_agent_brief_client.ClaudeAgentBriefClient`
runs the live :class:`MorningBriefGenerator` over the user's books.

Design notes:

* **Eager loading, fail fast.** The fixture is parsed once at
  construction. A missing or malformed file surfaces immediately as a
  clear error rather than poisoning a later request — silent
  degradation is worse than a startup failure.
* **Deterministic by design.** The canned client ignores the
  ``user`` argument entirely — the fixture is fixed demo data, so two
  different :class:`UserContext`\\ s always get the same briefs. This
  is intentional: the demo must be reproducible frame-for-frame.
* **Fixture is a JSON array.** ``demo_brief.json`` is an array of
  :class:`MorningBrief` objects so a multi-book demo works; each
  element is validated via :meth:`MorningBrief.model_validate`.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Protocol, runtime_checkable

from kinetix_insights.brief.models import MorningBrief
from kinetix_insights.clients.user_context import UserContext

_DEFAULT_FIXTURE_PATH = (
    Path(__file__).resolve().parent.parent / "fixtures" / "demo_brief.json"
)


@runtime_checkable
class BriefClient(Protocol):
    """Produces a :class:`MorningBrief` per book for a user.

    Implementations may be canned (fixture-driven) or live (the
    :class:`MorningBriefGenerator` wrapped by the Claude SDK client).
    The route handler depends on this protocol and swaps the concrete
    client per environment via :func:`build_brief_client`.
    """

    async def generate_brief(self, *, user: UserContext) -> list[MorningBrief]:
        ...  # pragma: no cover - structural only


def _load_briefs(fixture_path: Path) -> list[MorningBrief]:
    """Parse ``fixture_path`` into a list of :class:`MorningBrief`.

    Raises a clear error if the file is missing (``FileNotFoundError``)
    or malformed (JSON / Pydantic validation error).
    """

    raw = json.loads(fixture_path.read_text())
    if not isinstance(raw, list):
        raise ValueError(
            f"demo brief fixture {fixture_path!s} must be a JSON array of "
            f"MorningBrief objects, got {type(raw).__name__}"
        )
    return [MorningBrief.model_validate(entry) for entry in raw]


class CannedBriefClient:
    """Fixture-driven :class:`BriefClient`.

    Loads ``fixtures/demo_brief.json`` eagerly at construction and
    returns the parsed :class:`MorningBrief` list verbatim on every
    :meth:`generate_brief` call. The ``user`` argument is intentionally
    ignored — the fixture is deterministic demo data so the 90-second
    demo replays identically regardless of who is signed in.
    """

    def __init__(self, *, fixture_path: Path | None = None) -> None:
        path = fixture_path if fixture_path is not None else _DEFAULT_FIXTURE_PATH
        self._briefs = _load_briefs(path)

    async def generate_brief(self, *, user: UserContext) -> list[MorningBrief]:
        """Return the canned briefs from the fixture.

        ``user`` is accepted to satisfy the :class:`BriefClient`
        protocol but is intentionally unused — the fixture is fixed
        demo data, so every caller gets the same deterministic briefs.
        """

        del user  # the canned fixture is deterministic; user is ignored
        return list(self._briefs)

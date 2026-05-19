"""Immutable per-request user identity used to stamp downstream headers.

A :class:`UserContext` carries the trader's identifier and the set of
books their JWT scopes them to. ``KinetixHttpClient`` implementations
read it on every call and stamp ``X-User-Id`` / ``X-User-Books`` headers
into the outgoing request so downstream Kinetix services can authorise
and audit per-user.

The dataclass is frozen and uses a tuple for ``books`` so a context can
be safely shared across concurrent tool calls without risk of mutation
leaking between callers.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class UserContext:
    """Identifies the trader on whose behalf a downstream call is made.

    Attributes:
        user_id: Stable identifier (typically the JWT ``sub`` claim).
        books: Books the user is scoped to. Empty tuple means no books.
    """

    user_id: str
    books: tuple[str, ...]

    def to_headers(self) -> dict[str, str]:
        """Render the per-request headers downstream services expect.

        ``X-User-Books`` is a comma-separated list — empty string when
        the user has no book scopes, so the header is always present and
        downstream services can rely on it for authorisation checks.
        """

        return {
            "X-User-Id": self.user_id,
            "X-User-Books": ",".join(self.books),
        }

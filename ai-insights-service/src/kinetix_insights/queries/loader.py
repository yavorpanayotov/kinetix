"""Loads built-in saved-query templates from their JSON resources.

The five built-in templates ship as ``*.json`` files beside this module
(see :mod:`kinetix_insights.queries`). Each file's ``id`` matches its
filename stem, so :func:`load_saved_query_template` can resolve a
template by id without scanning every file — it just opens
``<id>.json``.

The loader is intentionally minimal: no caching, no eager whole-corpus
load. The run route resolves exactly one template per request, and the
files are tiny, so a per-request ``read_text`` + ``json.loads`` is
cheap. A missing id surfaces as :class:`SavedQueryTemplateNotFoundError`
which the route turns into an HTTP 404.
"""

from __future__ import annotations

import json
from pathlib import Path

from kinetix_insights.queries.saved_query_template import SavedQueryTemplate

# The directory holding the built-in *.json template resources — this
# package's own directory.
_QUERIES_DIR = Path(__file__).resolve().parent


class SavedQueryTemplateNotFoundError(LookupError):
    """Raised when no built-in template exists for a requested id."""

    def __init__(self, template_id: str) -> None:
        self.template_id = template_id
        super().__init__(f"no saved-query template with id {template_id!r}")


def _is_safe_id(template_id: str) -> bool:
    """Return whether ``template_id`` is a plain id with no path components.

    Template ids are filename stems (``limit-breaches`` etc.). Rejecting
    separators and ``.`` keeps a request-supplied id from escaping the
    queries directory via the filename it maps to.
    """

    return bool(template_id) and not (
        "/" in template_id or "\\" in template_id or template_id in {".", ".."}
    )


def load_saved_query_template(template_id: str) -> SavedQueryTemplate:
    """Load and return the built-in template with id ``template_id``.

    Raises :class:`SavedQueryTemplateNotFoundError` when no matching
    ``<id>.json`` resource exists (or the id is not a plain filename
    stem). The parsed file is validated against
    :class:`SavedQueryTemplate`, so a malformed resource fails loudly.
    """

    if not _is_safe_id(template_id):
        raise SavedQueryTemplateNotFoundError(template_id)
    path = _QUERIES_DIR / f"{template_id}.json"
    if not path.is_file():
        raise SavedQueryTemplateNotFoundError(template_id)
    payload = json.loads(path.read_text())
    return SavedQueryTemplate.model_validate(payload)


def load_saved_query_templates() -> list[SavedQueryTemplate]:
    """Load every built-in template, sorted by id for deterministic order."""

    templates = [
        SavedQueryTemplate.model_validate(json.loads(path.read_text()))
        for path in sorted(_QUERIES_DIR.glob("*.json"))
    ]
    templates.sort(key=lambda t: t.id)
    return templates

"""Unit tests for the built-in saved-query templates.

These tests pin the on-disk contract that the saved-query run route
(PR 8.2) will rely on:

* exactly five built-in templates ship under
  ``src/kinetix_insights/queries/``
* each template is valid JSON carrying the four required keys
  ``id``, ``label``, ``prompt_template``, ``required_params`` with the
  correct types
* each file's ``id`` matches its filename stem so a route can load a
  template by id without scanning every file
* the ``{...}`` placeholders embedded in ``prompt_template`` are exactly
  the parameter names declared in ``required_params`` — neither side
  drifts from the other

The templates are plain JSON resources; the test loads them directly
so the contract is verified independently of any loader code.
"""

from __future__ import annotations

import json
import string
from pathlib import Path

import pytest

pytestmark = pytest.mark.unit


_QUERIES_DIR = (
    Path(__file__).resolve().parent.parent
    / "src"
    / "kinetix_insights"
    / "queries"
)

_EXPECTED_IDS = {
    "limit-breaches",
    "pnl-vs-yesterday",
    "var-week-drivers",
    "top-positions-risk-contribution",
    "vol-dislocations",
}

_REQUIRED_KEYS = {"id", "label", "prompt_template", "required_params"}


def _template_files() -> list[Path]:
    """Return the JSON template files, sorted for deterministic ordering."""

    return sorted(_QUERIES_DIR.glob("*.json"))


def _load(path: Path) -> dict[str, object]:
    """Parse a single template file as JSON."""

    return json.loads(path.read_text())


def _placeholders(template: str) -> set[str]:
    """Extract ``{name}`` placeholders from a ``str.format`` style string."""

    return {
        field_name
        for _literal, field_name, _spec, _conv in string.Formatter().parse(
            template
        )
        if field_name
    }


def test_queries_directory_exists() -> None:
    """The built-in templates ship under ``src/kinetix_insights/queries/``."""

    assert _QUERIES_DIR.is_dir()


def test_exactly_five_built_in_templates() -> None:
    """PR 8 ships exactly five built-in saved queries."""

    assert len(_template_files()) == 5


def test_template_ids_match_the_expected_set() -> None:
    """The five templates carry the expected stable identifiers."""

    ids = {_load(path)["id"] for path in _template_files()}
    assert ids == _EXPECTED_IDS


@pytest.mark.parametrize("path", _template_files(), ids=lambda p: p.name)
def test_template_is_valid_json_with_required_keys(path: Path) -> None:
    """Each template parses as a JSON object carrying the four keys."""

    payload = _load(path)
    assert isinstance(payload, dict)
    assert _REQUIRED_KEYS.issubset(payload.keys())


@pytest.mark.parametrize("path", _template_files(), ids=lambda p: p.name)
def test_template_field_types_are_correct(path: Path) -> None:
    """``id``/``label``/``prompt_template`` are non-empty strings.

    ``required_params`` is a list of strings (possibly empty).
    """

    payload = _load(path)

    for key in ("id", "label", "prompt_template"):
        value = payload[key]
        assert isinstance(value, str), f"{path.name}: {key} must be a string"
        assert value.strip(), f"{path.name}: {key} must be non-empty"

    required_params = payload["required_params"]
    assert isinstance(required_params, list), (
        f"{path.name}: required_params must be a list"
    )
    for param in required_params:
        assert isinstance(param, str) and param.strip(), (
            f"{path.name}: required_params entries must be non-empty strings"
        )


@pytest.mark.parametrize("path", _template_files(), ids=lambda p: p.name)
def test_template_id_matches_filename_stem(path: Path) -> None:
    """A template's ``id`` equals its filename without the ``.json`` suffix."""

    assert _load(path)["id"] == path.stem


@pytest.mark.parametrize("path", _template_files(), ids=lambda p: p.name)
def test_prompt_placeholders_match_required_params(path: Path) -> None:
    """Every ``{placeholder}`` is declared, and every declared param is used."""

    payload = _load(path)
    placeholders = _placeholders(str(payload["prompt_template"]))
    declared = set(payload["required_params"])

    assert placeholders == declared, (
        f"{path.name}: prompt placeholders {placeholders} "
        f"differ from required_params {declared}"
    )

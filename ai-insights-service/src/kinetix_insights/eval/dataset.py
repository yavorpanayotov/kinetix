"""Loading and validating the golden eval dataset.

The dataset is a JSON array of :class:`GoldenCase` rows shipped alongside
this module under ``golden/dataset.json``. Keeping it as data (not code)
means the suite can grow without touching the runner, and the same file
feeds both the offline and live modes.
"""

from __future__ import annotations

import json
from pathlib import Path

from pydantic import TypeAdapter

from kinetix_insights.eval.models import GoldenCase

_GOLDEN_DIR = Path(__file__).parent / "golden"
DEFAULT_DATASET_PATH = _GOLDEN_DIR / "dataset.json"

_CASES_ADAPTER = TypeAdapter(list[GoldenCase])


def load_cases(path: Path | None = None) -> list[GoldenCase]:
    """Load and validate every golden case from ``path``.

    Raises ``pydantic.ValidationError`` if any row is malformed and
    ``ValueError`` if case ids are not unique — a duplicate id would let
    one case silently shadow another in a parametrised run.
    """

    dataset_path = path or DEFAULT_DATASET_PATH
    raw = json.loads(dataset_path.read_text())
    cases = _CASES_ADAPTER.validate_python(raw)

    ids = [case.id for case in cases]
    duplicates = sorted({cid for cid in ids if ids.count(cid) > 1})
    if duplicates:
        raise ValueError(f"duplicate golden case ids: {duplicates}")

    return cases

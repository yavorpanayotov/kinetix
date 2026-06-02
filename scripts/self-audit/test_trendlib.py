"""Stdlib unit tests for the self-audit trend library.

Run with ``python3 -m unittest`` from this directory, or
``python3 scripts/self-audit/test_trendlib.py``.
"""

from __future__ import annotations

import json
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

import trendlib


class BuildRecordTests(unittest.TestCase):
    def test_record_has_all_schema_keys(self) -> None:
        # An empty temp dir has no git/specs/bd — collectors should record
        # None for those metrics rather than crash, and the record must
        # still validate against the schema.
        with tempfile.TemporaryDirectory() as tmp:
            record = trendlib.build_record(
                Path(tmp), now=datetime(2026, 6, 2, tzinfo=timezone.utc)
            )
        self.assertEqual(tuple(record.keys()), trendlib.RECORD_KEYS)
        trendlib.validate_record(record)  # must not raise

    def test_record_is_json_serialisable(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            record = trendlib.build_record(Path(tmp))
        # Round-trips cleanly — this is what gets written as a JSONL line.
        json.loads(json.dumps(record))

    def test_date_is_derived_from_now(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            record = trendlib.build_record(
                Path(tmp), now=datetime(2026, 6, 2, 9, 30, tzinfo=timezone.utc)
            )
        self.assertEqual(record["date"], "2026-06-02")


class ValidateRecordTests(unittest.TestCase):
    def _valid(self) -> dict[str, object]:
        return {
            "timestamp": "2026-06-02T00:00:00+00:00",
            "date": "2026-06-02",
            "git_commit": "abc1234",
            "spec_count": 25,
            "allium_errors": 0,
            "allium_warnings": 3,
            "allium_infos": 12,
            "divergence_reports": 2,
            "open_issues": 5,
            "closed_issues": 50,
        }

    def test_accepts_a_well_formed_record(self) -> None:
        trendlib.validate_record(self._valid())

    def test_rejects_missing_key(self) -> None:
        record = self._valid()
        del record["spec_count"]
        with self.assertRaises(ValueError):
            trendlib.validate_record(record)

    def test_rejects_non_int_metric(self) -> None:
        record = self._valid()
        record["open_issues"] = "five"
        with self.assertRaises(ValueError):
            trendlib.validate_record(record)

    def test_allows_null_metric(self) -> None:
        record = self._valid()
        record["open_issues"] = None
        trendlib.validate_record(record)  # null = "not measured"


class AppendAndRenderTests(unittest.TestCase):
    def test_append_then_load_round_trips(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            record = trendlib.build_record(
                root, now=datetime(2026, 6, 2, tzinfo=timezone.utc)
            )
            trendlib.append_record(root, record)
            loaded = trendlib.load_records(root)
        self.assertEqual(len(loaded), 1)
        self.assertEqual(loaded[0]["date"], "2026-06-02")

    def test_render_markdown_has_sections(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            trendlib.append_record(
                root,
                trendlib.build_record(
                    root, now=datetime(2026, 6, 2, tzinfo=timezone.utc)
                ),
            )
            markdown = trendlib.render_markdown(trendlib.load_records(root))
        self.assertIn("# Self-Audit Trend", markdown)
        self.assertIn("## Runs", markdown)


if __name__ == "__main__":
    unittest.main()

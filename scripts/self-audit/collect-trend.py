#!/usr/bin/env python3
"""Append one self-audit trend record to ``docs/ops/self-audit-trend.jsonl``.

Run from anywhere::

    python3 scripts/self-audit/collect-trend.py

Stdlib only. Gathers spec/diagnostic/divergence/issue counts and appends
a single JSON line. See ``trendlib`` for the metric definitions.
"""

from __future__ import annotations

from trendlib import append_record, build_record, repo_root


def main() -> int:
    root = repo_root()
    record = build_record(root)
    path = append_record(root, record)
    print(f"appended trend record for {record['date']} to {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

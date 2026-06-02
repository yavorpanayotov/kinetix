#!/usr/bin/env python3
"""Regenerate ``docs/ops/self-audit-trend.md`` from the trend JSONL.

Run from anywhere::

    python3 scripts/self-audit/render-trend.py

Stdlib only. Reads every record from ``docs/ops/self-audit-trend.jsonl``
and writes a Markdown report with per-metric sparklines and a run table.
"""

from __future__ import annotations

from trendlib import TREND_MARKDOWN, load_records, render_markdown, repo_root


def main() -> int:
    root = repo_root()
    records = load_records(root)
    markdown = render_markdown(records)

    out = root / TREND_MARKDOWN
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(markdown, encoding="utf-8")
    print(f"wrote {out} from {len(records)} record(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

"""Shared logic for the nightly self-audit trend artefacts.

The self-audit routine (see ``docs/ops/nightly-self-audit.md``) runs the
agentic checks — ``/weed``, ``/code-review``, ``/dep-audit``, ``/health``
— but the *quantitative* spine of "is the codebase drifting?" is a small
set of counts captured every run: how many specs, how many Allium
structural diagnostics, how many divergence reports on file, how many
open vs. closed issues. This module gathers those counts and renders the
trend, with no third-party dependencies (stdlib only) so it runs under a
plain ``python3`` on any machine.

Every collector is defensive: a missing CLI (``allium``, ``bd``, ``git``)
records ``None`` for that metric rather than aborting the run, so a
partial environment still produces a valid trend row.
"""

from __future__ import annotations

import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

# The canonical trend files, relative to the repo root.
TREND_JSONL = Path("docs/ops/self-audit-trend.jsonl")
TREND_MARKDOWN = Path("docs/ops/self-audit-trend.md")

# Every metric key a trend record must carry. Numeric metrics are int or
# None (None = "could not be measured in this environment").
RECORD_KEYS: tuple[str, ...] = (
    "timestamp",
    "date",
    "git_commit",
    "spec_count",
    "allium_errors",
    "allium_warnings",
    "allium_infos",
    "divergence_reports",
    "open_issues",
    "closed_issues",
)

_NUMERIC_KEYS: tuple[str, ...] = (
    "spec_count",
    "allium_errors",
    "allium_warnings",
    "allium_infos",
    "divergence_reports",
    "open_issues",
    "closed_issues",
)


def _run(args: list[str], cwd: Path) -> str | None:
    """Run a command, returning stdout, or None on any failure.

    ``allium check`` exits non-zero when diagnostics exist, so a non-zero
    return code is NOT treated as failure — we still want the JSON it
    printed. We only fall back to None when the command could not run or
    produced nothing on stdout.
    """

    try:
        result = subprocess.run(
            args,
            cwd=cwd,
            capture_output=True,
            text=True,
            timeout=120,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    return result.stdout or None


def _iter_json_values(text: str):
    """Yield each top-level JSON value from a stream of concatenated values.

    ``json.loads`` rejects ``{...}{...}``; ``raw_decode`` parses one value
    and reports where it stopped, so we can walk the whole stream.
    """

    decoder = json.JSONDecoder()
    index = 0
    length = len(text)
    while index < length:
        while index < length and text[index].isspace():
            index += 1
        if index >= length:
            break
        value, end = decoder.raw_decode(text, index)
        yield value
        index = end


def _git_commit(root: Path) -> str | None:
    out = _run(["git", "rev-parse", "--short", "HEAD"], root)
    return out.strip() if out else None


def _spec_count(root: Path) -> int | None:
    specs_dir = root / "specs"
    if not specs_dir.is_dir():
        return None
    return len(list(specs_dir.glob("*.allium")))


def _allium_diagnostics(root: Path) -> dict[str, int | None]:
    """Tally Allium structural diagnostics by severity across all specs."""

    blank: dict[str, int | None] = {
        "allium_errors": None,
        "allium_warnings": None,
        "allium_infos": None,
    }
    out = _run(["allium", "check", "specs"], root)
    if not out:
        return blank
    # `allium check <dir>` prints one JSON object per spec file,
    # concatenated — not a single document. Decode the stream of values.
    try:
        payloads = list(_iter_json_values(out))
    except json.JSONDecodeError:
        return blank
    if not payloads:
        return blank
    counts = {"error": 0, "warning": 0, "info": 0}
    for payload in payloads:
        for diagnostic in payload.get("diagnostics", []):
            severity = str(diagnostic.get("severity", "")).lower()
            if severity in counts:
                counts[severity] += 1
    return {
        "allium_errors": counts["error"],
        "allium_warnings": counts["warning"],
        "allium_infos": counts["info"],
    }


def _divergence_reports(root: Path) -> int | None:
    divergences = root / "specs" / "divergences"
    if not divergences.is_dir():
        return None
    # Count dated report directories plus any top-level report markdown.
    dirs = [p for p in divergences.iterdir() if p.is_dir()]
    files = [p for p in divergences.glob("*.md")]
    return len(dirs) + len(files)


def _issue_count(root: Path, status: str) -> int | None:
    out = _run(["bd", "list", f"--status={status}", "--json"], root)
    if not out:
        return None
    try:
        payload = json.loads(out)
    except json.JSONDecodeError:
        return None
    if isinstance(payload, list):
        return len(payload)
    if isinstance(payload, dict) and isinstance(payload.get("issues"), list):
        return len(payload["issues"])
    return None


def build_record(root: Path, *, now: datetime | None = None) -> dict[str, Any]:
    """Gather one trend record for the repo at ``root``."""

    stamp = now or datetime.now(timezone.utc)
    record: dict[str, Any] = {
        "timestamp": stamp.isoformat(),
        "date": stamp.date().isoformat(),
        "git_commit": _git_commit(root),
        "spec_count": _spec_count(root),
        "divergence_reports": _divergence_reports(root),
        "open_issues": _issue_count(root, "open"),
        "closed_issues": _issue_count(root, "closed"),
    }
    record.update(_allium_diagnostics(root))
    # Re-key in canonical order for stable, diff-friendly JSON lines.
    return {key: record[key] for key in RECORD_KEYS}


def validate_record(record: dict[str, Any]) -> None:
    """Raise ``ValueError`` if ``record`` does not match the trend schema."""

    missing = [key for key in RECORD_KEYS if key not in record]
    if missing:
        raise ValueError(f"trend record missing keys: {missing}")
    if not isinstance(record["timestamp"], str) or not record["timestamp"]:
        raise ValueError("timestamp must be a non-empty string")
    if not isinstance(record["date"], str) or not record["date"]:
        raise ValueError("date must be a non-empty string")
    if record["git_commit"] is not None and not isinstance(record["git_commit"], str):
        raise ValueError("git_commit must be a string or null")
    for key in _NUMERIC_KEYS:
        value = record[key]
        if value is not None and not isinstance(value, int):
            raise ValueError(f"{key} must be an int or null, got {value!r}")


def append_record(root: Path, record: dict[str, Any]) -> Path:
    """Validate and append ``record`` as one JSON line to the trend log."""

    validate_record(record)
    path = root / TREND_JSONL
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(record) + "\n")
    return path


def load_records(root: Path) -> list[dict[str, Any]]:
    """Read every trend record from the JSONL log (empty if absent)."""

    path = root / TREND_JSONL
    if not path.exists():
        return []
    records: list[dict[str, Any]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            records.append(json.loads(line))
    return records


def _sparkline(values: list[int | None]) -> str:
    """Render a unicode sparkline over the non-null values."""

    bars = "▁▂▃▄▅▆▇█"
    nums = [v for v in values if isinstance(v, int)]
    if not nums:
        return "—"
    lo, hi = min(nums), max(nums)
    span = hi - lo or 1
    out = []
    for v in values:
        if not isinstance(v, int):
            out.append(" ")
            continue
        idx = round((v - lo) / span * (len(bars) - 1))
        out.append(bars[idx])
    return "".join(out)


def render_markdown(records: list[dict[str, Any]]) -> str:
    """Render the trend log as a Markdown report with sparklines."""

    lines: list[str] = []
    a = lines.append
    a("# Self-Audit Trend")
    a("")
    a(
        "> Quantitative spine of the nightly self-audit "
        "(see [`nightly-self-audit.md`](nightly-self-audit.md)). "
        "Generated by `scripts/self-audit/render-trend.py`; do not hand-edit."
    )
    a("")

    if not records:
        a("_No trend data captured yet._")
        a("")
        return "\n".join(lines)

    a("## Trend at a glance")
    a("")
    a("| Metric | Sparkline | Latest |")
    a("| --- | --- | --- |")
    metric_labels = {
        "allium_errors": "Allium errors",
        "allium_warnings": "Allium warnings",
        "spec_count": "Specs",
        "divergence_reports": "Divergence reports",
        "open_issues": "Open issues",
        "closed_issues": "Closed issues",
    }
    for key, label in metric_labels.items():
        series = [r.get(key) for r in records]
        latest = series[-1]
        latest_str = "—" if latest is None else str(latest)
        a(f"| {label} | `{_sparkline(series)}` | {latest_str} |")
    a("")

    a("## Runs")
    a("")
    a("| Date | Commit | Specs | Errors | Warnings | Divergences | Open | Closed |")
    a("| --- | --- | --- | --- | --- | --- | --- | --- |")
    for r in records:
        def cell(key: str) -> str:
            value = r.get(key)
            return "—" if value is None else str(value)

        commit = r.get("git_commit") or "—"
        a(
            f"| {r.get('date', '—')} | `{commit}` | {cell('spec_count')} "
            f"| {cell('allium_errors')} | {cell('allium_warnings')} "
            f"| {cell('divergence_reports')} | {cell('open_issues')} "
            f"| {cell('closed_issues')} |"
        )
    a("")
    return "\n".join(lines)


def repo_root() -> Path:
    """Repo root, derived from this file's location (scripts/self-audit/)."""

    return Path(__file__).resolve().parents[2]

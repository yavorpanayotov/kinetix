#!/usr/bin/env python3
"""
Coverage ratchet check.

Reads the per-stack coverage outputs and fails if any drops below the
recorded baseline minus a small tolerance (0.5pp by default). Intended to
run after the test+coverage steps in CI.

Baselines live in `coverage-baselines.json` at the repo root. Update them
when coverage genuinely improves; do not lower them to absorb regressions
(that's the whole point of the ratchet).

Stacks supported today:
- UI: reads ui/coverage/coverage-summary.json (Vitest json-summary)
- Python: reads risk-engine/reports/coverage.xml (pytest-cov XML)
- Kotlin: reads MODULE/build/reports/kover/report.xml per module
"""
from __future__ import annotations

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

DEFAULT_TOLERANCE_PP = 0.5
REPO_ROOT = Path(__file__).resolve().parent.parent
BASELINE_PATH = REPO_ROOT / "coverage-baselines.json"


def load_baselines() -> dict:
    if not BASELINE_PATH.exists():
        return {}
    return json.loads(BASELINE_PATH.read_text())


def ui_line_coverage() -> float | None:
    path = REPO_ROOT / "ui" / "coverage" / "coverage-summary.json"
    if not path.exists():
        return None
    data = json.loads(path.read_text())
    return float(data["total"]["lines"]["pct"])


def python_line_coverage() -> float | None:
    path = REPO_ROOT / "risk-engine" / "reports" / "coverage.xml"
    if not path.exists():
        return None
    root = ET.parse(path).getroot()
    # Cobertura XML: line-rate is a 0-1 float on the <coverage> root.
    line_rate = root.attrib.get("line-rate")
    if line_rate is None:
        return None
    return round(float(line_rate) * 100, 2)


def kover_module_line_coverage(module: str) -> float | None:
    path = REPO_ROOT / module / "build" / "reports" / "kover" / "report.xml"
    if not path.exists():
        return None
    root = ET.parse(path).getroot()
    # Kover XML follows the JaCoCo schema. Look for the LINE counter at root.
    for counter in root.findall("counter"):
        if counter.attrib.get("type") == "LINE":
            covered = int(counter.attrib["covered"])
            missed = int(counter.attrib["missed"])
            total = covered + missed
            if total == 0:
                return None
            return round(covered * 100 / total, 2)
    return None


def check(name: str, current: float | None, baseline: float, tolerance: float) -> bool:
    if current is None:
        print(f"  [{name}] no current coverage data — skipping")
        return True
    floor = baseline - tolerance
    ok = current >= floor
    status = "OK" if ok else "RATCHET BREACHED"
    print(f"  [{name}] current={current:.2f}% baseline={baseline:.2f}% floor={floor:.2f}% — {status}")
    return ok


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--tolerance",
        type=float,
        default=DEFAULT_TOLERANCE_PP,
        help="Allowed drop in percentage points (default: 0.5)",
    )
    args = parser.parse_args()

    baselines = load_baselines()
    if not baselines:
        print(
            "No baselines recorded yet. Run tests + coverage on main, "
            "copy the percentages into coverage-baselines.json, and re-run."
        )
        return 0

    print(f"Coverage ratchet check (tolerance: {args.tolerance}pp)")

    all_ok = True

    if "ui" in baselines:
        all_ok &= check("UI", ui_line_coverage(), baselines["ui"], args.tolerance)

    if "python" in baselines:
        all_ok &= check("Python", python_line_coverage(), baselines["python"], args.tolerance)

    for module, baseline in baselines.get("kotlin", {}).items():
        all_ok &= check(
            f"Kotlin:{module}",
            kover_module_line_coverage(module),
            baseline,
            args.tolerance,
        )

    if not all_ok:
        print(
            "\nOne or more stacks dropped below the ratchet floor. "
            "Either strengthen tests to recover coverage, or — if the drop "
            "is intentional and reviewed — lower the baseline in coverage-baselines.json."
        )
        return 1
    print("\nAll ratchets respected.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

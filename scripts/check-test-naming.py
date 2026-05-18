#!/usr/bin/env python3
"""
Test-naming convention check.

Scans test files for names that look implementation-flavoured rather than
specification-flavoured and prints warnings. Designed to be a soft signal
during PR review, not a hard CI gate (at least initially).

Flagged patterns:
- `test foo bar`, `test_foo_bar`, `testFooBar` — names that don't read as a
  specification
- `testWorks`, `it_works`, `does_thing` — vague names
- `testHappy`, `testFlow` — placeholder names

Counter-examples (good):
- `"rejects a trade when the position limit is exceeded"`
- `"returns the latest matrix when multiple are present"`
- `"def test_put_call_parity_holds_for_all_inputs"`

Scoped to Kotlin Kotest `test("...")` blocks, Kotlin `@Test fun ...`,
Python `def test_...`, and Vitest `it(...)`.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

VAGUE_NAMES = {
    "works", "it_works", "happy", "happy_path", "flow", "main", "basic",
    "smoke", "simple", "test", "does_thing", "succeeds", "passes",
}

# Short tokens that legitimately appear at the start of a test description —
# ISO-4217 currency majors and common instrument types. A description that
# opens with one of these (e.g. "USD-TREASURY redemption", "BOND coupon
# accrual", "FX swap settlement") should not be flagged as
# implementation-flavoured just because the first token is short.
ALLOW_LIST_PREFIXES = {
    # ISO-4217 majors
    "USD", "EUR", "GBP", "JPY", "CHF", "AUD", "NZD", "CAD", "CNY", "HKD",
    "SGD", "SEK", "NOK", "DKK",
    # Common instrument types
    "BOND", "EQUITY", "FX", "IRS", "OIS", "CDS", "FRA", "ETF", "FUTURE",
}

# Pattern of test names that look implementation-flavoured
# - testFooBar, test_foo_bar (camelCase or snake_case without spaces)
# - Names <= 2 words probably can't read as a specification
#
# IMPL_STYLE_KOTEST matches *only* the Kotest test-description form:
#     test("description") {
# (with optional whitespace before the opening brace). Requiring the
# block body excludes ordinary function calls like `findLatest("USD")`
# or `test("USD")` used as parameterised data values, which are not
# Kotest test declarations and were tripping false positives.
IMPL_STYLE_KOTEST = re.compile(r'\btest\(\s*"([^"]{1,40})"\s*\)\s*\{')
IMPL_STYLE_KOTLIN_JUNIT = re.compile(r'@Test\s+fun\s+`([^`]+)`')
IMPL_STYLE_KOTLIN_JUNIT_BARE = re.compile(r'@Test\s+fun\s+([a-zA-Z][A-Za-z_0-9]*)\s*\(')
IMPL_STYLE_PYTHON = re.compile(r'^\s*(?:async\s+)?def\s+(test_[a-zA-Z_0-9]+)\s*\(')
IMPL_STYLE_VITEST = re.compile(r'(?:^|\W)it\(\s*[\'"]([^\'"]{1,40})[\'"]')


def is_vague(name: str) -> bool:
    normalised = name.strip().lower().replace("-", "_").replace(" ", "_")
    return normalised in VAGUE_NAMES


def looks_implementation_flavoured(name: str) -> bool:
    """Heuristic: a spec-style name has at least 3 words separated by spaces.

    Descriptions that open with an allow-listed short identifier (currency
    code, instrument type) are exempt — the first token is treated as
    domain shorthand rather than counted toward the impl-style score.
    """
    stripped = name.strip()
    first_token = stripped.split(maxsplit=1)[0] if stripped else ""
    # Treat the leading dash-segment as the candidate token so e.g.
    # "USD-TREASURY ..." matches "USD" against the allow-list.
    leading_segment = first_token.split("-")[0].upper()
    if leading_segment in ALLOW_LIST_PREFIXES:
        return False
    word_count = len(stripped.split())
    if word_count >= 4:
        return False
    if "_" in stripped and " " not in stripped:
        # snake_case identifier — Python style — not necessarily bad.
        underscores = stripped.count("_")
        return underscores <= 2
    return word_count <= 2


def scan_kotlin(file: Path) -> list[tuple[int, str]]:
    findings = []
    try:
        lines = file.read_text().splitlines()
    except UnicodeDecodeError:
        return findings
    for i, line in enumerate(lines, 1):
        for pat in (IMPL_STYLE_KOTEST,):
            for m in pat.finditer(line):
                name = m.group(1)
                if is_vague(name):
                    findings.append((i, f"vague name: {name!r}"))
                elif looks_implementation_flavoured(name):
                    findings.append((i, f"short/implementation-flavoured: {name!r}"))
        for m in IMPL_STYLE_KOTLIN_JUNIT_BARE.finditer(line):
            name = m.group(1)
            if is_vague(name):
                findings.append((i, f"vague @Test fun name: {name!r}"))
    return findings


def scan_python(file: Path) -> list[tuple[int, str]]:
    findings = []
    try:
        lines = file.read_text().splitlines()
    except UnicodeDecodeError:
        return findings
    for i, line in enumerate(lines, 1):
        m = IMPL_STYLE_PYTHON.match(line)
        if not m:
            continue
        name = m.group(1)[len("test_"):]
        if is_vague(name) or len(name) <= 6:
            findings.append((i, f"short/vague python test name: {name!r}"))
    return findings


def scan_typescript(file: Path) -> list[tuple[int, str]]:
    findings = []
    try:
        lines = file.read_text().splitlines()
    except UnicodeDecodeError:
        return findings
    for i, line in enumerate(lines, 1):
        for m in IMPL_STYLE_VITEST.finditer(line):
            name = m.group(1)
            if is_vague(name):
                findings.append((i, f"vague it(...) description: {name!r}"))
            elif looks_implementation_flavoured(name):
                findings.append((i, f"short it(...) description: {name!r}"))
    return findings


def run_self_test() -> int:
    """Exercise IMPL_STYLE_KOTEST against in-memory fixtures.

    Returns 0 if the regex behaves as expected, non-zero otherwise.
    The fixture deliberately contains:
      - one impl-style declaration: ``test("foo") {}``
      - one descriptive declaration: ``test("rejects a trade ...") {}``
      - one parameterised data value: ``findLatest("USD")``  (must NOT match)
      - one shorthand call: ``test("USD")`` with no block body (must NOT match)
    """
    impl_style_src = 'test("foo") {}\n'
    # Kept within IMPL_STYLE_KOTEST's 40-char description ceiling so the
    # regex still captures it; the downstream classifier should then
    # decide that a 4-word description is NOT impl-flavoured.
    descriptive_name = "rejects a trade when limit exceeded"
    descriptive_src = f'test("{descriptive_name}") {{}}\n'
    non_decl_src = '''val found = repository.findLatest("USD")\ntest("USD")\n'''

    impl_matches = [m.group(1) for m in IMPL_STYLE_KOTEST.finditer(impl_style_src)]
    desc_matches = [m.group(1) for m in IMPL_STYLE_KOTEST.finditer(descriptive_src)]
    non_decl_matches = [m.group(1) for m in IMPL_STYLE_KOTEST.finditer(non_decl_src)]

    failures: list[str] = []
    if impl_matches != ["foo"]:
        failures.append(f"expected ['foo'] from impl-style fixture, got {impl_matches!r}")
    if desc_matches != [descriptive_name]:
        failures.append(f"expected descriptive match, got {desc_matches!r}")
    if non_decl_matches:
        failures.append(
            f"expected zero matches in parameterised/non-declaration fixture, got {non_decl_matches!r}"
        )

    # The impl-style match should still be classified as flagged downstream;
    # the descriptive one should not be.
    if not looks_implementation_flavoured("foo"):
        failures.append("expected 'foo' to be classified as implementation-flavoured")
    if looks_implementation_flavoured(descriptive_name):
        failures.append("expected descriptive name to NOT be flagged")

    # End-to-end check: drive scan_kotlin against temp files. The impl-style
    # fixture must produce findings; the descriptive + parameterised fixture
    # must produce none.
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        impl_file = Path(tmp) / "ImplStyleTest.kt"
        impl_file.write_text(impl_style_src)
        good_file = Path(tmp) / "DescriptiveTest.kt"
        good_file.write_text(descriptive_src + non_decl_src)

        impl_findings = scan_kotlin(impl_file)
        good_findings = scan_kotlin(good_file)

        if not impl_findings:
            failures.append(
                f"scan_kotlin produced no findings for impl-style fixture {impl_file.name}"
            )
        if good_findings:
            failures.append(
                f"scan_kotlin produced unexpected findings for descriptive fixture: {good_findings!r}"
            )

    if failures:
        print("self-test FAILED:")
        for f in failures:
            print(f"  - {f}")
        return 1
    print("self-test OK")
    return 0


def main() -> int:
    if "--self-test" in sys.argv[1:]:
        return run_self_test()

    skipped = ("build/", "node_modules/", ".gradle/", ".claude/", "dist/")

    total = 0
    for _stack, scanner, pattern in (
        ("kotlin", scan_kotlin, "**/src/test/**/*.kt"),
        ("python", scan_python, "risk-engine/tests/test_*.py"),
        ("ui", scan_typescript, "ui/src/**/*.test.{ts,tsx}"),
        ("ui-e2e", scan_typescript, "ui/e2e/**/*.spec.ts"),
    ):
        for path in REPO_ROOT.rglob(pattern.split("/")[-1]):
            rel = path.relative_to(REPO_ROOT)
            if any(s in str(rel) for s in skipped):
                continue
            if pattern.startswith("**/src/test") and "src/test" not in str(rel):
                continue
            if "risk-engine/tests" in pattern and "risk-engine/tests" not in str(rel):
                continue
            if "ui/src" in pattern and "ui/src" not in str(rel):
                continue
            if "ui/e2e" in pattern and "ui/e2e" not in str(rel):
                continue
            findings = scanner(path)
            for line_no, msg in findings:
                print(f"{rel}:{line_no}: {msg}")
                total += 1

    if total:
        print(f"\n{total} test-naming warnings — consider rewriting as specifications.")
    else:
        print("All test names look spec-flavoured. ✓")
    # Warning-only: do not fail the build. Promote to non-zero exit
    # once the codebase is clean and the team agrees to enforce.
    return 0


if __name__ == "__main__":
    sys.exit(main())

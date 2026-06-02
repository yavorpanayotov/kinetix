"""Schema and coverage checks for the golden eval dataset.

These run under the default suite (marked ``unit``) so a malformed
dataset row is caught on every test run, not only when the eval suite is
explicitly selected. The actual scoring lives in the ``eval``-marked
suite.
"""

from __future__ import annotations

import pytest

from kinetix_insights.eval.dataset import load_cases
from kinetix_insights.eval.models import EvalVerdict, GoldenCase


@pytest.fixture(scope="module")
def cases() -> list[GoldenCase]:
    return load_cases()


@pytest.mark.unit
def test_dataset_loads_and_validates(cases: list[GoldenCase]) -> None:
    assert cases, "golden dataset is empty"
    assert all(isinstance(c, GoldenCase) for c in cases)


@pytest.mark.unit
def test_case_ids_are_unique(cases: list[GoldenCase]) -> None:
    ids = [c.id for c in cases]
    assert len(ids) == len(set(ids)), "duplicate golden case ids"


@pytest.mark.unit
def test_every_verdict_is_represented(cases: list[GoldenCase]) -> None:
    verdicts = {c.expected_verdict for c in cases}
    assert verdicts == set(EvalVerdict), (
        "dataset must exercise every verdict; missing "
        f"{set(EvalVerdict) - verdicts}"
    )


@pytest.mark.unit
def test_adversarial_categories_present(cases: list[GoldenCase]) -> None:
    categories = {c.category for c in cases}
    required = {
        "clean",
        "uncited_number",
        "hallucinated_ticker",
        "banned_phrase",
        "policy_precedence",
        "prompt_injection",
    }
    assert required <= categories, f"missing categories: {required - categories}"


@pytest.mark.unit
def test_injection_cases_declare_expected_labels(cases: list[GoldenCase]) -> None:
    injection_cases = [c for c in cases if c.category == "prompt_injection"]
    assert injection_cases
    assert all(c.expected_injection_labels for c in injection_cases), (
        "every prompt_injection case must declare the sanitiser labels it expects"
    )


@pytest.mark.unit
def test_non_injection_cases_declare_no_labels(cases: list[GoldenCase]) -> None:
    for case in cases:
        if case.category != "prompt_injection":
            assert not case.expected_injection_labels, (
                f"{case.id}: only prompt_injection cases should expect labels"
            )

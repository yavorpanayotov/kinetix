"""Live copilot eval suite (``pytest -m eval_live``).

These tests call the real Claude model through the production chat
pipeline and score the answers with an LLM-as-judge. They are gated on
``ANTHROPIC_API_KEY`` (and a successful SDK import) via
:func:`live_available`, so the suite skips cleanly in CI and on any
machine without credentials — ``pytest -m eval_live`` then passes by
skipping rather than failing.

When a key IS present but the live transport turns out to be unreachable
at call time, the individual test skips (rather than fails) so a flaky or
unconfigured transport never blocks the build.
"""

from __future__ import annotations

import pytest

from kinetix_insights.eval.dataset import load_cases
from kinetix_insights.eval.live import (
    LiveTransportUnavailable,
    live_available,
    run_live_case,
)

pytestmark = [
    pytest.mark.eval_live,
    pytest.mark.skipif(
        not live_available(),
        reason="live eval requires ANTHROPIC_API_KEY and the Claude Agent SDK",
    ),
]

# A small, grounded subset — clean cases with known facts the model can
# quote. Live runs are slow and cost API calls, so we keep the live
# sample tight; the offline suite carries the exhaustive coverage.
_LIVE_CASES = [
    c for c in load_cases() if c.category == "clean" and c.citations
]


@pytest.mark.asyncio
@pytest.mark.parametrize("case", _LIVE_CASES, ids=[c.id for c in _LIVE_CASES])
async def test_live_answer_is_faithful_to_grounding(case) -> None:
    try:
        result = await run_live_case(case)
    except LiveTransportUnavailable as exc:
        pytest.skip(str(exc))

    # The pipeline ran end-to-end without an upstream failure.
    assert result.error_code != "UPSTREAM_ERROR"

    # If the model produced prose, the judge must find it faithful to the
    # facts it was grounded with — i.e. no invented figures or tickers.
    if result.judge is not None:
        assert result.judge.faithful, (
            f"{case.id}: judge ruled the live answer unfaithful — "
            f"{result.judge.reason}\nanswer: {result.narrative}"
        )

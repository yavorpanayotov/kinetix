"""Unit tests for :class:`CachingInsightClient`.

The wrapper must memoise identical requests, distinguish distinct
payloads, canonicalise dictionary key order, and evict least-recently
used entries when ``maxsize`` is exceeded.
"""

from __future__ import annotations

import pytest

from kinetix_insights.cache import CachingInsightClient
from kinetix_insights.models import InsightRequest, InsightResponse

pytestmark = pytest.mark.unit


class CountingClient:
    """Test double that records how many times ``explain`` was invoked.

    Returns a response whose ``narrative`` encodes the request payload so
    tests can assert that distinct requests yielded distinct responses.
    """

    def __init__(self) -> None:
        self.call_count = 0

    async def explain(self, request: InsightRequest) -> InsightResponse:
        self.call_count += 1
        return InsightResponse(
            narrative=f"call-{self.call_count}:{request.kind}",
            bullets=[],
            model="counting",
            mode="canned",
        )


@pytest.mark.asyncio
async def test_same_request_returns_same_response_without_reinvoking_inner() -> None:
    inner = CountingClient()
    wrapper = CachingInsightClient(inner)
    request = InsightRequest(
        kind="var",
        payload={"as_of": "2026-05-18", "confidence": 0.99, "horizon_days": 1},
    )

    first = await wrapper.explain(request)
    second = await wrapper.explain(request)

    assert first == second
    assert inner.call_count == 1


@pytest.mark.asyncio
async def test_different_payloads_invoke_inner_separately() -> None:
    inner = CountingClient()
    wrapper = CachingInsightClient(inner)
    request_a = InsightRequest(kind="var", payload={"confidence": 0.99})
    request_b = InsightRequest(kind="var", payload={"confidence": 0.95})

    await wrapper.explain(request_a)
    await wrapper.explain(request_b)

    assert inner.call_count == 2


@pytest.mark.asyncio
async def test_different_kinds_with_same_payload_invoke_inner_separately() -> None:
    inner = CountingClient()
    wrapper = CachingInsightClient(inner)
    payload = {"shared": "payload"}

    await wrapper.explain(InsightRequest(kind="var", payload=payload))
    await wrapper.explain(InsightRequest(kind="report", payload=payload))

    assert inner.call_count == 2


@pytest.mark.asyncio
async def test_key_is_canonical_across_payload_dict_key_order() -> None:
    inner = CountingClient()
    wrapper = CachingInsightClient(inner)
    request_ab = InsightRequest(kind="var", payload={"a": 1, "b": 2})
    request_ba = InsightRequest(kind="var", payload={"b": 2, "a": 1})

    first = await wrapper.explain(request_ab)
    second = await wrapper.explain(request_ba)

    assert first == second
    assert inner.call_count == 1


@pytest.mark.asyncio
async def test_lru_eviction_at_maxsize() -> None:
    inner = CountingClient()
    wrapper = CachingInsightClient(inner, maxsize=2)
    request_a = InsightRequest(kind="var", payload={"id": "a"})
    request_b = InsightRequest(kind="var", payload={"id": "b"})
    request_c = InsightRequest(kind="var", payload={"id": "c"})

    await wrapper.explain(request_a)  # cache: [a]
    await wrapper.explain(request_b)  # cache: [a, b]
    await wrapper.explain(request_c)  # cache: [b, c]; a evicted
    await wrapper.explain(request_a)  # cache miss; re-fetched

    assert inner.call_count == 4


@pytest.mark.asyncio
async def test_lru_recency_moves_on_hit() -> None:
    inner = CountingClient()
    wrapper = CachingInsightClient(inner, maxsize=2)
    request_a = InsightRequest(kind="var", payload={"id": "a"})
    request_b = InsightRequest(kind="var", payload={"id": "b"})
    request_c = InsightRequest(kind="var", payload={"id": "c"})

    await wrapper.explain(request_a)  # cache: [a]                  inner=1
    await wrapper.explain(request_b)  # cache: [a, b]               inner=2
    await wrapper.explain(request_a)  # hit; cache: [b, a]          inner=2
    await wrapper.explain(request_c)  # cache: [a, c]; b evicted    inner=3
    await wrapper.explain(request_a)  # hit                         inner=3
    await wrapper.explain(request_b)  # miss; re-fetched            inner=4

    assert inner.call_count == 4


@pytest.mark.asyncio
async def test_zero_or_negative_maxsize_rejected() -> None:
    inner = CountingClient()
    with pytest.raises(ValueError):
        CachingInsightClient(inner, maxsize=0)
    with pytest.raises(ValueError):
        CachingInsightClient(inner, maxsize=-1)


@pytest.mark.asyncio
async def test_default_maxsize_is_256() -> None:
    from kinetix_insights.cache import DEFAULT_MAXSIZE

    assert DEFAULT_MAXSIZE == 256
    inner = CountingClient()
    wrapper = CachingInsightClient(inner)
    assert wrapper._maxsize == 256

"""In-process LRU cache wrapper for :class:`InsightClient` implementations.

The wrapper memoises ``explain`` responses keyed by a canonical hash of
the request (``kind`` plus a deterministic JSON encoding of the payload
with sorted keys). Identical requests return the same cached
:class:`InsightResponse` without re-invoking the underlying client.

Why a hand-rolled :class:`collections.OrderedDict` LRU rather than
``functools.lru_cache``? Two reasons:

1. :class:`InsightRequest` is a Pydantic ``BaseModel`` and is not hashable
   by default, so it cannot be used as an ``lru_cache`` key directly.
2. ``functools.lru_cache`` caches the *return value* of the wrapped call.
   When the wrapped call is ``async def``, that return value is a
   coroutine object, which is single-use — a cache hit would yield an
   already-awaited coroutine and raise ``RuntimeError``. The cache must
   store the *awaited* :class:`InsightResponse`, which requires custom
   async-aware lookup logic.

The OrderedDict-based implementation below preserves LRU semantics
(``move_to_end`` on hit, ``popitem(last=False)`` on eviction) and keeps
the maximum cache size bounded.
"""

from __future__ import annotations

import hashlib
import json
from collections import OrderedDict

from .insights_client import InsightClient
from .models import InsightRequest, InsightResponse

DEFAULT_MAXSIZE = 256


class CachingInsightClient:
    """LRU-cached wrapper around any :class:`InsightClient` implementation.

    The wrapper itself satisfies the :class:`InsightClient` protocol, so
    it composes with the factory and existing routes without changes to
    callers.
    """

    def __init__(self, inner: InsightClient, maxsize: int = DEFAULT_MAXSIZE) -> None:
        if maxsize <= 0:
            raise ValueError("maxsize must be positive")
        self._inner = inner
        self._maxsize = maxsize
        self._cache: OrderedDict[str, InsightResponse] = OrderedDict()

    async def explain(self, request: InsightRequest) -> InsightResponse:
        key = self._key(request)
        if key in self._cache:
            self._cache.move_to_end(key)
            return self._cache[key]
        response = await self._inner.explain(request)
        self._cache[key] = response
        self._cache.move_to_end(key)
        if len(self._cache) > self._maxsize:
            self._cache.popitem(last=False)
        return response

    @staticmethod
    def _key(request: InsightRequest) -> str:
        payload_json = json.dumps(
            request.payload,
            sort_keys=True,
            separators=(",", ":"),
            default=str,
        )
        digest = hashlib.sha256(payload_json.encode("utf-8")).hexdigest()
        return f"{request.kind}:{digest}"

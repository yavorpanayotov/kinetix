from typing import Protocol

from .models import InsightRequest, InsightResponse


class InsightClient(Protocol):
    async def explain(self, request: InsightRequest) -> InsightResponse: ...

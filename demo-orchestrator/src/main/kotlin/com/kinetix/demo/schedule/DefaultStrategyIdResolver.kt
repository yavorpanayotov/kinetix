package com.kinetix.demo.schedule

/**
 * Default [StrategyIdResolver] used by the demo orchestrator at runtime.
 *
 * `risk-orchestrator`'s `DevDataSeeder` does NOT pre-seed `position-service`
 * strategies — strategies are created on-demand via
 * `POST /api/v1/books/{bookId}/strategies`. Until checkbox 2.3 wires
 * a strategy-bootstrap step, this resolver falls back to a deterministic
 * `"{bookId}-default"` id. Operators can override individual mappings via the
 * [overrides] map without changing code.
 *
 * The fallback id is intentionally readable so a 404 from `position-service`
 * surfaces a useful "strategy '<bookId>-default' not found" hint rather than
 * an opaque UUID.
 */
class DefaultStrategyIdResolver(
    private val overrides: Map<String, String> = emptyMap(),
) : StrategyIdResolver {

    override fun strategyIdFor(bookId: String): String =
        overrides[bookId] ?: "$bookId-default"
}

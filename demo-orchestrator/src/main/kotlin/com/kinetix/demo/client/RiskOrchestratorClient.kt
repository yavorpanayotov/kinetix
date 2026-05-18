package com.kinetix.demo.client

import com.kinetix.demo.client.dtos.BookExposureSnapshot
import java.math.BigDecimal

/**
 * Contract over `risk-orchestrator` HTTP APIs that the demo orchestrator
 * relies on to size and seed limits each day.
 *
 * The interface keeps the demo decoupled from Ktor and from the upstream wire
 * shapes so tests can substitute an in-memory fake without spinning up an
 * HTTP server.
 */
interface RiskOrchestratorClient {

    /**
     * Reads the current risk exposure for the given book from
     * `GET /api/v1/risk/hierarchy/BOOK/{bookId}`.
     */
    suspend fun readBookExposure(bookId: String): BookExposureSnapshot

    /**
     * Seeds a limit for the given book at the supplied threshold.
     *
     * Implementations target whatever upstream route is responsible for
     * persisting the limit. The current production implementation falls back
     * to `POST /api/v1/risk/budgets` because risk-orchestrator does not yet
     * expose a dedicated per-book VaR/Delta limit route.
     */
    suspend fun seedLimit(bookId: String, limitType: LimitType, threshold: BigDecimal)
}

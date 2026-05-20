package com.kinetix.demo.client

import com.kinetix.demo.client.dtos.PrimeBrokerStatementRequest
import com.kinetix.demo.client.dtos.RecordExecutionCostRequest
import com.kinetix.demo.client.dtos.StrategyTradeRequest

/**
 * Contract over `position-service` HTTP APIs that the demo orchestrator
 * relies on to book simulated trades against a strategy.
 *
 * The interface keeps the demo decoupled from Ktor and from the upstream wire
 * shapes so tests can substitute an in-memory fake without spinning up an
 * HTTP server.
 */
interface PositionServiceClient {

    /**
     * Books a trade against the supplied strategy by calling
     * `POST /api/v1/books/{bookId}/strategies/{strategyId}/trades`.
     *
     * Returns the resulting `tradeId` — either the value the caller supplied
     * in [request], or the server-generated UUID when [StrategyTradeRequest.tradeId]
     * was null.
     */
    suspend fun bookTrade(
        bookId: String,
        strategyId: String,
        request: StrategyTradeRequest,
    ): String

    /**
     * Records a synthetic execution-cost sample for [bookId] by calling the
     * demo/seed endpoint `POST /api/v1/internal/execution/cost/{bookId}`.
     *
     * Used by the simulator to populate the Trades > Execution Cost subtab —
     * one sample per simulated trade.
     */
    suspend fun recordExecutionCost(
        bookId: String,
        request: RecordExecutionCostRequest,
    )

    /**
     * Uploads a prime-broker statement for [bookId] by calling
     * `POST /api/v1/execution/reconciliation/{bookId}/statements`, which runs
     * server-side reconciliation against the book's internal positions.
     *
     * Used by the simulator to seed reconciliation breaks for the Trades >
     * Reconciliation subtab.
     */
    suspend fun uploadPrimeBrokerStatement(
        bookId: String,
        request: PrimeBrokerStatementRequest,
    )
}

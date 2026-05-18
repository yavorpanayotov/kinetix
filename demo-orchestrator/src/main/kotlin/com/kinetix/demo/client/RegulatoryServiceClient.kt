package com.kinetix.demo.client

import com.kinetix.demo.client.dtos.BacktestRequest
import com.kinetix.demo.client.dtos.BacktestResult
import com.kinetix.demo.client.dtos.CreateSubmissionRequest
import com.kinetix.demo.client.dtos.SubmissionRef

/**
 * Contract over `regulatory-service` HTTP APIs that the demo orchestrator
 * relies on to demo the regulatory workflow: running a VaR backtest for a
 * book, and creating a daily risk summary submission draft.
 *
 * The interface keeps the demo decoupled from Ktor and from the upstream
 * wire shapes so tests can substitute an in-memory fake without spinning up
 * an HTTP server.
 */
interface RegulatoryServiceClient {

    /**
     * Triggers a VaR backtest for [bookId] via
     * `POST /api/v1/regulatory/backtest/{bookId}`.
     */
    suspend fun runBacktest(bookId: String, request: BacktestRequest): BacktestResult

    /**
     * Creates a regulatory submission draft via `POST /api/v1/submissions`.
     */
    suspend fun createSubmission(request: CreateSubmissionRequest): SubmissionRef
}

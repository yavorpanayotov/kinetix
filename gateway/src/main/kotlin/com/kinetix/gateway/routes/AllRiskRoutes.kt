package com.kinetix.gateway.routes

import com.kinetix.gateway.client.PositionServiceClient
import com.kinetix.gateway.client.RiskServiceClient
import io.ktor.server.routing.Route

/**
 * Registers the full risk route suite on a Route receiver. Shared by the
 * `module(RiskServiceClient)` and `module(PositionServiceClient, RiskServiceClient)`
 * test-harness overloads.
 *
 * [positionClient] is forwarded to [counterpartyRiskRoutes] when non-null so
 * that trade-derived counterparties are merged with the credit-risk snapshot
 * set (trader-review P0 #6).
 */
fun Route.allRiskRoutes(riskClient: RiskServiceClient, positionClient: PositionServiceClient? = null) {
    varRoutes(riskClient)
    crossBookVaRRoutes(riskClient)
    hierarchyRiskRoutes(riskClient)
    riskBudgetRoutes(riskClient)
    croReportRoutes(riskClient)
    liquidityRiskRoutes(riskClient)
    marginRoutes(riskClient)
    factorRiskRoutes(riskClient)
    stressTestRoutes(riskClient)
    whatIfRoutes(riskClient)
    preTradeRiskPreviewRoutes(riskClient)
    positionRiskRoutes(riskClient)
    regulatoryRoutes(riskClient)
    dependenciesRoutes(riskClient)
    jobHistoryRoutes(riskClient)
    eodTimelineRoutes(riskClient)
    sodSnapshotRoutes(riskClient)
    runComparisonRoutes(riskClient)
    marketRegimeRoutes(riskClient)
    hedgeRecommendationRoutes(riskClient)
    counterpartyRiskRoutes(riskClient, positionClient)
    keyRateDurationRoutes(riskClient)
    saCcrRoutes(riskClient)
    reportProxyRoutes(riskClient)
    benchmarkAttributionRoutes(riskClient)
}

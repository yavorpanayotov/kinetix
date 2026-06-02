package com.kinetix.gateway.routes

import com.kinetix.gateway.client.PositionServiceClient
import com.kinetix.gateway.client.RiskServiceClient
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put

/**
 * Routes that back the dedicated "Counterparty Risk" tab plus the smaller
 * Counterparty Exposure tile on the Risk tab. The list endpoint `GET
 * /api/v1/counterparty-risk` is the canonical source for the counterparty
 * SET so both surfaces agree on names (trader-review P0 #6).
 *
 * When [positionClient] is provided, the gateway enriches the risk-service
 * snapshot stream with trade-derived counterparties: any counterparty that
 * has booked trades but no exposure snapshot yet appears in the response
 * with sentinel risk metrics (zero net exposure, zero peak PFE, null CVA)
 * so the dedicated tab cannot disagree with the Risk-tab tile on the name
 * set. Without the position client, the route is a transparent passthrough
 * — preserving the legacy contract for module overloads that don't wire a
 * position client.
 */
/**
 * Default deadline for the trade-counterparty enrichment fan-out. The
 * canonical risk-orchestrator snapshot must always come back quickly; the
 * trade-derived placeholder rows are a best-effort consistency nicety
 * (trader-review P0 #6). If the position-service fan-out can't finish inside
 * this window we drop the placeholders rather than hang the whole response —
 * the bug behind kx-qfqn was an unbounded sequential fan-out that left the
 * Counterparty Risk tab on a perpetual spinner.
 */
const val DEFAULT_COUNTERPARTY_ENRICHMENT_TIMEOUT_MS = 4_000L

fun Route.counterpartyRiskRoutes(
    riskClient: RiskServiceClient,
    positionClient: PositionServiceClient? = null,
    enrichmentTimeoutMs: Long = DEFAULT_COUNTERPARTY_ENRICHMENT_TIMEOUT_MS,
) {

    get("/api/v1/counterparty-risk") {
        val exposures = riskClient.getAllCounterpartyExposures()
        val merged = if (positionClient != null) {
            CounterpartyExposureMerge.mergeWithTradeDerivedCounterparties(
                snapshots = exposures,
                tradeCounterparties = collectFirmTradeCounterparties(positionClient, enrichmentTimeoutMs),
            )
        } else {
            exposures
        }
        call.respond(merged)
    }

    get("/api/v1/counterparty-risk/{counterpartyId}") {
        val counterpartyId = call.requirePathParam("counterpartyId")
        val exposure = riskClient.getCounterpartyExposure(counterpartyId)
        if (exposure == null) {
            call.respond(HttpStatusCode.NotFound)
        } else {
            call.respond(exposure)
        }
    }

    get("/api/v1/counterparty-risk/{counterpartyId}/history") {
        val counterpartyId = call.requirePathParam("counterpartyId")
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 90
        val history = riskClient.getCounterpartyExposureHistory(counterpartyId, limit)
        call.respond(history)
    }

    post("/api/v1/counterparty-risk/{counterpartyId}/pfe") {
        val counterpartyId = call.requirePathParam("counterpartyId")
        val body = call.receive<JsonObject>()
        val result = riskClient.computeCounterpartyPFE(counterpartyId, body)
        if (result == null) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(result)
        }
    }

    post("/api/v1/counterparty-risk/{counterpartyId}/cva") {
        val counterpartyId = call.requirePathParam("counterpartyId")
        val result = riskClient.computeCounterpartyCVA(counterpartyId)
        if (result == null) {
            call.respond(HttpStatusCode.NotFound)
        } else {
            call.respond(result)
        }
    }
}

/**
 * Walks every book the position-service knows about and collects the set of
 * counterparty IDs that appear on at least one trade. Used to seed the
 * `/api/v1/counterparty-risk` response with placeholder rows for trade-only
 * counterparties (trader-review P0 #6).
 *
 * Tolerates upstream failures by returning an empty set — better to fall
 * back to the legacy risk-service-only set than fail the whole response
 * when position-service is degraded. The merge step still runs (no-op if
 * the set is empty) so the existing snapshot rows continue to flow.
 *
 * Bounded and parallel (kx-qfqn): the per-book trade-history calls run
 * concurrently inside a [coroutineScope] so total latency tracks the slowest
 * single book rather than the sum of all of them, and the whole fan-out is
 * wrapped in [withTimeoutOrNull] so a slow position-service can't hang the
 * Counterparty Risk response. On timeout (or any failure) we return an empty
 * set and the canonical snapshot rows still flow.
 */
private suspend fun collectFirmTradeCounterparties(
    positionClient: PositionServiceClient,
    enrichmentTimeoutMs: Long,
): Set<String> = try {
    withTimeoutOrNull(enrichmentTimeoutMs) {
        val books = positionClient.listPortfolios()
        coroutineScope {
            books
                .map { book -> async { positionClient.getTradeHistory(book.id) } }
                .awaitAll()
                .flatten()
                .mapNotNull { row -> row.trade.counterpartyId?.takeIf { it.isNotBlank() } }
                .toSet()
        }
    } ?: emptySet()
} catch (_: Exception) {
    emptySet()
}

/**
 * Merges a risk-service `/api/v1/counterparty-risk` JSON array with the set
 * of trade-derived counterparty IDs collected from the position-service.
 *
 * The output is a JsonArray that:
 *   1. Preserves every existing snapshot row unchanged (including all
 *      fields the risk-service emits — currentNetExposure, peakPfe, cva,
 *      cvaEstimated, currency, pfeProfile, agreementStatus, …).
 *   2. Appends a placeholder row for every counterparty that has trades
 *      but no snapshot. Placeholders use sentinel values:
 *        - currentNetExposure = 0.0
 *        - peakPfe = 0.0
 *        - cva = null (already nullable in the production DTO)
 *        - cvaEstimated = false
 *        - currency = "USD" (firm default — the snapshot would carry the
 *          real reporting currency when it's calculated)
 *        - pfeProfile = []
 *        - calculatedAt = "" (the UI renders "Last calculated …" only when
 *          the snapshot is present; empty string signals "no snapshot yet")
 *
 * Kept as an object (not in-route logic) so the merge invariants can be
 * exercised directly from a unit test without spinning up Ktor.
 */
internal object CounterpartyExposureMerge {

    fun mergeWithTradeDerivedCounterparties(
        snapshots: JsonArray,
        tradeCounterparties: Set<String>,
    ): JsonArray {
        val snapshotIds = snapshots
            .mapNotNull { it as? JsonObject }
            .mapNotNull { it["counterpartyId"]?.let { id ->
                if (id is kotlinx.serialization.json.JsonPrimitive) id.content else null
            } }
            .toSet()

        val missing = tradeCounterparties - snapshotIds
        if (missing.isEmpty()) return snapshots

        return buildJsonArray {
            snapshots.forEach { add(it) }
            missing.sorted().forEach { counterpartyId ->
                add(placeholderRow(counterpartyId))
            }
        }
    }

    private fun placeholderRow(counterpartyId: String): JsonObject = buildJsonObject {
        put("counterpartyId", counterpartyId)
        put("calculatedAt", "")
        put("currentNetExposure", 0.0)
        put("peakPfe", 0.0)
        put("cva", JsonNull)
        put("cvaEstimated", false)
        put("currency", "USD")
        put("pfeProfile", buildJsonArray { })
    }
}

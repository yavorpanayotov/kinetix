package com.kinetix.risk.routes

import com.kinetix.common.model.BookId
import com.kinetix.proto.common.BookId as ProtoBookId
import com.kinetix.proto.risk.StressTestRequest
import com.kinetix.proto.risk.StressTestServiceGrpcKt
import com.kinetix.risk.cache.CannedStressCache
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.mapper.toProto
import com.kinetix.risk.model.CalculationType
import com.kinetix.risk.model.ConfidenceLevel
import com.kinetix.risk.routes.dtos.CannedStressResultResponse
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import java.time.Instant

/**
 * Canned stress-scenario endpoints (issue kx-wxy).
 *
 * - `POST /api/v1/risk/stress/{bookId}/canned/{scenarioName}` fires a single
 *   pre-registered scenario (e.g. `+100BPS_PARALLEL`) against the book's
 *   current positions via the risk-engine gRPC `StressTestService`, caches
 *   the resulting delta-PV in-memory, and returns
 *   [CannedStressResultResponse].
 * - `GET /api/v1/risk/stress/{bookId}/canned` returns the most recent cached
 *   canned result for the book, or 404 if none has been seeded yet.
 *
 * The endpoint is intentionally narrower than the existing
 * `POST /api/v1/risk/stress/{bookId}` — there is no request body, no vol or
 * price shock overrides, and the response is the minimal three-field tile
 * payload (scenario, deltaPv, asOf). The demo orchestrator's
 * `StressScenarioSeedJob` calls this once per book on bootstrap and at SOD.
 */
fun Route.cannedStressRoutes(
    positionProvider: PositionProvider,
    stressTestStub: StressTestServiceGrpcKt.StressTestServiceCoroutineStub,
    cache: CannedStressCache,
) {
    post("/api/v1/risk/stress/{bookId}/canned/{scenarioName}", {
        summary = "Run a canned stress scenario for a portfolio and cache the result"
        tags = listOf("Stress Tests")
        request {
            pathParameter<String>("bookId") { description = "Portfolio identifier" }
            pathParameter<String>("scenarioName") {
                description = "Pre-registered scenario name, e.g. +100BPS_PARALLEL"
            }
        }
    }) {
        val bookId = call.requirePathParam("bookId")
        val scenarioName = call.requirePathParam("scenarioName")
        val positions = positionProvider.getPositions(BookId(bookId))

        val protoRequest = StressTestRequest.newBuilder()
            .setBookId(ProtoBookId.newBuilder().setValue(bookId))
            .setScenarioName(scenarioName)
            .setCalculationType(CalculationType.PARAMETRIC.toProto())
            .setConfidenceLevel(ConfidenceLevel.CL_95.toProto())
            .setTimeHorizonDays(1)
            .addAllPositions(positions.map { it.toProto() })
            .build()

        val response = stressTestStub.runStressTest(protoRequest)
        val asOfInstant = Instant.ofEpochSecond(
            response.calculatedAt.seconds,
            response.calculatedAt.nanos.toLong(),
        )
        // Fall back to "now" when the upstream proto carries no timestamp
        // (default Timestamp has seconds=0, nanos=0). The seeded demo job
        // would otherwise display 1970-01-01 on the tile.
        val asOf = if (response.calculatedAt.seconds == 0L && response.calculatedAt.nanos == 0) {
            Instant.now()
        } else {
            asOfInstant
        }

        val result = CannedStressResultResponse(
            bookId = bookId,
            scenario = response.scenarioName.ifBlank { scenarioName },
            deltaPv = "%.2f".format(response.pnlImpact),
            asOf = asOf.toString(),
        )
        cache.put(bookId, result)
        call.respond(result)
    }

    get("/api/v1/risk/stress/{bookId}/canned", {
        summary = "Most recent canned stress scenario result for a portfolio"
        tags = listOf("Stress Tests")
        request {
            pathParameter<String>("bookId") { description = "Portfolio identifier" }
        }
    }) {
        val bookId = call.requirePathParam("bookId")
        val cached = cache.get(bookId)
        if (cached != null) {
            call.respond(cached)
        } else {
            call.respond(HttpStatusCode.NotFound)
        }
    }
}

package com.kinetix.demo.routes

import com.kinetix.demo.routes.dtos.TriggerEodResponse
import com.kinetix.demo.schedule.EodPromotionJob
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.UUID

private val logger = LoggerFactory.getLogger("com.kinetix.demo.routes.TriggerEodRoutes")

/**
 * Registers the `POST /demo/trigger-eod` admin route against [job].
 *
 * The endpoint exists so live demos can fire the end-of-day cycle on demand
 * — without it the regulatory cycle (the strongest moment of the demo) is
 * unreachable unless the run happens to coincide with the configured close
 * time (default 16:30 UTC).
 *
 * ## Semantics — fire-and-forget
 *
 * The route launches [EodPromotionJob.runOnce] on the application coroutine
 * scope and returns `202 Accepted` immediately with a fresh correlation id.
 * Promotion success/failure does not flow back through this response —
 * downstream consumers observe the `risk.official-eod` Kafka topic, and
 * `EodCycleObserverJob` picks the events up to drive backtests and
 * regulatory submissions exactly as on the scheduled path.
 *
 * Failures inside the dispatched coroutine are logged with the correlation
 * id but never break the already-sent HTTP response — partial sweeps look
 * identical to the scheduled path's behaviour by design.
 *
 * ## Auth / availability
 *
 * Registration is gated upstream in [com.kinetix.demo.Application] behind
 * `DEMO_MODE=true`. When demo mode is disabled this function is not called
 * and the path returns `404 Not Found`.
 *
 * ## Response shape
 *
 * ```json
 * {"correlationId": "550e8400-e29b-41d4-a716-446655440000",
 *  "dispatched": true, "bookCount": 8}
 * ```
 */
fun Route.triggerEodRoutes(job: EodPromotionJob) {
    post("/demo/trigger-eod") {
        val correlationId = UUID.randomUUID().toString()
        val response = TriggerEodResponse(
            correlationId = correlationId,
            dispatched = true,
            bookCount = job.bookCount,
        )
        call.application.launch {
            runEodPromotionSafely(job, correlationId)
        }
        call.respond(HttpStatusCode.Accepted, response)
    }
}

private suspend fun runEodPromotionSafely(job: EodPromotionJob, correlationId: String) {
    logger.info(
        "Dispatching EodPromotionJob (correlationId={}, bookCount={}) via /demo/trigger-eod",
        correlationId,
        job.bookCount,
    )
    try {
        val promoted = job.runOnce()
        logger.info(
            "EodPromotionJob dispatch complete (correlationId={}, promoted={}/{})",
            correlationId,
            promoted,
            job.bookCount,
        )
    } catch (cancellation: CancellationException) {
        logger.info(
            "EodPromotionJob dispatch cancelled (correlationId={})",
            correlationId,
        )
        throw cancellation
    } catch (failure: Throwable) {
        logger.warn(
            "EodPromotionJob dispatch failed (correlationId={}) — HTTP response was already 202",
            correlationId,
            failure,
        )
    }
}

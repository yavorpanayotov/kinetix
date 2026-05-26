package com.kinetix.demo.routes

import com.kinetix.demo.routes.dtos.BootstrapStatusResponse
import com.kinetix.demo.schedule.BootstrapStateHolder
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Registers the `GET /demo/bootstrap-status` route against the supplied
 * [holder].
 *
 * The endpoint is available immediately after the server starts — before the
 * bootstrap job finishes — so callers can observe the state transition from
 * `NOT_STARTED` through `IN_PROGRESS` to `READY` or `FAILED`.
 *
 * ## Response shape
 *
 * ```json
 * {"state": "READY", "successCount": 8, "failureCount": 0,
 *  "sodSuccessCount": 8, "sodFailureCount": 0}
 * ```
 *
 * Count fields are `null` when the state is `NOT_STARTED` or `IN_PROGRESS`.
 */
fun Route.bootstrapStatusRoutes(holder: BootstrapStateHolder) {
    get("/demo/bootstrap-status") {
        val result = holder.getResult()
        val response = BootstrapStatusResponse(
            state = holder.get().name,
            successCount = result?.successCount,
            failureCount = result?.failureCount,
            failedBooks = result?.failedBooks,
            sodSuccessCount = result?.sodSuccessCount,
            sodFailureCount = result?.sodFailureCount,
            sodFailedBooks = result?.sodFailedBooks,
            durationMillis = result?.durationMillis,
        )
        call.respond(response)
    }
}

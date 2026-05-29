package com.kinetix.gateway.routes

import com.kinetix.gateway.client.RiskServiceClient
import com.kinetix.gateway.dtos.GenerateReportRequest
import com.kinetix.gateway.dtos.toResponse
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

// Path words that look like book identifiers but are actually verbs or
// reserved RPC-style segments. If a caller posts to
// `/api/v1/regulatory/frtb/calculate` the router happily captures
// "calculate" as `{bookId}` and the downstream service returns an
// all-zero FRTB result for the non-existent book — a confusing
// foot-gun for clients written against an earlier `/frtb/calculate`
// verb-style API. Reject these explicitly with a 400 so the bug
// surfaces immediately instead of masquerading as a successful
// empty calculation.
private val RESERVED_FRTB_PATH_WORDS = setOf("calculate")

fun Route.regulatoryRoutes(client: RiskServiceClient) {
    route("/api/v1/regulatory/frtb/{bookId}") {
        post({
            summary = "Calculate FRTB"
            tags = listOf("Regulatory")
            request {
                pathParameter<String>("bookId") { description = "Book identifier" }
            }
        }) {
            val bookId = call.requirePathParam("bookId")
            require(bookId !in RESERVED_FRTB_PATH_WORDS) {
                "'$bookId' is a reserved path word, not a book id"
            }
            val result = client.calculateFrtb(bookId)
            if (result != null) {
                call.respond(result.toResponse())
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }

        // Read the most recent persisted FRTB calculation for a book without
        // recalculating, so the Regulatory tab can render the last result by
        // default instead of an empty "Click Calculate FRTB" state.
        get("/latest", {
            summary = "Get the latest FRTB calculation"
            tags = listOf("Regulatory")
            request {
                pathParameter<String>("bookId") { description = "Book identifier" }
            }
        }) {
            val bookId = call.requirePathParam("bookId")
            require(bookId !in RESERVED_FRTB_PATH_WORDS) {
                "'$bookId' is a reserved path word, not a book id"
            }
            val result = client.getLatestFrtb(bookId)
            if (result != null) {
                call.respond(result.toResponse())
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }
    }

    route("/api/v1/regulatory/report/{bookId}") {
        post({
            summary = "Generate regulatory report"
            tags = listOf("Regulatory")
            request {
                pathParameter<String>("bookId") { description = "Book identifier" }
            }
        }) {
            val bookId = call.requirePathParam("bookId")
            val request = call.receive<GenerateReportRequest>()
            val format = request.format ?: "CSV"
            val result = client.generateReport(bookId, format)
            if (result != null) {
                call.respond(result.toResponse())
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }
    }
}

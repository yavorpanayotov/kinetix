package com.kinetix.gateway.routes

import com.kinetix.gateway.auth.BookAccessService
import com.kinetix.gateway.auth.checkMultiBookAccess
import com.kinetix.gateway.client.CrossBookVaRResultSummary
import com.kinetix.gateway.client.RiskServiceClient
import com.kinetix.gateway.dtos.CrossBookVaRRequestDto
import com.kinetix.gateway.dtos.CrossBookVaRResponseDto
import com.kinetix.gateway.dtos.ErrorResponse
import com.kinetix.gateway.dtos.StressedCrossBookVaRRequestDto
import com.kinetix.gateway.dtos.StressedCrossBookVaRResponseDto
import com.kinetix.gateway.dtos.toParams
import com.kinetix.gateway.dtos.toResponse
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.kinetix.gateway.routes.CrossBookVaRRoutes")

/**
 * Responds OK with [result] when it satisfies the Risk-dashboard reconciliation
 * invariants (specs/risk.allium — see [CrossBookVaRReconciliation]); responds
 * 502 Bad Gateway when the orchestrator returned a payload whose
 * `bookContributions` sum or `totalStandaloneVar - diversificationBenefit`
 * disagree with the headline `varValue` outside a $1 / 0.1 % tolerance.
 *
 * Surfacing 502 (not 200) is deliberate: the dashboard renders whatever the
 * gateway returns, so a 200 with inconsistent numbers becomes the trader-review
 * P0 bug "four different VaR totals on the same page".
 */
private suspend fun respondCrossBookReconciled(
    call: ApplicationCall,
    result: CrossBookVaRResultSummary,
) {
    val violation = CrossBookVaRReconciliation.firstViolation(result)
    if (violation != null) {
        logger.error(
            "Rejecting cross-book VaR response for portfolioGroupId={} (bookIds={}): {}",
            result.portfolioGroupId, result.bookIds, violation,
        )
        call.respond(
            HttpStatusCode.BadGateway,
            ErrorResponse(
                error = "cross_book_var_reconciliation_failed",
                message = violation,
            ),
        )
    } else {
        call.respond(result.toResponse())
    }
}

fun Route.crossBookVaRRoutes(client: RiskServiceClient, bookAccessService: BookAccessService? = null) {
    route("/api/v1/risk/var/cross-book") {

        post({
            summary = "Calculate cross-book aggregated VaR"
            tags = listOf("VaR")
            request {
                body<CrossBookVaRRequestDto>()
            }
            response {
                HttpStatusCode.OK to { body<CrossBookVaRResponseDto>() }
                HttpStatusCode.BadRequest to { description = "Invalid request (e.g. empty bookIds)" }
                HttpStatusCode.NotFound to { description = "No positions found for any of the specified books" }
            }
        }) {
            val request = call.receive<CrossBookVaRRequestDto>()
            if (bookAccessService != null && !call.checkMultiBookAccess(request.bookIds, bookAccessService)) return@post
            val params = request.toParams()
            val result = client.calculateCrossBookVaR(params)
            if (result != null) {
                respondCrossBookReconciled(call, result)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }

        get("/{groupId}", {
            summary = "Get cached cross-book VaR result"
            tags = listOf("VaR")
            request {
                pathParameter<String>("groupId") { description = "Portfolio group identifier" }
            }
            response {
                HttpStatusCode.OK to { body<CrossBookVaRResponseDto>() }
                HttpStatusCode.NotFound to { description = "No cached result for this group" }
            }
        }) {
            val groupId = call.requirePathParam("groupId")
            val result = client.getCrossBookVaR(groupId)
            if (result != null) {
                respondCrossBookReconciled(call, result)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }

        post("/stressed", {
            summary = "Calculate stressed cross-book VaR (correlation spike scenario)"
            description = "Computes cross-book VaR under both normal and stressed correlations, " +
                "showing how diversification benefit erodes when correlations spike to crisis levels."
            tags = listOf("VaR")
            request {
                body<StressedCrossBookVaRRequestDto>()
            }
            response {
                HttpStatusCode.OK to { body<StressedCrossBookVaRResponseDto>() }
                HttpStatusCode.BadRequest to { description = "Invalid request (e.g. empty bookIds)" }
                HttpStatusCode.NotFound to { description = "No positions found for any of the specified books" }
            }
        }) {
            val request = call.receive<StressedCrossBookVaRRequestDto>()
            if (bookAccessService != null && !call.checkMultiBookAccess(request.bookIds, bookAccessService)) return@post
            val params = request.toParams()
            val result = client.calculateStressedCrossBookVaR(params)
            if (result != null) {
                call.respond(result.toResponse())
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }
    }
}

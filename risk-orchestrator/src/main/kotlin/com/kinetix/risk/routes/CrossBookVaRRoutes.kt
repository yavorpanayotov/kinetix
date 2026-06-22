package com.kinetix.risk.routes

import com.kinetix.common.model.BookId
import com.kinetix.risk.cache.CrossBookVaRCache
import com.kinetix.risk.model.CalculationType
import com.kinetix.risk.model.ConfidenceLevel
import com.kinetix.risk.model.CrossBookVaRRequest
import com.kinetix.risk.model.CrossBookValuationResult
import com.kinetix.risk.routes.dtos.BookVaRContributionResponse
import com.kinetix.risk.routes.dtos.ComponentBreakdownDto
import com.kinetix.risk.routes.dtos.CrossBookVaRCalculationRequestBody
import com.kinetix.risk.routes.dtos.CrossBookVaRResultResponse
import com.kinetix.risk.routes.dtos.GreekValuesDto
import com.kinetix.risk.routes.dtos.GreeksResponse
import com.kinetix.risk.routes.dtos.StressedCrossBookVaRRequestBody
import com.kinetix.risk.routes.dtos.StressedCrossBookVaRResultResponse
import com.kinetix.risk.service.CrossBookVaRCalculationService
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.crossBookVaRRoutes(
    crossBookVaRService: CrossBookVaRCalculationService,
    crossBookVaRCache: CrossBookVaRCache,
) {
    route("/api/v1/risk/var/cross-book") {
        post({
            summary = "Calculate cross-book VaR"
            tags = listOf("Cross-Book VaR")
            request {
                body<CrossBookVaRCalculationRequestBody>()
            }
        }) {
            val body = call.receive<CrossBookVaRCalculationRequestBody>()

            if (body.bookIds.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bookIds must not be empty"))
                return@post
            }

            val request = CrossBookVaRRequest(
                bookIds = body.bookIds.map { BookId(it) },
                portfolioGroupId = body.portfolioGroupId,
                calculationType = CalculationType.valueOf(body.calculationType ?: "PARAMETRIC"),
                confidenceLevel = ConfidenceLevel.valueOf(body.confidenceLevel ?: "CL_95"),
                timeHorizonDays = body.timeHorizonDays?.toInt() ?: 1,
                numSimulations = body.numSimulations?.toInt() ?: 10_000,
                monteCarloSeed = body.monteCarloSeed?.toLong() ?: 0L,
            )

            val result = crossBookVaRService.calculate(request)
            if (result != null) {
                crossBookVaRCache.put(body.portfolioGroupId, result)
                call.respond(result.toResponse())
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }

        get("/{groupId}", {
            summary = "Get cached cross-book VaR result"
            tags = listOf("Cross-Book VaR")
            request {
                pathParameter<String>("groupId") { description = "Portfolio group identifier" }
            }
        }) {
            val groupId = call.requirePathParam("groupId")
            val cached = crossBookVaRCache.get(groupId)
            if (cached != null) {
                call.respond(cached.toResponse())
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }

        post("/stressed", {
            summary = "Calculate stressed cross-book VaR (correlation spike scenario)"
            description = "Computes cross-book VaR under both normal and stressed correlations, " +
                "showing how diversification benefit erodes when correlations spike to crisis levels."
            tags = listOf("Cross-Book VaR")
            request {
                body<StressedCrossBookVaRRequestBody>()
            }
        }) {
            val body = call.receive<StressedCrossBookVaRRequestBody>()

            if (body.bookIds.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bookIds must not be empty"))
                return@post
            }

            val stressCorrelation = body.stressCorrelation ?: 0.9

            val request = CrossBookVaRRequest(
                bookIds = body.bookIds.map { BookId(it) },
                portfolioGroupId = body.portfolioGroupId,
                calculationType = CalculationType.valueOf(body.calculationType ?: "PARAMETRIC"),
                confidenceLevel = ConfidenceLevel.valueOf(body.confidenceLevel ?: "CL_95"),
                timeHorizonDays = body.timeHorizonDays?.toInt() ?: 1,
                numSimulations = body.numSimulations?.toInt() ?: 10_000,
                monteCarloSeed = body.monteCarloSeed?.toLong() ?: 0L,
            )

            val result = crossBookVaRService.calculateStressed(request, stressCorrelation)
            if (result != null) {
                call.respond(result)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }
    }
}

internal fun CrossBookValuationResult.toResponse() = CrossBookVaRResultResponse(
    portfolioGroupId = portfolioGroupId,
    bookIds = bookIds.map { it.value },
    calculationType = calculationType.name,
    confidenceLevel = confidenceLevel.name,
    varValue = "%.2f".format(varValue),
    expectedShortfall = "%.2f".format(expectedShortfall),
    componentBreakdown = componentBreakdown.map {
        ComponentBreakdownDto(
            assetClass = it.assetClass.name,
            varContribution = "%.2f".format(it.varContribution),
            percentageOfTotal = "%.2f".format(it.percentageOfTotal),
        )
    },
    bookContributions = bookContributions.map {
        BookVaRContributionResponse(
            bookId = it.bookId.value,
            varContribution = "%.2f".format(it.varContribution),
            percentageOfTotal = "%.2f".format(it.percentageOfTotal),
            standaloneVar = "%.2f".format(it.standaloneVar),
            diversificationBenefit = "%.2f".format(it.diversificationBenefit),
            marginalVar = "%.6f".format(it.marginalVar),
            incrementalVar = "%.2f".format(it.incrementalVar),
        )
    },
    totalStandaloneVar = "%.2f".format(totalStandaloneVar),
    diversificationBenefit = "%.2f".format(diversificationBenefit),
    calculatedAt = calculatedAt.toString(),
    greeks = greeks?.let { g ->
        GreeksResponse(
            bookId = portfolioGroupId,
            assetClassGreeks = g.assetClassGreeks.map { gv ->
                GreekValuesDto(
                    assetClass = gv.assetClass.name,
                    delta = "%.6f".format(gv.delta),
                    gamma = "%.6f".format(gv.gamma),
                    vega = "%.6f".format(gv.vega),
                )
            },
            theta = "%.6f".format(g.theta),
            rho = "%.6f".format(g.rho),
            calculatedAt = calculatedAt.toString(),
        )
    },
)

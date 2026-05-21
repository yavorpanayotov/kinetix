package com.kinetix.risk.routes

import com.kinetix.risk.model.FactorContribution
import com.kinetix.risk.model.FactorDecompositionSnapshot
import com.kinetix.risk.persistence.FactorDecompositionRepository
import com.kinetix.risk.routes.dtos.FactorContributionDto
import com.kinetix.risk.routes.dtos.FactorRiskResponse
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.factorRiskRoutes(repository: FactorDecompositionRepository) {

    get("/api/v1/books/{bookId}/factor-risk/latest") {
        val bookId = call.requirePathParam("bookId")
        val snapshot = repository.findLatestByBookId(bookId)

        if (snapshot == null) {
            call.respond(HttpStatusCode.NotFound)
        } else {
            call.respond(snapshot.toResponse())
        }
    }

    get("/api/v1/books/{bookId}/factor-risk") {
        val bookId = call.requirePathParam("bookId")
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
        val snapshots = repository.findAllByBookId(bookId, limit)
        call.respond(snapshots.map { it.toResponse() })
    }
}

private fun FactorDecompositionSnapshot.toResponse() = FactorRiskResponse(
    bookId = bookId,
    calculatedAt = calculatedAt.toString(),
    totalVar = totalVar,
    systematicVar = systematicVar,
    idiosyncraticVar = idiosyncraticVar,
    rSquared = rSquared,
    concentrationWarning = concentrationWarning,
    factors = factors.map { it.toDto() },
)

private fun FactorContribution.toDto() = FactorContributionDto(
    factorType = factorType,
    factorExposure = factorExposure,
    varContribution = varContribution,
    pnlAttribution = pnlAttribution,
    pctOfTotal = pctOfTotal,
    loading = loading,
    loadingMethod = loadingMethod,
)

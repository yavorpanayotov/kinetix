package com.kinetix.risk.routes

import com.kinetix.risk.client.SaCcrResult
import com.kinetix.risk.routes.dtos.SaCcrResponse
import com.kinetix.risk.routes.dtos.SaCcrSummaryResponse
import com.kinetix.risk.service.SaCcrService
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

private fun SaCcrResult.toResponse() = SaCcrResponse(
    nettingSetId = nettingSetId,
    counterpartyId = counterpartyId,
    replacementCost = replacementCost,
    pfeAddon = pfeAddon,
    multiplier = multiplier,
    ead = ead,
    alpha = alpha,
)

fun Route.saCcrRoutes(service: SaCcrService) {

    route("/api/v1/counterparty/{counterpartyId}") {

        get("/sa-ccr", {
            summary = "Compute SA-CCR (BCBS 279) regulatory EAD per netting set"
            description = """
                Returns the Standardised Approach for Counterparty Credit Risk (BCBS 279)
                Exposure at Default for the given counterparty.

                SA-CCR runs per netting set: a counterparty's trades are partitioned by their
                real ISDA/GMRA netting agreement and EAD is computed independently for each set.

                With no `nettingSetId` query parameter, the response is a per-netting-set
                summary (one result per set, plus the total EAD). Supply `nettingSetId` to
                retrieve the result for a single netting set.

                SA-CCR is the REGULATORY capital model — deterministic and formulaic.
                It coexists with but is distinct from the Monte Carlo PFE model
                (available at /api/v1/counterparty-risk/{counterpartyId}/pfe).
            """.trimIndent()
            tags = listOf("SA-CCR", "Counterparty Risk")
            request {
                pathParameter<String>("counterpartyId") { description = "Counterparty ID" }
                queryParameter<String>("nettingSetId") {
                    description = "Restrict the calculation to a single netting set. " +
                        "When omitted, all netting sets are returned."
                    required = false
                }
                queryParameter<Double>("collateral") {
                    description = "Net collateral held after haircuts (default 0)"
                    required = false
                }
            }
            response {
                code(HttpStatusCode.OK) { body<SaCcrSummaryResponse>() }
                code(HttpStatusCode.NotFound) {}
            }
        }) {
            val counterpartyId = call.requirePathParam("counterpartyId")
            val nettingSetId = call.request.queryParameters["nettingSetId"]
            val collateralNet = call.request.queryParameters["collateral"]?.toDoubleOrNull() ?: 0.0

            if (nettingSetId != null) {
                val result = runCatching {
                    service.calculateSaCcr(
                        counterpartyId = counterpartyId,
                        nettingSetId = nettingSetId,
                        collateralNet = collateralNet,
                    )
                }.getOrElse { ex -> return@get call.respondError(ex) }
                return@get call.respond(result.toResponse())
            }

            val results = runCatching {
                service.calculateAllSaCcr(
                    counterpartyId = counterpartyId,
                    collateralNet = collateralNet,
                )
            }.getOrElse { ex -> return@get call.respondError(ex) }

            call.respond(
                SaCcrSummaryResponse(
                    counterpartyId = counterpartyId,
                    totalEad = results.sumOf { it.ead },
                    nettingSets = results.map { it.toResponse() },
                ),
            )
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondError(ex: Throwable) {
    if (ex is IllegalArgumentException) {
        respond(HttpStatusCode.NotFound, mapOf("error" to (ex.message ?: "Not found")))
    } else {
        respond(
            HttpStatusCode.InternalServerError,
            mapOf("error" to (ex.message ?: "SA-CCR calculation failed")),
        )
    }
}

package com.kinetix.risk.routes

import com.kinetix.risk.client.PFEPositionInput
import com.kinetix.risk.model.CounterpartyExposureSnapshot
import com.kinetix.risk.model.ExposureAtTenor
import com.kinetix.risk.routes.dtos.CVAResponse
import com.kinetix.risk.routes.dtos.ComputePFERequest
import com.kinetix.risk.routes.dtos.CounterpartyExposureResponse
import com.kinetix.risk.routes.dtos.ExposureAtTenorResponse
import com.kinetix.risk.routes.dtos.NettingSetExposureResponse
import com.kinetix.risk.service.CounterpartyRiskOrchestrationService
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.counterpartyRiskRoutes(service: CounterpartyRiskOrchestrationService) {

    route("/api/v1/counterparty-risk") {

        get("/", {
            summary = "List latest counterparty exposure snapshots for all counterparties"
            tags = listOf("Counterparty Risk")
            response {
                code(HttpStatusCode.OK) { body<List<CounterpartyExposureResponse>>() }
            }
        }) {
            val snapshots = service.getAllLatestExposures()
            call.respond(snapshots.map { it.toResponse() })
        }

        route("/{counterpartyId}") {

            get({
                summary = "Get latest PFE snapshot for a counterparty"
                tags = listOf("Counterparty Risk")
                request {
                    pathParameter<String>("counterpartyId") { description = "Counterparty ID" }
                }
                response {
                    code(HttpStatusCode.OK) { body<CounterpartyExposureResponse>() }
                    code(HttpStatusCode.NotFound) {}
                }
            }) {
                val counterpartyId = call.requirePathParam("counterpartyId")
                val snapshot = service.getLatestExposure(counterpartyId)
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respond(snapshot.toResponse())
            }

            get("/history", {
                summary = "Get PFE history for a counterparty"
                tags = listOf("Counterparty Risk")
                request {
                    pathParameter<String>("counterpartyId") { description = "Counterparty ID" }
                    queryParameter<Int>("limit") {
                        description = "Maximum number of historical snapshots to return (default 90)"
                        required = false
                    }
                }
                response {
                    code(HttpStatusCode.OK) { body<List<CounterpartyExposureResponse>>() }
                }
            }) {
                val counterpartyId = call.requirePathParam("counterpartyId")
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 90
                val snapshots = service.getExposureHistory(counterpartyId, limit)
                call.respond(snapshots.map { it.toResponse() })
            }

            post("/pfe", {
                summary = "Trigger PFE computation for a counterparty"
                tags = listOf("Counterparty Risk")
                request {
                    pathParameter<String>("counterpartyId") { description = "Counterparty ID" }
                    body<ComputePFERequest>()
                }
                response {
                    code(HttpStatusCode.OK) { body<CounterpartyExposureResponse>() }
                    code(HttpStatusCode.BadRequest) {}
                    code(HttpStatusCode.NotFound) {}
                }
            }) {
                val counterpartyId = call.requirePathParam("counterpartyId")
                val request = call.receive<ComputePFERequest>()

                val positions = request.positions.map { dto ->
                    PFEPositionInput(
                        instrumentId = dto.instrumentId,
                        marketValue = dto.marketValue,
                        assetClass = dto.assetClass,
                        volatility = dto.volatility,
                        sector = dto.sector,
                    )
                }

                val snapshot = runCatching {
                    service.computeAndPersistPFE(
                        counterpartyId = counterpartyId,
                        positions = positions,
                        numSimulations = request.numSimulations,
                        seed = request.seed,
                    )
                }.getOrElse { ex ->
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to (ex.message ?: "Computation failed")),
                    )
                }

                call.respond(snapshot.toResponse())
            }

            post("/cva", {
                summary = "Compute CVA for a counterparty using its latest PFE profile"
                tags = listOf("Counterparty Risk")
                request {
                    pathParameter<String>("counterpartyId") { description = "Counterparty ID" }
                }
                response {
                    code(HttpStatusCode.OK) { body<CVAResponse>() }
                    code(HttpStatusCode.NotFound) {}
                }
            }) {
                val counterpartyId = call.requirePathParam("counterpartyId")

                val latestSnapshot = service.getLatestExposure(counterpartyId)
                    ?: return@post call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "No PFE snapshot found for counterparty $counterpartyId. Run PFE first."),
                    )

                val cvaResult = runCatching {
                    service.computeCVA(counterpartyId, latestSnapshot.pfeProfile)
                }.getOrElse { ex ->
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to (ex.message ?: "CVA computation failed")),
                    )
                }

                call.respond(
                    CVAResponse(
                        counterpartyId = cvaResult.counterpartyId,
                        cva = cvaResult.cva,
                        isEstimated = cvaResult.isEstimated,
                        hazardRate = cvaResult.hazardRate,
                        pd1y = cvaResult.pd1y,
                    )
                )
            }
        }
    }
}

private fun CounterpartyExposureSnapshot.toResponse() = CounterpartyExposureResponse(
    counterpartyId = counterpartyId,
    calculatedAt = calculatedAt.toString(),
    currentNetExposure = currentNetExposure,
    peakPfe = peakPfe,
    pfeProfile = pfeProfile.map { it.toResponse() },
    cva = cva,
    cvaEstimated = cvaEstimated,
    currency = currency,
    nettingSetExposures = (nettingSetExposures ?: emptyList()).map {
        NettingSetExposureResponse(
            nettingSetId = it.nettingSetId,
            agreementType = it.agreementType,
            netExposure = it.netExposure,
            peakPfe = it.peakPfe,
        )
    },
    collateralHeld = collateralHeld,
    collateralPosted = collateralPosted,
    netNetExposure = netNetExposure,
    wrongWayRiskFlags = wrongWayRiskFlags ?: emptyList(),
    agreementStatus = agreementStatus,
)

private fun ExposureAtTenor.toResponse() = ExposureAtTenorResponse(
    tenor = tenor,
    tenorYears = tenorYears,
    expectedExposure = expectedExposure,
    pfe95 = pfe95,
    pfe99 = pfe99,
)

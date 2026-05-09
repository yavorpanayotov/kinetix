package com.kinetix.referencedata.routes

import com.kinetix.referencedata.model.Counterparty
import com.kinetix.referencedata.model.NettingAgreement
import com.kinetix.referencedata.routes.dtos.CounterpartyResponse
import com.kinetix.referencedata.routes.dtos.NettingAgreementResponse
import com.kinetix.referencedata.routes.dtos.UpsertCounterpartyRequest
import com.kinetix.referencedata.routes.dtos.UpsertNettingAgreementRequest
import com.kinetix.referencedata.service.CounterpartyService
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import java.math.BigDecimal
import java.time.Instant

fun Route.counterpartyRoutes(counterpartyService: CounterpartyService) {

    route("/api/v1/counterparties") {

        get({
            summary = "List all counterparties"
            tags = listOf("Counterparties")
        }) {
            call.respond(counterpartyService.findAll().map { it.toResponse() })
        }

        post({
            summary = "Create or update a counterparty"
            tags = listOf("Counterparties")
            request { body<UpsertCounterpartyRequest>() }
        }) {
            val request = call.receive<UpsertCounterpartyRequest>()
            require(request.counterpartyId.isNotBlank()) { "counterpartyId must not be blank" }
            require(request.legalName.isNotBlank()) { "legalName must not be blank" }
            require(request.lgd > 0.0 && request.lgd <= 1.0) { "lgd must be in (0, 1]" }

            val counterparty = request.toDomain()
            counterpartyService.upsert(counterparty)
            call.respond(HttpStatusCode.Created, counterparty.toResponse())
        }

        route("/{id}") {
            get({
                summary = "Get counterparty by ID"
                tags = listOf("Counterparties")
                request { pathParameter<String>("id") { description = "Counterparty ID" } }
            }) {
                val id = call.requirePathParam("id")
                val counterparty = counterpartyService.findById(id)
                if (counterparty != null) {
                    call.respond(counterparty.toResponse())
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }

            get("/netting-sets", {
                summary = "List netting agreements for a counterparty"
                tags = listOf("Counterparties", "Netting")
                request { pathParameter<String>("id") { description = "Counterparty ID" } }
            }) {
                val id = call.requirePathParam("id")
                val agreements = counterpartyService.findNettingAgreementsForCounterparty(id)
                call.respond(agreements.map { it.toResponse() })
            }
        }
    }

    route("/api/v1/netting-agreements") {

        post({
            summary = "Create or update a netting agreement"
            tags = listOf("Netting")
            request { body<UpsertNettingAgreementRequest>() }
        }) {
            val request = call.receive<UpsertNettingAgreementRequest>()
            require(request.nettingSetId.isNotBlank()) { "nettingSetId must not be blank" }
            require(request.counterpartyId.isNotBlank()) { "counterpartyId must not be blank" }

            val agreement = request.toDomain()
            counterpartyService.upsertNettingAgreement(agreement)
            call.respond(HttpStatusCode.Created, agreement.toResponse())
        }

        route("/{id}") {
            get({
                summary = "Get netting agreement by netting set ID"
                tags = listOf("Netting")
                request { pathParameter<String>("id") { description = "Netting set ID" } }
            }) {
                val id = call.requirePathParam("id")
                val agreement = counterpartyService.findNettingAgreementById(id)
                if (agreement != null) {
                    call.respond(agreement.toResponse())
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Mappers
// ---------------------------------------------------------------------------

private fun UpsertCounterpartyRequest.toDomain() = Counterparty(
    counterpartyId = counterpartyId,
    legalName = legalName,
    shortName = shortName,
    lei = lei,
    ratingSp = ratingSp,
    ratingMoodys = ratingMoodys,
    ratingFitch = ratingFitch,
    sector = sector,
    country = country,
    isFinancial = isFinancial,
    pd1y = pd1y?.let { BigDecimal.valueOf(it) },
    lgd = BigDecimal.valueOf(lgd),
    cdsSpreadBps = cdsSpreadBps?.let { BigDecimal.valueOf(it) },
    createdAt = Instant.now(),
    updatedAt = Instant.now(),
)

private fun UpsertNettingAgreementRequest.toDomain() = NettingAgreement(
    nettingSetId = nettingSetId,
    counterpartyId = counterpartyId,
    agreementType = agreementType,
    closeOutNetting = closeOutNetting,
    csaThreshold = csaThreshold?.let { BigDecimal.valueOf(it) },
    currency = currency,
    createdAt = Instant.now(),
    updatedAt = Instant.now(),
    expiryDate = expiryDate?.let { Instant.parse(it) },
)

private fun Counterparty.toResponse() = CounterpartyResponse(
    counterpartyId = counterpartyId,
    legalName = legalName,
    shortName = shortName,
    lei = lei,
    ratingSp = ratingSp,
    ratingMoodys = ratingMoodys,
    ratingFitch = ratingFitch,
    sector = sector,
    country = country,
    isFinancial = isFinancial,
    pd1y = pd1y?.toDouble(),
    lgd = lgd.toDouble(),
    cdsSpreadBps = cdsSpreadBps?.toDouble(),
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

private fun NettingAgreement.toResponse() = NettingAgreementResponse(
    nettingSetId = nettingSetId,
    counterpartyId = counterpartyId,
    agreementType = agreementType,
    closeOutNetting = closeOutNetting,
    csaThreshold = csaThreshold?.toDouble(),
    currency = currency,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    expiryDate = expiryDate?.toString(),
    agreementStatus = if (expiryDate != null && expiryDate.isBefore(Instant.now())) "EXPIRED" else "ACTIVE",
)

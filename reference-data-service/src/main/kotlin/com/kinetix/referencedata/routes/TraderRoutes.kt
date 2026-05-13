package com.kinetix.referencedata.routes

import com.kinetix.common.model.DeskId
import com.kinetix.common.model.Trader
import com.kinetix.common.model.TraderId
import com.kinetix.referencedata.routes.dtos.CreateTraderRequest
import com.kinetix.referencedata.routes.dtos.TraderResponse
import com.kinetix.referencedata.service.TraderService
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import java.math.BigDecimal

fun Route.traderRoutes(traderService: TraderService) {
    route("/api/v1/traders") {
        get({
            summary = "List all traders"
            tags = listOf("Traders")
        }) {
            val traders = traderService.findAll()
            call.respond(traders.map { it.toResponse() })
        }

        post({
            summary = "Create a trader"
            tags = listOf("Traders")
            request { body<CreateTraderRequest>() }
        }) {
            val request = call.receive<CreateTraderRequest>()
            val trader = Trader(
                id = TraderId(request.id),
                name = request.name,
                deskId = DeskId(request.deskId),
                email = request.email,
                notionalLimitUsd = request.notionalLimitUsd?.let { BigDecimal(it) },
            )
            traderService.create(trader)
            call.respond(HttpStatusCode.Created, trader.toResponse())
        }

        route("/{id}") {
            get({
                summary = "Get trader by ID"
                tags = listOf("Traders")
                request { pathParameter<String>("id") { description = "Trader identifier" } }
            }) {
                val id = TraderId(call.requirePathParam("id"))
                val trader = traderService.findById(id)
                if (trader != null) {
                    call.respond(trader.toResponse())
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }
        }
    }

    route("/api/v1/desks/{deskId}/traders") {
        get({
            summary = "List traders on a desk"
            tags = listOf("Traders")
            request { pathParameter<String>("deskId") { description = "Desk identifier" } }
        }) {
            val deskId = DeskId(call.requirePathParam("deskId"))
            val traders = traderService.findByDesk(deskId)
            call.respond(traders.map { it.toResponse() })
        }
    }
}

private fun Trader.toResponse() = TraderResponse(
    id = id.value,
    name = name,
    deskId = deskId.value,
    email = email,
    notionalLimitUsd = notionalLimitUsd?.toPlainString(),
)

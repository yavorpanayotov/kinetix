package com.kinetix.referencedata.routes

import com.kinetix.common.model.AssetClass
import com.kinetix.referencedata.model.InstrumentLiquidity
import com.kinetix.referencedata.routes.dtos.InstrumentLiquidityResponse
import com.kinetix.referencedata.routes.dtos.UpsertInstrumentLiquidityRequest
import com.kinetix.referencedata.service.InstrumentLiquidityService
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import java.time.Instant

fun Route.liquidityRoutes(liquidityService: InstrumentLiquidityService) {
    route("/api/v1/liquidity") {
        get({
            summary = "List all instrument liquidity records"
            tags = listOf("Liquidity")
        }) {
            val all = liquidityService.findAll()
            val now = Instant.now()
            call.respond(all.map { it.toResponse(liquidityService, now) })
        }

        post({
            summary = "Create or update an instrument liquidity record"
            tags = listOf("Liquidity")
            request {
                body<UpsertInstrumentLiquidityRequest>()
            }
        }) {
            val request = call.receive<UpsertInstrumentLiquidityRequest>()
            val now = Instant.now()
            val liquidity = InstrumentLiquidity(
                instrumentId = request.instrumentId,
                adv = request.adv,
                bidAskSpreadBps = request.bidAskSpreadBps,
                assetClass = AssetClass.valueOf(request.assetClass),
                liquidityTier = InstrumentLiquidityService.classifyTier(request.adv, request.bidAskSpreadBps),
                advUpdatedAt = now,
                createdAt = now,
                updatedAt = now,
                advShares = request.advShares,
                marketDepthScore = request.marketDepthScore,
                source = request.source,
            )
            liquidityService.upsert(liquidity)
            call.respond(HttpStatusCode.Created, liquidity.toResponse(liquidityService, now))
        }

        route("/batch") {
            get({
                summary = "Get liquidity data for multiple instruments by comma-separated IDs"
                tags = listOf("Liquidity")
                request {
                    queryParameter<String>("ids") { description = "Comma-separated instrument IDs"; required = true }
                }
            }) {
                val ids = call.request.queryParameters["ids"]
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList()

                val results = liquidityService.findByIds(ids)
                val now = Instant.now()
                call.respond(results.map { it.toResponse(liquidityService, now) })
            }
        }

        route("/{id}") {
            get({
                summary = "Get liquidity data for a specific instrument"
                tags = listOf("Liquidity")
                request {
                    pathParameter<String>("id") { description = "Instrument identifier" }
                }
            }) {
                val id = call.requirePathParam("id")
                val liquidity = liquidityService.findById(id)
                if (liquidity != null) {
                    val now = Instant.now()
                    call.respond(liquidity.toResponse(liquidityService, now))
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }
        }
    }
}

private fun InstrumentLiquidity.toResponse(
    service: InstrumentLiquidityService,
    now: Instant,
): InstrumentLiquidityResponse = InstrumentLiquidityResponse(
    instrumentId = instrumentId,
    adv = adv,
    bidAskSpreadBps = bidAskSpreadBps,
    assetClass = assetClass.name,
    liquidityTier = liquidityTier.name,
    advUpdatedAt = advUpdatedAt.toString(),
    advStale = service.isStale(this, now),
    advStalenessDays = service.staleDays(this, now),
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    advShares = advShares,
    marketDepthScore = marketDepthScore,
    source = source,
    hedgingEligible = hedgingEligible,
)

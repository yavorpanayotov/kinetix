package com.kinetix.volatility.routes

import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.VolPoint
import com.kinetix.common.model.VolSurface
import com.kinetix.common.model.VolatilitySource
import com.kinetix.volatility.persistence.VolSurfaceRepository
import com.kinetix.volatility.routes.dtos.IngestVolSurfaceRequest
import com.kinetix.volatility.routes.dtos.VolPointDto
import com.kinetix.volatility.routes.dtos.VolSurfaceDiffResponse
import com.kinetix.volatility.routes.dtos.VolSurfaceResponse
import com.kinetix.volatility.service.VolatilityIngestionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import java.math.BigDecimal
import java.time.Instant

fun Route.volatilityRoutes(
    volSurfaceRepository: VolSurfaceRepository,
    ingestionService: VolatilityIngestionService,
) {
    route("/api/v1/volatility") {
        route("/{instrumentId}/surface") {
            route("/latest") {
                get({
                    summary = "Get latest volatility surface"
                    tags = listOf("Volatility")
                    request {
                        pathParameter<String>("instrumentId") { description = "Instrument identifier" }
                    }
                }) {
                    val instrumentId = InstrumentId(call.requirePathParam("instrumentId"))
                    val surface = volSurfaceRepository.findLatest(instrumentId)
                    if (surface != null) {
                        call.respond(surface.toResponse())
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }
            }

            route("/diff") {
                get({
                    summary = "Get vol surface diff vs a comparison date"
                    tags = listOf("Volatility")
                    request {
                        pathParameter<String>("instrumentId") { description = "Instrument identifier" }
                        queryParameter<String>("compareDate") { description = "Comparison date (ISO-8601 instant)" }
                    }
                }) {
                    val instrumentId = InstrumentId(call.requirePathParam("instrumentId"))
                    val compareDateStr = call.request.queryParameters["compareDate"]
                        ?: throw IllegalArgumentException("Missing required query parameter: compareDate")
                    val compareDate = try {
                        Instant.parse(compareDateStr)
                    } catch (_: Exception) {
                        throw IllegalArgumentException("Invalid compareDate — expected ISO-8601 instant: $compareDateStr")
                    }

                    val baseSurface = volSurfaceRepository.findLatest(instrumentId)
                    if (baseSurface == null) {
                        call.respond(HttpStatusCode.NotFound); return@get
                    }
                    val compareSurface = volSurfaceRepository.findAtOrBefore(instrumentId, compareDate)
                    if (compareSurface == null) {
                        call.respond(HttpStatusCode.NotFound); return@get
                    }

                    val diffs = computeUnionGridDiff(baseSurface.points, compareSurface.points)

                    call.respond(
                        VolSurfaceDiffResponse(
                            instrumentId = instrumentId.value,
                            baseDate = baseSurface.asOf.toString(),
                            compareDate = compareSurface.asOf.toString(),
                            diffs = diffs,
                        )
                    )
                }
            }

            route("/history") {
                get({
                    summary = "Get volatility surface history"
                    tags = listOf("Volatility")
                    request {
                        pathParameter<String>("instrumentId") { description = "Instrument identifier" }
                        queryParameter<String>("from") { description = "Start of time range (ISO-8601)" }
                        queryParameter<String>("to") { description = "End of time range (ISO-8601)" }
                    }
                }) {
                    val instrumentId = InstrumentId(call.requirePathParam("instrumentId"))
                    val from = Instant.parse(
                        call.request.queryParameters["from"]
                            ?: throw IllegalArgumentException("Missing required query parameter: from")
                    )
                    val to = Instant.parse(
                        call.request.queryParameters["to"]
                            ?: throw IllegalArgumentException("Missing required query parameter: to")
                    )
                    val surfaces = volSurfaceRepository.findByTimeRange(instrumentId, from, to)
                    call.respond(surfaces.map { it.toResponse() })
                }
            }
        }

        route("/surfaces") {
            post({
                summary = "Ingest a volatility surface"
                tags = listOf("Volatility")
                request {
                    body<IngestVolSurfaceRequest>()
                }
            }) {
                val request = call.receive<IngestVolSurfaceRequest>()
                require(request.points.isNotEmpty()) { "points must contain at least one entry" }
                request.points.forEach { p ->
                    require(p.strike > 0.0) { "strike must be positive, was ${p.strike}" }
                    require(p.maturityDays > 0) { "maturityDays must be positive, was ${p.maturityDays}" }
                    require(p.impliedVol > 0.0) { "impliedVol must be positive, was ${p.impliedVol}" }
                }
                val surface = VolSurface(
                    instrumentId = InstrumentId(request.instrumentId),
                    asOf = Instant.now(),
                    points = request.points.map {
                        VolPoint(BigDecimal(it.strike.toString()), it.maturityDays, BigDecimal(it.impliedVol.toString()))
                    },
                    source = VolatilitySource.valueOf(request.source),
                )
                ingestionService.ingest(surface)
                call.respond(HttpStatusCode.Created, surface.toResponse())
            }
        }
    }
}

private fun VolSurface.toResponse() = VolSurfaceResponse(
    instrumentId = instrumentId.value,
    asOfDate = asOf.toString(),
    points = points.map { VolPointDto(it.strike.toDouble(), it.maturityDays, it.impliedVol.toDouble()) },
    source = source.name,
    lastUpdatedAt = asOf.toString(),
)

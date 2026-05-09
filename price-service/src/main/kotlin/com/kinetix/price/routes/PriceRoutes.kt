package com.kinetix.price.routes

import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.PricePoint
import com.kinetix.common.model.PriceSource
import com.kinetix.price.persistence.PriceRepository
import com.kinetix.price.routes.dtos.IngestPriceRequest
import com.kinetix.price.routes.dtos.StaleInstrumentResponse
import com.kinetix.price.service.PriceIngestionService
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

// --- Response DTOs matching gateway's expected shapes ---

@Serializable
data class MoneyDto(val amount: String, val currency: String)

@Serializable
data class PricePointResponse(
    val instrumentId: String,
    val price: MoneyDto,
    val timestamp: String,
    val source: String,
)

@Serializable
data class ErrorResponse(val error: String, val message: String)

private fun Money.toDto() = MoneyDto(amount.toPlainString(), currency.currencyCode)

private fun PricePoint.toResponse() = PricePointResponse(
    instrumentId = instrumentId.value,
    price = price.toDto(),
    timestamp = timestamp.toString(),
    source = source.name,
)

// --- Routes ---

fun Route.priceRoutes(repository: PriceRepository, ingestionService: PriceIngestionService) {
    route("/api/v1/prices/{instrumentId}") {

        route("/latest") {
            get({
                summary = "Get latest price for an instrument"
                tags = listOf("Prices")
                request {
                    pathParameter<String>("instrumentId") { description = "Instrument identifier" }
                }
                response {
                    code(HttpStatusCode.OK) { body<PricePointResponse>() }
                }
            }) {
                val instrumentId = InstrumentId(call.requirePathParam("instrumentId"))
                val point = repository.findLatest(instrumentId)
                if (point != null) {
                    call.respond(point.toResponse())
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }
        }

        route("/history") {
            get({
                summary = "Get price history for an instrument"
                tags = listOf("Prices")
                request {
                    pathParameter<String>("instrumentId") { description = "Instrument identifier" }
                    queryParameter<String>("from") { description = "Start of time range (ISO-8601)" }
                    queryParameter<String>("to") { description = "End of time range (ISO-8601)" }
                }
                response {
                    code(HttpStatusCode.OK) { body<List<PricePointResponse>>() }
                }
            }) {
                val instrumentId = InstrumentId(call.requirePathParam("instrumentId"))
                val from = call.queryParameters["from"]
                    ?: throw IllegalArgumentException("Missing required query parameter: from")
                val to = call.queryParameters["to"]
                    ?: throw IllegalArgumentException("Missing required query parameter: to")
                val interval = call.queryParameters["interval"]
                val parsedFrom = Instant.parse(from)
                val parsedTo = Instant.parse(to)
                val points = when (interval) {
                    null -> repository.findByInstrumentId(instrumentId, parsedFrom, parsedTo)
                    "1d" -> repository.findDailyCloseByInstrumentId(instrumentId, parsedFrom, parsedTo)
                    else -> throw IllegalArgumentException("Unsupported interval: $interval. Supported values: 1d")
                }
                call.respond(points.map { it.toResponse() })
            }
        }
    }

    route("/api/v1/prices") {
        route("/stale") {
            get({
                summary = "List instruments whose freshest price is older than the threshold relative to the platform-wide latest"
                tags = listOf("Prices", "Data Quality")
                request {
                    queryParameter<Long>("thresholdHours") {
                        description = "Staleness threshold in hours (default 24)"
                        required = false
                    }
                }
                response {
                    code(HttpStatusCode.OK) { body<List<StaleInstrumentResponse>>() }
                }
            }) {
                val threshold = call.queryParameters["thresholdHours"]?.toLongOrNull() ?: 24L
                val stale = repository.findStaleInstruments(threshold)
                call.respond(stale.map {
                    StaleInstrumentResponse(
                        instrumentId = it.instrumentId,
                        lastUpdated = it.lastUpdated.toString(),
                        ageHours = it.ageHours,
                        status = it.status,
                    )
                })
            }
        }

        route("/ingest") {
            post({
                summary = "Ingest a new price"
                tags = listOf("Prices")
                request {
                    body<IngestPriceRequest>()
                }
                response {
                    code(HttpStatusCode.Created) { body<PricePointResponse>() }
                }
            }) {
                val request = call.receive<IngestPriceRequest>()
                require(request.instrumentId.isNotBlank()) { "instrumentId must not be blank" }
                val priceAmount = BigDecimal(request.priceAmount)
                require(priceAmount >= BigDecimal.ZERO) {
                    "priceAmount must be non-negative, was ${request.priceAmount}"
                }
                val point = PricePoint(
                    instrumentId = InstrumentId(request.instrumentId),
                    price = Money(priceAmount, Currency.getInstance(request.priceCurrency)),
                    timestamp = Instant.now(),
                    source = PriceSource.valueOf(request.source),
                )
                ingestionService.ingest(point)
                call.respond(HttpStatusCode.Created, point.toResponse())
            }
        }
    }
}

package com.kinetix.gateway.routes

import com.kinetix.gateway.client.PositionServiceClient
import io.github.smiley4.ktoropenapi.get
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory

@Serializable
data class MoneyDto(val amount: String, val currency: String)

@Serializable
data class CurrencyExposureDto(
    val currency: String,
    val localValue: MoneyDto,
    val baseValue: MoneyDto,
    val fxRate: String,
)

@Serializable
data class BookAggregationDto(
    val bookId: String,
    val baseCurrency: String,
    val totalNav: MoneyDto,
    val totalUnrealizedPnl: MoneyDto,
    val currencyBreakdown: List<CurrencyExposureDto> = emptyList(),
)

private val hierarchyLogger = LoggerFactory.getLogger("com.kinetix.gateway.routes.HierarchyRoutes")

fun Route.hierarchyRoutes(
    httpClient: HttpClient,
    referenceDataBaseUrl: String,
    positionClient: PositionServiceClient? = null,
) {
    route("/api/v1/divisions") {
        get({
            summary = "List divisions"
            tags = listOf("Hierarchy")
        }) {
            val response = httpClient.get("$referenceDataBaseUrl/api/v1/divisions")
            val body: List<JsonElement> = response.body()
            call.respond(body)
        }

        route("/{id}") {
            get({
                summary = "Get division by ID"
                tags = listOf("Hierarchy")
                request { pathParameter<String>("id") { description = "Division identifier" } }
            }) {
                val id = call.requirePathParam("id")
                val response = httpClient.get("$referenceDataBaseUrl/api/v1/divisions/$id")
                if (response.status == HttpStatusCode.NotFound) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    val body: JsonElement = response.body()
                    call.respond(body)
                }
            }

            get("/desks", {
                summary = "List desks in division"
                tags = listOf("Hierarchy")
                request { pathParameter<String>("id") { description = "Division identifier" } }
            }) {
                val id = call.requirePathParam("id")
                val response = httpClient.get("$referenceDataBaseUrl/api/v1/divisions/$id/desks")
                val body: List<JsonElement> = response.body()
                call.respond(body)
            }

            get("/summary", {
                summary = "Division-level book aggregation (USD-base by default)"
                tags = listOf("Hierarchy")
                request {
                    pathParameter<String>("id") { description = "Division identifier" }
                    queryParameter<String>("baseCurrency") { description = "Base currency for aggregation"; required = false }
                }
            }) {
                val baseCurrency = call.request.queryParameters["baseCurrency"] ?: "USD"
                // Real cross-book aggregation is gated on the book_hierarchy join,
                // which is empty in the current demo seed; returning a zero-aggregate
                // so the UI degrades gracefully instead of throwing on 404.
                call.respond(emptyAggregate(call.requirePathParam("id"), baseCurrency))
            }
        }
    }

    route("/api/v1/desks") {
        get({
            summary = "List desks"
            tags = listOf("Hierarchy")
        }) {
            val response = httpClient.get("$referenceDataBaseUrl/api/v1/desks")
            val body: List<JsonElement> = response.body()
            call.respond(body)
        }

        route("/{id}") {
            get({
                summary = "Get desk by ID"
                tags = listOf("Hierarchy")
                request { pathParameter<String>("id") { description = "Desk identifier" } }
            }) {
                val id = call.requirePathParam("id")
                val response = httpClient.get("$referenceDataBaseUrl/api/v1/desks/$id")
                if (response.status == HttpStatusCode.NotFound) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    val body: JsonElement = response.body()
                    call.respond(body)
                }
            }

            get("/summary", {
                summary = "Desk-level book aggregation (USD-base by default)"
                tags = listOf("Hierarchy")
                request {
                    pathParameter<String>("id") { description = "Desk identifier" }
                    queryParameter<String>("baseCurrency") { description = "Base currency for aggregation"; required = false }
                }
            }) {
                val baseCurrency = call.request.queryParameters["baseCurrency"] ?: "USD"
                call.respond(emptyAggregate(call.requirePathParam("id"), baseCurrency))
            }
        }
    }

    route("/api/v1/firm") {
        get("/summary", {
            summary = "Firm-level book aggregation (USD-base by default)"
            tags = listOf("Hierarchy")
            request {
                queryParameter<String>("baseCurrency") { description = "Base currency for aggregation"; required = false }
            }
        }) {
            val baseCurrency = call.request.queryParameters["baseCurrency"] ?: "USD"
            // Aggregate every book the position-service knows about so the
            // default Positions tab shows a real NAV instead of a $0 stub.
            // Any upstream failure degrades to the zero-aggregate so the UI
            // never crashes on the firm header (the existing per-book summary
            // calls still surface specific errors when the user drills in).
            val response = if (positionClient != null) {
                runCatching { positionClient.aggregateAllBooks(baseCurrency).toAggregateDto(baseCurrency) }
                    .onFailure { hierarchyLogger.warn("Firm aggregation failed, returning zero-aggregate", it) }
                    .getOrElse { emptyAggregate("firm", baseCurrency) }
            } else {
                emptyAggregate("firm", baseCurrency)
            }
            call.respond(response)
        }
    }
}

private fun com.kinetix.gateway.client.PortfolioAggregationSummary.toAggregateDto(
    baseCurrency: String,
): BookAggregationDto = BookAggregationDto(
    bookId = bookId,
    baseCurrency = baseCurrency,
    totalNav = MoneyDto(totalNav.amount.toPlainString(), totalNav.currency.currencyCode),
    totalUnrealizedPnl = MoneyDto(
        totalUnrealizedPnl.amount.toPlainString(),
        totalUnrealizedPnl.currency.currencyCode,
    ),
    currencyBreakdown = currencyBreakdown.map {
        CurrencyExposureDto(
            currency = it.currency,
            localValue = MoneyDto(it.localValue.amount.toPlainString(), it.localValue.currency.currencyCode),
            baseValue = MoneyDto(it.baseValue.amount.toPlainString(), it.baseValue.currency.currencyCode),
            fxRate = it.fxRate.toPlainString(),
        )
    },
)

private fun emptyAggregate(bookId: String, baseCurrency: String) = BookAggregationDto(
    bookId = bookId,
    baseCurrency = baseCurrency,
    totalNav = MoneyDto("0", baseCurrency),
    totalUnrealizedPnl = MoneyDto("0", baseCurrency),
    currencyBreakdown = emptyList(),
)

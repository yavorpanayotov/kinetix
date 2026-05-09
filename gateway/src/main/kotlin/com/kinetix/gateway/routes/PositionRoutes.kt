package com.kinetix.gateway.routes

import com.kinetix.common.model.BookId
import com.kinetix.gateway.client.InstrumentServiceClient
import com.kinetix.gateway.client.InstrumentSummary
import com.kinetix.gateway.client.PositionServiceClient
import com.kinetix.gateway.dtos.*
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.kinetix.gateway.routes.PositionRoutes")

fun Route.positionRoutes(client: PositionServiceClient, instrumentClient: InstrumentServiceClient? = null) {
    route("/api/v1/books") {

        get({
            summary = "List all books"
            tags = listOf("Books")
        }) {
            val portfolios = client.listPortfolios()
            call.respond(portfolios.map { it.toResponse() })
        }

        route("/{bookId}") {

            route("/trades/page") {
                get({
                    summary = "Server-paginated trade history for the blotter"
                    tags = listOf("Trades")
                    request {
                        pathParameter<String>("bookId") { description = "Book identifier" }
                        queryParameter<Long>("offset") {
                            description = "Page offset (default 0)"
                            required = false
                        }
                        queryParameter<Int>("limit") {
                            description = "Page size (default 100, max 1000)"
                            required = false
                        }
                        queryParameter<String>("counterpartyId") {
                            description = "Filter trades by counterparty"
                            required = false
                        }
                    }
                    response {
                        code(HttpStatusCode.OK) { body<TradeHistoryPageResponse>() }
                    }
                }) {
                    val bookId = BookId(call.requirePathParam("bookId"))
                    val offset = call.request.queryParameters["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0L
                    val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 1000)
                    val counterpartyId = call.request.queryParameters["counterpartyId"]?.takeIf { it.isNotBlank() }
                    val page = client.getTradeHistoryPage(bookId, offset, limit, counterpartyId)
                    val instrumentMap = fetchInstrumentMap(instrumentClient)
                    call.respond(
                        TradeHistoryPageResponse(
                            items = page.items.map { it.toResponse(instrumentMap) },
                            total = page.total,
                            offset = page.offset,
                            limit = page.limit,
                            hasMore = page.hasMore,
                        ),
                    )
                }
            }

            route("/trades") {
                get({
                    summary = "Get trade history for a book"
                    tags = listOf("Trades")
                    request {
                        pathParameter<String>("bookId") { description = "Book identifier" }
                    }
                }) {
                    val bookId = BookId(call.requirePathParam("bookId"))
                    val trades = client.getTradeHistory(bookId)
                    val instrumentMap = fetchInstrumentMap(instrumentClient)
                    call.respond(trades.map { it.toResponse(instrumentMap) })
                }

                post({
                    summary = "Book a trade"
                    tags = listOf("Trades")
                    request {
                        pathParameter<String>("bookId") { description = "Book identifier" }
                    }
                }) {
                    val bookId = BookId(call.requirePathParam("bookId"))
                    val request = call.receive<BookTradeRequest>()
                    val demoUserId = call.request.headers["X-Demo-User-Id"]
                    val demoUserRole = call.request.headers["X-Demo-User-Role"]
                    val command = request.toCommand(bookId).copy(
                        userId = demoUserId,
                        userRole = demoUserRole,
                    )
                    val result = client.bookTrade(command)
                    val instrumentMap = fetchInstrumentMap(instrumentClient)
                    call.respond(HttpStatusCode.Created, result.toResponse(instrumentMap))
                }
            }

            route("/positions") {
                get({
                    summary = "Get positions for a book"
                    tags = listOf("Positions")
                    request {
                        pathParameter<String>("bookId") { description = "Book identifier" }
                    }
                }) {
                    val bookId = BookId(call.requirePathParam("bookId"))
                    val positions = client.getPositions(bookId)
                    val instrumentMap = fetchInstrumentMap(instrumentClient)
                    call.respond(positions.map { it.toResponse(instrumentMap) })
                }
            }

            route("/summary") {
                get({
                    summary = "Get book summary with multi-currency aggregation"
                    tags = listOf("Books")
                    request {
                        pathParameter<String>("bookId") { description = "Book identifier" }
                        queryParameter<String>("baseCurrency") {
                            description = "Base currency for aggregation (default: USD)"
                            required = false
                        }
                    }
                }) {
                    val bookId = BookId(call.requirePathParam("bookId"))
                    val baseCurrency = call.request.queryParameters["baseCurrency"] ?: "USD"
                    val summary = client.getBookSummary(bookId, baseCurrency)
                    call.respond(summary.toResponse())
                }
            }
        }
    }
}

private suspend fun fetchInstrumentMap(
    client: InstrumentServiceClient?,
): Map<String, InstrumentSummary> {
    if (client == null) return emptyMap()
    return try {
        client.fetchAll().associateBy { it.instrumentId }
    } catch (e: Exception) {
        log.warn("Failed to fetch instrument map from reference-data-service", e)
        emptyMap()
    }
}

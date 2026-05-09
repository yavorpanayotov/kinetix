package com.kinetix.position.routes

import com.kinetix.common.model.*
import com.kinetix.common.model.instrument.InstrumentTypeCode
import com.kinetix.position.model.LimitBreach
import com.kinetix.position.persistence.PositionRepository
import com.kinetix.position.service.*
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency
import java.util.UUID

// --- Response DTOs matching gateway's expected JSON shapes ---

@Serializable
data class MoneyDto(
    val amount: String,
    val currency: String,
)

@Serializable
data class PortfolioSummaryResponse(
    val bookId: String,
)

@Serializable
data class PositionResponse(
    val bookId: String,
    val instrumentId: String,
    val assetClass: String,
    val quantity: String,
    val averageCost: MoneyDto,
    val marketPrice: MoneyDto,
    val marketValue: MoneyDto,
    val unrealizedPnl: MoneyDto,
    val realizedPnl: MoneyDto,
    val instrumentType: String? = null,
    val strategyId: String? = null,
    val strategyType: String? = null,
    val strategyName: String? = null,
)

@Serializable
data class TradeResponse(
    val tradeId: String,
    val bookId: String,
    val instrumentId: String,
    val assetClass: String,
    val side: String,
    val quantity: String,
    val price: MoneyDto,
    val tradedAt: String,
    val eventType: String = "NEW",
    val status: String = "LIVE",
    val originalTradeId: String? = null,
    val instrumentType: String? = null,
    val strategyId: String? = null,
    val counterpartyId: String? = null,
)

@Serializable
data class AmendTradeRequest(
    val newTradeId: String? = null,
    val instrumentId: String,
    val assetClass: String,
    val side: String,
    val quantity: String,
    val priceAmount: String,
    val priceCurrency: String,
    val tradedAt: String,
    val instrumentType: String,
    val userId: String? = null,
    val userRole: String? = null,
    val counterpartyId: String? = null,
)

@Serializable
data class BookTradeResponse(
    val trade: TradeResponse,
    val position: PositionResponse,
    val warnings: List<LimitBreachDto> = emptyList(),
)

@Serializable
data class TradeHistoryPageResponse(
    val items: List<TradeResponse>,
    val total: Long,
    val offset: Long,
    val limit: Int,
    val hasMore: Boolean,
)

@Serializable
data class BookTradeRequest(
    val tradeId: String? = null,
    val instrumentId: String,
    val assetClass: String,
    val side: String,
    val quantity: String,
    val priceAmount: String,
    val priceCurrency: String,
    val tradedAt: String,
    val instrumentType: String,
    val userId: String? = null,
    val userRole: String? = null,
    val strategyId: String? = null,
    val counterpartyId: String? = null,
)

@Serializable
data class ErrorResponse(
    val error: String,
    val message: String,
)

@Serializable
data class LimitBreachDto(
    val limitType: String,
    val severity: String,
    val currentValue: String,
    val limitValue: String,
    val message: String,
)

@Serializable
data class LimitBreachResponse(
    val error: String,
    val message: String,
    val breaches: List<LimitBreachDto>,
)

@Serializable
data class CurrencyExposureDto(
    val currency: String,
    val localValue: MoneyDto,
    val baseValue: MoneyDto,
    val fxRate: String,
)

@Serializable
data class PortfolioAggregationResponse(
    val bookId: String,
    val baseCurrency: String,
    val totalNav: MoneyDto,
    val totalUnrealizedPnl: MoneyDto,
    val currencyBreakdown: List<CurrencyExposureDto>,
)

// --- Mappers ---

private fun Money.toDto() = MoneyDto(amount.toPlainString(), currency.currencyCode)

private fun Position.toResponse() = PositionResponse(
    bookId = bookId.value,
    instrumentId = instrumentId.value,
    assetClass = assetClass.name,
    quantity = quantity.toPlainString(),
    averageCost = averageCost.toDto(),
    marketPrice = marketPrice.toDto(),
    marketValue = marketValue.toDto(),
    unrealizedPnl = unrealizedPnl.toDto(),
    realizedPnl = realizedPnl.toDto(),
    instrumentType = instrumentType?.name,
    strategyId = strategyId,
    strategyType = strategyType,
    strategyName = strategyName,
)

private fun Trade.toResponse() = TradeResponse(
    tradeId = tradeId.value,
    bookId = bookId.value,
    instrumentId = instrumentId.value,
    assetClass = assetClass.name,
    side = side.name,
    quantity = quantity.toPlainString(),
    price = price.toDto(),
    tradedAt = tradedAt.toString(),
    eventType = eventType.name,
    status = status.name,
    originalTradeId = originalTradeId?.value,
    instrumentType = instrumentType?.name,
    strategyId = strategyId,
    counterpartyId = counterpartyId,
)

private fun LimitBreach.toDto() = LimitBreachDto(
    limitType = limitType,
    severity = severity.name,
    currentValue = currentValue,
    limitValue = limitValue,
    message = message,
)

private fun com.kinetix.position.model.CurrencyExposure.toDto() = CurrencyExposureDto(
    currency = currency.currencyCode,
    localValue = localValue.toDto(),
    baseValue = baseValue.toDto(),
    fxRate = fxRate.toPlainString(),
)

private fun com.kinetix.position.model.PortfolioSummary.toResponse() = PortfolioAggregationResponse(
    bookId = bookId.value,
    baseCurrency = baseCurrency.currencyCode,
    totalNav = totalNav.toDto(),
    totalUnrealizedPnl = totalUnrealizedPnl.toDto(),
    currencyBreakdown = currencyBreakdown.map { it.toDto() },
)

// --- Routes ---

fun Route.positionRoutes(
    positionRepository: PositionRepository,
    positionQueryService: PositionQueryService,
    tradeBookingService: TradeBookingService,
    tradeEventRepository: com.kinetix.position.persistence.TradeEventRepository,
    tradeLifecycleService: TradeLifecycleService,
    portfolioAggregationService: PortfolioAggregationService,
) {
    route("/api/v1/books") {

        get({
            summary = "List all portfolios"
            tags = listOf("Portfolios")
            response {
                code(HttpStatusCode.OK) { body<List<PortfolioSummaryResponse>>() }
            }
        }) {
            val bookIds = positionRepository.findDistinctBookIds()
            call.respond(bookIds.map { PortfolioSummaryResponse(it.value) })
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
                    val items = tradeEventRepository.findByBookIdPaged(bookId, offset, limit, counterpartyId)
                    val total = tradeEventRepository.countByBookId(bookId, counterpartyId)
                    call.respond(
                        TradeHistoryPageResponse(
                            items = items.map { it.toResponse() },
                            total = total,
                            offset = offset,
                            limit = limit,
                            hasMore = offset + items.size < total,
                        )
                    )
                }
            }

            route("/trades") {
                get({
                    summary = "Get trade history for a portfolio"
                    tags = listOf("Trades")
                    request {
                        pathParameter<String>("bookId") { description = "Book identifier" }
                        queryParameter<String>("from") {
                            description = "Start of date range (ISO-8601 instant, inclusive)"
                            required = false
                        }
                        queryParameter<String>("to") {
                            description = "End of date range (ISO-8601 instant, inclusive)"
                            required = false
                        }
                    }
                    response {
                        code(HttpStatusCode.OK) { body<List<TradeResponse>>() }
                    }
                }) {
                    val bookId = BookId(call.requirePathParam("bookId"))
                    val from = call.request.queryParameters["from"]?.let { Instant.parse(it) }
                    val to = call.request.queryParameters["to"]?.let { Instant.parse(it) }
                    val trades = if (from != null && to != null) {
                        tradeEventRepository.findByBookIdInRange(bookId, from, to)
                    } else {
                        tradeEventRepository.findByBookId(bookId)
                    }
                    call.respond(trades.map { it.toResponse() })
                }

                post({
                    summary = "Book a trade"
                    tags = listOf("Trades")
                    request {
                        pathParameter<String>("bookId") { description = "Book identifier" }
                        body<BookTradeRequest>()
                    }
                    response {
                        code(HttpStatusCode.Created) { body<BookTradeResponse>() }
                        code(HttpStatusCode.BadRequest) { body<ErrorResponse>() }
                        code(HttpStatusCode.UnprocessableEntity) { body<LimitBreachResponse>() }
                    }
                }) {
                    val bookId = BookId(call.requirePathParam("bookId"))
                    val request = call.receive<BookTradeRequest>()
                    val qty = BigDecimal(request.quantity)
                    require(qty > BigDecimal.ZERO) { "Trade quantity must be positive, was $qty" }
                    val priceAmt = BigDecimal(request.priceAmount)
                    require(priceAmt >= BigDecimal.ZERO) { "Trade price must be non-negative, was $priceAmt" }
                    val command = BookTradeCommand(
                        tradeId = TradeId(request.tradeId ?: UUID.randomUUID().toString()),
                        bookId = bookId,
                        instrumentId = InstrumentId(request.instrumentId),
                        assetClass = AssetClass.valueOf(request.assetClass),
                        side = Side.valueOf(request.side),
                        quantity = qty,
                        price = Money(priceAmt, Currency.getInstance(request.priceCurrency)),
                        tradedAt = Instant.parse(request.tradedAt),
                        instrumentType = request.instrumentType,
                        userId = request.userId,
                        userRole = request.userRole,
                        strategyId = request.strategyId,
                        counterpartyId = request.counterpartyId,
                    )
                    try {
                        val result = tradeBookingService.handle(command)
                        call.respond(
                            HttpStatusCode.Created,
                            BookTradeResponse(
                                trade = result.trade.toResponse(),
                                position = result.position.toResponse(),
                                warnings = result.warnings.map { it.toDto() },
                            ),
                        )
                    } catch (e: LimitBreachException) {
                        call.respond(
                            HttpStatusCode.UnprocessableEntity,
                            LimitBreachResponse(
                                error = "limit_breach",
                                message = e.message ?: "Trade blocked by limit breach",
                                breaches = e.result.breaches.map { it.toDto() },
                            ),
                        )
                    }
                }
            }

            route("/positions") {
                get({
                    summary = "Get positions for a portfolio"
                    tags = listOf("Positions")
                    request {
                        pathParameter<String>("bookId") { description = "Book identifier" }
                    }
                    response {
                        code(HttpStatusCode.OK) { body<List<PositionResponse>>() }
                    }
                }) {
                    val bookId = BookId(call.requirePathParam("bookId"))
                    val positions = positionQueryService.handle(GetPositionsQuery(bookId))
                    call.respond(positions.map { it.toResponse() })
                }
            }

            route("/summary") {
                get({
                    summary = "Get portfolio summary with multi-currency aggregation"
                    tags = listOf("Portfolios")
                    request {
                        pathParameter<String>("bookId") { description = "Book identifier" }
                        queryParameter<String>("baseCurrency") {
                            description = "Base currency for aggregation (default: USD)"
                            required = false
                        }
                    }
                    response {
                        code(HttpStatusCode.OK) { body<PortfolioAggregationResponse>() }
                    }
                }) {
                    val bookId = BookId(call.requirePathParam("bookId"))
                    val baseCurrency = call.request.queryParameters["baseCurrency"]
                        ?.let { Currency.getInstance(it) }
                        ?: Currency.getInstance("USD")
                    val summary = portfolioAggregationService.aggregate(bookId, baseCurrency)
                    call.respond(summary.toResponse())
                }
            }

            route("/trades/{tradeId}") {
                put({
                    summary = "Amend a trade"
                    tags = listOf("Trades")
                    request {
                        pathParameter<String>("bookId") { description = "Book identifier" }
                        pathParameter<String>("tradeId") { description = "Trade identifier to amend" }
                        body<AmendTradeRequest>()
                    }
                    response {
                        code(HttpStatusCode.OK) { body<BookTradeResponse>() }
                        code(HttpStatusCode.BadRequest) { body<ErrorResponse>() }
                        code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                        code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    }
                }) {
                    val bookId = BookId(call.requirePathParam("bookId"))
                    val tradeId = TradeId(call.requirePathParam("tradeId"))
                    val request = call.receive<AmendTradeRequest>()
                    val qty = BigDecimal(request.quantity)
                    require(qty > BigDecimal.ZERO) { "Trade quantity must be positive, was $qty" }
                    val priceAmt = BigDecimal(request.priceAmount)
                    require(priceAmt >= BigDecimal.ZERO) { "Trade price must be non-negative, was $priceAmt" }
                    val command = AmendTradeCommand(
                        originalTradeId = tradeId,
                        newTradeId = TradeId(request.newTradeId ?: UUID.randomUUID().toString()),
                        bookId = bookId,
                        instrumentId = InstrumentId(request.instrumentId),
                        assetClass = AssetClass.valueOf(request.assetClass),
                        side = Side.valueOf(request.side),
                        quantity = qty,
                        price = Money(priceAmt, Currency.getInstance(request.priceCurrency)),
                        tradedAt = Instant.parse(request.tradedAt),
                        instrumentType = request.instrumentType,
                        userId = request.userId,
                        userRole = request.userRole,
                        counterpartyId = request.counterpartyId,
                    )
                    try {
                        val result = tradeLifecycleService.handleAmend(command)
                        call.respond(
                            HttpStatusCode.OK,
                            BookTradeResponse(
                                trade = result.trade.toResponse(),
                                position = result.position.toResponse(),
                            ),
                        )
                    } catch (e: InvalidTradeStateException) {
                        call.respond(
                            HttpStatusCode.Conflict,
                            ErrorResponse("invalid_trade_state", e.message ?: "Trade is not in a valid state for amend"),
                        )
                    }
                }

                delete({
                    summary = "Cancel a trade"
                    tags = listOf("Trades")
                    request {
                        pathParameter<String>("bookId") { description = "Book identifier" }
                        pathParameter<String>("tradeId") { description = "Trade identifier to cancel" }
                    }
                    response {
                        code(HttpStatusCode.OK) { body<BookTradeResponse>() }
                        code(HttpStatusCode.BadRequest) { body<ErrorResponse>() }
                        code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                        code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    }
                }) {
                    val bookId = BookId(call.requirePathParam("bookId"))
                    val tradeId = TradeId(call.requirePathParam("tradeId"))
                    val command = CancelTradeCommand(
                        tradeId = tradeId,
                        bookId = bookId,
                    )
                    try {
                        val result = tradeLifecycleService.handleCancel(command)
                        call.respond(
                            HttpStatusCode.OK,
                            BookTradeResponse(
                                trade = result.trade.toResponse(),
                                position = result.position.toResponse(),
                            ),
                        )
                    } catch (e: InvalidTradeStateException) {
                        call.respond(
                            HttpStatusCode.Conflict,
                            ErrorResponse("invalid_trade_state", e.message ?: "Trade is not in a valid state for cancel"),
                        )
                    }
                }
            }
        }
    }
}

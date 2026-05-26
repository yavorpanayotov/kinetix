package com.kinetix.position.routes

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Side
import com.kinetix.common.model.TradeId
import com.kinetix.common.model.TraderId
import com.kinetix.position.model.LimitBreach
import com.kinetix.position.model.StrategyType
import com.kinetix.position.model.TradeStrategy
import com.kinetix.position.routes.dtos.CreateStrategyRequest
import com.kinetix.position.routes.dtos.StrategyResponse
import com.kinetix.position.service.BookTradeCommand
import com.kinetix.position.service.BookTradeResult
import com.kinetix.position.service.TradeBookingService
import com.kinetix.position.service.TradeStrategyService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency
import java.util.UUID

fun Route.strategyRoutes(
    strategyService: TradeStrategyService,
    tradeBookingService: TradeBookingService? = null,
) {
    route("/api/v1/books/{bookId}/strategies") {

        post {
            val bookId = BookId(call.requirePathParam("bookId"))
            val request = call.receive<CreateStrategyRequest>()
            val strategyType = StrategyType.valueOf(request.strategyType)
            val strategy = strategyService.createStrategy(
                bookId = bookId,
                strategyType = strategyType,
                name = request.name,
            )
            call.respond(HttpStatusCode.Created, strategy.toStrategyResponse())
        }

        get {
            val bookId = BookId(call.requirePathParam("bookId"))
            val strategies = strategyService.listStrategies(bookId)
            call.respond(strategies.map { it.toStrategyResponse() })
        }

        route("/{strategyId}") {

            get {
                val strategyId = call.parameters["strategyId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest)
                val strategy = strategyService.findById(strategyId)
                if (strategy == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respond(strategy.toStrategyResponse())
                }
            }

            // POST /api/v1/books/{bookId}/strategies/{strategyId}/trades
            // Books a trade linked to this strategy.
            route("/trades") {
                post {
                    val bookingService = tradeBookingService
                        ?: return@post call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "service_unavailable"),
                        )

                    val bookId = BookId(call.requirePathParam("bookId"))
                    val strategyId = call.parameters["strategyId"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest)

                    val strategy = strategyService.findById(strategyId)
                        ?: return@post call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "strategy_not_found"),
                        )

                    val request = call.receive<StrategyTradeRequest>()
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
                        strategyId = strategy.strategyId,
                        counterpartyId = request.counterpartyId,
                        traderId = TraderId(
                            com.kinetix.common.demo.DemoTraderRoster.requirePrimaryTraderFor(bookId.value),
                        ),
                    )

                    val result = bookingService.handle(command)
                    call.respond(HttpStatusCode.Created, result.toStrategyBookResponse())
                }
            }
        }
    }
}

// --- DTOs ---

@Serializable
private data class StrategyTradeRequest(
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
    /**
     * Optional counterparty identifier persisted on the trade record. Demo
     * orchestrators rotate this across the seeded counterparties (kx-i72) so
     * the Counterparty Exposure tile has non-trivial concentration to render.
     * Existing call sites that omit it keep working — the field is optional
     * and defaults to `null`.
     */
    val counterpartyId: String? = null,
)

@Serializable
private data class StrategyBookTradeResponse(
    val tradeId: String,
    val bookId: String,
    val instrumentId: String,
    val side: String,
    val quantity: String,
    val strategyId: String?,
)

// --- Mappers ---

private fun TradeStrategy.toStrategyResponse() = StrategyResponse(
    strategyId = strategyId,
    bookId = bookId.value,
    strategyType = strategyType.name,
    name = name,
    createdAt = createdAt.toString(),
)

private fun BookTradeResult.toStrategyBookResponse() = StrategyBookTradeResponse(
    tradeId = trade.tradeId.value,
    bookId = trade.bookId.value,
    instrumentId = trade.instrumentId.value,
    side = trade.side.name,
    quantity = trade.quantity.toPlainString(),
    strategyId = trade.strategyId,
)

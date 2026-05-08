package com.kinetix.position.routes

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Side
import com.kinetix.common.model.TradeId
import com.kinetix.position.model.LimitBreach
import com.kinetix.position.model.LimitBreachSeverity
import com.kinetix.position.routes.dtos.LimitBreachDto
import com.kinetix.position.routes.dtos.PreTradeCheckRequest
import com.kinetix.position.routes.dtos.PreTradeCheckResponse
import com.kinetix.position.service.BookTradeCommand
import com.kinetix.position.service.PreTradeCheckService
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency
import java.util.UUID

private val logger = LoggerFactory.getLogger("PreTradeCheckRoutes")

/** Timeout for the pre-trade risk check as specified in execution.allium config. */
private const val RISK_CHECK_TIMEOUT_MS = 100L

fun Route.preTradeCheckRoutes(preTradeCheckService: PreTradeCheckService) {
    route("/api/v1/risk") {
        post("/pre-trade-check", {
            summary = "Run a pre-trade risk check without persisting any trade"
            description = """
                Evaluates position, notional, and concentration limits for a hypothetical trade.
                Does not book the trade — for read-only limit assessment only.
                Target latency: <100ms (fail-safe on timeout — returns REJECTED).
            """.trimIndent()
            tags = listOf("Risk")
            request {
                body<PreTradeCheckRequest>()
            }
            response {
                code(HttpStatusCode.OK) { body<PreTradeCheckResponse>() }
                code(HttpStatusCode.BadRequest) { }
            }
        }) {
            val request = call.receive<PreTradeCheckRequest>()
            val qty = BigDecimal(request.quantity)
            require(qty > BigDecimal.ZERO) { "quantity must be positive, was $qty" }
            val priceAmt = BigDecimal(request.priceAmount)
            require(priceAmt >= BigDecimal.ZERO) { "priceAmount must be non-negative, was $priceAmt" }

            val command = BookTradeCommand(
                tradeId = TradeId(UUID.randomUUID().toString()),
                bookId = BookId(request.bookId),
                instrumentId = InstrumentId(request.instrumentId),
                assetClass = AssetClass.valueOf(request.assetClass),
                side = Side.valueOf(request.side),
                quantity = qty,
                price = Money(priceAmt, Currency.getInstance(request.priceCurrency)),
                tradedAt = Instant.now(),
                instrumentType = request.instrumentType,
            )

            // Fail-safe on timeout: reject the order rather than allowing it through
            val limitResult = withTimeoutOrNull(RISK_CHECK_TIMEOUT_MS) {
                preTradeCheckService.check(command)
            }

            if (limitResult == null) {
                logger.warn(
                    "Pre-trade check timed out after {}ms for book={}, instrument={}",
                    RISK_CHECK_TIMEOUT_MS, request.bookId, request.instrumentId,
                )
                call.respond(
                    HttpStatusCode.OK,
                    PreTradeCheckResponse(
                        approved = false,
                        result = "REJECTED",
                        warnings = emptyList(),
                        breaches = listOf(
                            LimitBreachDto(
                                limitType = "TIMEOUT",
                                severity = "HARD",
                                currentValue = "0",
                                limitValue = "0",
                                message = "Pre-trade risk check timed out after ${RISK_CHECK_TIMEOUT_MS}ms — order rejected (fail-safe)",
                            )
                        ),
                    )
                )
                return@post
            }

            val isApproved = !limitResult.blocked
            val hardBreaches = limitResult.breaches.filter { it.severity == LimitBreachSeverity.HARD }
            val softWarnings = limitResult.breaches.filter { it.severity == LimitBreachSeverity.SOFT }
            val resultLabel = when {
                limitResult.blocked -> "REJECTED"
                softWarnings.isNotEmpty() -> "FLAGGED"
                else -> "APPROVED"
            }

            call.respond(
                HttpStatusCode.OK,
                PreTradeCheckResponse(
                    approved = isApproved,
                    result = resultLabel,
                    warnings = softWarnings.map { it.toDto() },
                    breaches = hardBreaches.map { it.toDto() },
                ),
            )
        }
    }
}

private fun LimitBreach.toDto() = LimitBreachDto(
    limitType = limitType,
    severity = severity.name,
    currentValue = currentValue,
    limitValue = limitValue,
    message = message,
)

package com.kinetix.position.routes

import com.kinetix.common.model.Side
import com.kinetix.position.fix.GhostFill
import com.kinetix.position.fix.GhostFillRepository
import com.kinetix.position.fix.Order
import com.kinetix.position.fix.OrderSubmissionService
import com.kinetix.position.fix.TimeInForce
import com.kinetix.position.routes.dtos.GhostFillResponse
import com.kinetix.position.routes.dtos.OrderResponse
import com.kinetix.position.routes.dtos.SubmitOrderRequest
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.math.BigDecimal
import java.time.Instant
import java.time.format.DateTimeParseException

fun Route.orderRoutes(
    orderSubmissionService: OrderSubmissionService,
    ghostFillRepository: GhostFillRepository,
) {
    route("/api/v1/orders") {

        post({
            summary = "Submit a new order for execution"
            tags = listOf("Orders")
            request {
                body<SubmitOrderRequest>()
            }
            response {
                code(HttpStatusCode.Created) { body<OrderResponse>() }
                code(HttpStatusCode.BadRequest) { description = "Invalid request body" }
            }
        }) {
            val request = call.receive<SubmitOrderRequest>()

            val side = runCatching { Side.valueOf(request.side.uppercase()) }
                .getOrElse { throw IllegalArgumentException("Invalid side '${request.side}': must be BUY or SELL") }

            val quantity = request.quantity.toBigDecimalOrNull()
                ?: throw IllegalArgumentException("Invalid quantity '${request.quantity}'")

            val arrivalPrice = request.arrivalPrice.toBigDecimalOrNull()
                ?: throw IllegalArgumentException("Invalid arrivalPrice '${request.arrivalPrice}'")

            val limitPrice = request.limitPrice?.let {
                it.toBigDecimalOrNull()
                    ?: throw IllegalArgumentException("Invalid limitPrice '$it'")
            }

            val arrivalPriceTimestamp = request.arrivalPriceTimestamp?.let {
                try {
                    Instant.parse(it)
                } catch (e: DateTimeParseException) {
                    throw IllegalArgumentException("Invalid arrivalPriceTimestamp '$it'")
                }
            }

            val timeInForce = runCatching { TimeInForce.valueOf(request.timeInForce.uppercase()) }
                .getOrElse {
                    throw IllegalArgumentException(
                        "Invalid timeInForce '${request.timeInForce}': must be DAY, GTC, IOC, FOK, or GTD"
                    )
                }

            val expiresAt = request.expiresAt?.let {
                try {
                    Instant.parse(it)
                } catch (e: DateTimeParseException) {
                    throw IllegalArgumentException("Invalid expiresAt '$it'")
                }
            }

            val order = orderSubmissionService.submit(
                bookId = request.bookId,
                instrumentId = request.instrumentId,
                side = side,
                quantity = quantity,
                orderType = request.orderType,
                limitPrice = limitPrice,
                arrivalPrice = arrivalPrice,
                fixSessionId = request.fixSessionId,
                assetClass = request.assetClass,
                currency = request.currency,
                arrivalPriceTimestamp = arrivalPriceTimestamp,
                timeInForce = timeInForce,
                expiresAt = expiresAt,
                instrumentType = request.instrumentType,
            )

            call.respond(HttpStatusCode.Created, order.toResponse())
        }

        get("/{id}/ghost-fills", {
            summary = "List ghost fills attached to an order (FIX fills against EXPIRED/CANCELLED/REJECTED orders)"
            tags = listOf("Orders")
            response {
                code(HttpStatusCode.OK) { body<List<GhostFillResponse>>() }
                code(HttpStatusCode.BadRequest) { description = "Order id is missing" }
            }
        }) {
            val orderId = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing path parameter id"))
            val fills = ghostFillRepository.findByOrderId(orderId).map { it.toResponse() }
            call.respond(HttpStatusCode.OK, fills)
        }
    }
}

private fun GhostFill.toResponse() = GhostFillResponse(
    orderId = orderId,
    priorStatus = priorStatus.name,
    venue = venue,
    fixExecId = fixExecId,
    fillQty = fillQty.toPlainString(),
    fillPrice = fillPrice.toPlainString(),
    cumulativeQty = cumulativeQty.toPlainString(),
    detectedAt = detectedAt.toString(),
)

// --- Mapper ---

private fun Order.toResponse() = OrderResponse(
    orderId = orderId,
    bookId = bookId,
    instrumentId = instrumentId,
    side = side.name,
    quantity = quantity.toPlainString(),
    orderType = orderType,
    limitPrice = limitPrice?.toPlainString(),
    arrivalPrice = arrivalPrice.toPlainString(),
    submittedAt = submittedAt.toString(),
    status = status.name,
    fixSessionId = fixSessionId,
    timeInForce = timeInForce.name,
    expiresAt = expiresAt?.toString(),
)

package com.kinetix.fix.session

import com.kinetix.fix.venue.VenueSession
import com.kinetix.proto.execution.OrderType
import com.kinetix.proto.execution.PlaceOrderRequest
import com.kinetix.proto.execution.Side
import com.kinetix.proto.execution.TimeInForce
import quickfix.Message
import quickfix.field.ClOrdID
import quickfix.field.ExpireTime
import quickfix.field.MsgType
import quickfix.field.OrdType
import quickfix.field.OrderQty
import quickfix.field.Price
import quickfix.field.Symbol
import quickfix.field.TransactTime
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Builds FIX `NewOrderSingle` (35=D) messages from a [PlaceOrderRequest].
 *
 * Tag layout (subset that matters for the platform's downstream consumers and the
 * cancel-flow's `fix_message_log` lookup at phase 4):
 *
 *   11 (ClOrdID)        — minted by position-service; carried verbatim.
 *   35 (MsgType)        — "D".
 *   38 (OrderQty)       — decimal-as-string preserved verbatim.
 *   40 (OrdType)        — `1` MARKET, `2` LIMIT (phase-4 launch only).
 *   44 (Price)          — populated for LIMIT orders only (verbatim, supports negative).
 *   54 (Side)           — `1` BUY, `2` SELL.
 *   55 (Symbol)         — `instrument_id` (reference-data resolution lands in phase-4 follow-on).
 *   59 (TimeInForce)    — `0` DAY, `1` GTC, `3` IOC, `4` FOK, `6` GTD.
 *   60 (TransactTime)   — UTC, millisecond precision.
 *  126 (ExpireTime)     — populated for GTD only.
 *
 * The session header (BeginString, sender/target CompIDs, MsgSeqNum) is stamped by
 * QuickFIX/J on send — this builder is intentionally thin so its tests can pin the
 * byte shape without booting an Initiator.
 */
class NewOrderSingleBuilder {

    fun build(session: VenueSession, request: PlaceOrderRequest, transactTime: Instant): Message {
        validate(request)

        val message = Message()
        message.header.setField(MsgType("D"))
        message.setField(ClOrdID(request.clOrdId))
        message.setField(Symbol(request.instrumentId))
        message.setField(quickfix.field.Side(toFixSide(request.side)))
        message.setField(OrderQty(BigDecimal(request.quantity).toDouble()))
        message.setField(OrdType(toFixOrdType(request.orderType)))
        if (request.orderType == OrderType.LIMIT) {
            // Use the long-form (tag, value) constructor on Message so the price string is
            // preserved verbatim — Price's double constructor would re-format the value and
            // drop trailing zeros / introduce float-drift on negative synthetic prices.
            message.setField(quickfix.StringField(Price.FIELD, request.limitPrice))
        }
        message.setField(quickfix.field.TimeInForce(toFixTif(request.timeInForce)))
        message.setField(TransactTime(LocalDateTime.ofInstant(transactTime, ZoneOffset.UTC)))
        if (request.timeInForce == TimeInForce.TIF_GTD) {
            val expiresAt = Instant.parse(request.expiresAtIso)
            message.setField(ExpireTime(LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC)))
        }
        return message
    }

    private fun validate(request: PlaceOrderRequest) {
        require(request.side != Side.SIDE_UNSPECIFIED) { "side is required" }
        require(request.orderType != OrderType.ORDER_TYPE_UNSPECIFIED) { "order_type is required" }
        require(request.timeInForce != TimeInForce.TIME_IN_FORCE_UNSPECIFIED) { "time_in_force is required" }

        val qty = runCatching { BigDecimal(request.quantity) }.getOrElse {
            throw IllegalArgumentException("quantity is not a valid decimal: '${request.quantity}'")
        }
        require(qty.signum() > 0) { "quantity must be > 0" }

        if (request.orderType == OrderType.LIMIT) {
            require(request.limitPrice.isNotBlank()) { "limit_price is required for LIMIT orders" }
            // Validate it parses, but keep the original string to set on the wire.
            runCatching { BigDecimal(request.limitPrice) }.getOrElse {
                throw IllegalArgumentException("limit_price is not a valid decimal: '${request.limitPrice}'")
            }
        }

        if (request.timeInForce == TimeInForce.TIF_GTD) {
            require(request.expiresAtIso.isNotBlank()) { "expires_at_iso is required for GTD orders" }
            runCatching { Instant.parse(request.expiresAtIso) }.getOrElse {
                throw IllegalArgumentException("expires_at_iso is not a valid ISO-8601 instant: '${request.expiresAtIso}'")
            }
        }
    }

    private fun toFixSide(side: Side): Char = when (side) {
        Side.BUY -> '1'
        Side.SELL -> '2'
        Side.SIDE_UNSPECIFIED, Side.UNRECOGNIZED -> error("unreachable")
    }

    private fun toFixOrdType(type: OrderType): Char = when (type) {
        OrderType.MARKET -> '1'
        OrderType.LIMIT -> '2'
        OrderType.ORDER_TYPE_UNSPECIFIED, OrderType.UNRECOGNIZED -> error("unreachable")
    }

    private fun toFixTif(tif: TimeInForce): Char = when (tif) {
        TimeInForce.TIF_DAY -> '0'
        TimeInForce.TIF_GTC -> '1'
        TimeInForce.TIF_IOC -> '3'
        TimeInForce.TIF_FOK -> '4'
        TimeInForce.TIF_GTD -> '6'
        TimeInForce.TIME_IN_FORCE_UNSPECIFIED, TimeInForce.UNRECOGNIZED -> error("unreachable")
    }
}

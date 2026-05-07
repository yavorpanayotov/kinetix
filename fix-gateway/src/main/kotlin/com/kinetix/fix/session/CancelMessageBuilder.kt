package com.kinetix.fix.session

import com.kinetix.fix.venue.VenueSession
import quickfix.Message
import quickfix.field.MsgType
import quickfix.field.OrderQty
import quickfix.field.OrigClOrdID
import quickfix.field.Side
import quickfix.field.Symbol
import quickfix.field.TransactTime
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

/**
 * Builds FIX `OrderCancelRequest` (35=F) messages without committing to the
 * QuickFIX/J 4.4 generated subclass — fix-gateway speaks several FIX versions
 * across launch venues and the registry-driven routing decides per call.
 *
 * Phase 2 builds messages with the tags the cancel server-side test pins:
 *   11 (ClOrdID — newly minted for the cancel)
 *   17 (ExecID) — owned by ExecutionReport (inbound), not 35=F
 *   37 (OrderID — venue-assigned)
 *   38 (OrderQty — recovered from the original 35=D when phase 4 lands; for
 *       phase 2 the request carries it via [BuilderInputs])
 *   41 (OrigClOrdID — the order being cancelled)
 *   54 (Side — recovered from fix_message_log when phase 4 lands)
 *   55 (Symbol — instrument identifier)
 *   60 (TransactTime — UTC, millisecond precision)
 *
 * Side, OrderQty, and Symbol come from [BuilderInputs] — phase 2 receives them
 * synthesised by the gRPC server from caller hints + a future fix_message_log
 * lookup. The builder is intentionally thin so its tests can pin the byte-shape.
 */
class CancelMessageBuilder(
    private val cancelClOrdIdSeq: AtomicLong = AtomicLong(0),
) {

    /**
     * Inputs to the cancel-message build. Side, quantity, and symbol resolve
     * from `fix_message_log` of the original 35=D in production; phase-2 tests
     * pass them explicitly.
     */
    data class BuilderInputs(
        val origClOrdId: String,
        val venueOrderId: String,
        val symbol: String,
        val side: Char,
        val orderQty: BigDecimal,
        val transactTime: java.time.Instant,
    )

    /** Build the FIX 35=F message. The session header (BeginString, sender/target CompIDs) is set by QuickFIX/J on send. */
    fun build(session: VenueSession, inputs: BuilderInputs): Message {
        val message = Message()
        message.header.setField(MsgType("F"))
        message.setField(OrigClOrdID(inputs.origClOrdId))
        message.setField(quickfix.field.OrderID(inputs.venueOrderId))
        message.setField(quickfix.field.ClOrdID(mintCancelClOrdId(inputs.origClOrdId)))
        message.setField(Symbol(inputs.symbol))
        message.setField(Side(inputs.side))
        message.setField(OrderQty(inputs.orderQty.toDouble()))
        message.setField(TransactTime(LocalDateTime.ofInstant(inputs.transactTime, ZoneOffset.UTC)))
        return message
    }

    /**
     * Mint the cancel's own ClOrdID. Format `${origClOrdID}-cxl-${seq}`. FIX
     * requires unique ClOrdID per outbound message; tag 41 (OrigClOrdID) carries
     * the original.
     */
    private fun mintCancelClOrdId(origClOrdId: String): String =
        "$origClOrdId-cxl-${cancelClOrdIdSeq.incrementAndGet()}"
}

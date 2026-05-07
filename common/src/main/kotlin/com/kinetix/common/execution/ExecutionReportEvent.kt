package com.kinetix.common.execution

import kotlinx.serialization.Serializable

/**
 * Wire format for execution events published by `fix-gateway` on the `execution.reports`
 * Kafka topic (ADR-0035 phase 3). Consumed by `position-service` via `ExecutionReportConsumer`.
 *
 * Produced from inbound FIX messages: 35=8 (ExecutionReport), 35=9 (OrderCancelReject),
 * 35=j (BusinessMessageReject).
 *
 * ## Partition key
 *
 * The topic is partitioned by [clOrdId] (12 partitions). Caller (`position-service`) mints
 * `clOrdId` as a UUID v4, giving uniform partition distribution. Future child-order schemes
 * that share an `OrigClOrdID` parent stem MUST NOT be used as partition keys — they would
 * create hot partitions and break ordering.
 *
 * ## Decimal carriage
 *
 * Quantities and prices are carried as strings (not double / BigDecimal) to avoid float drift
 * across the JSON serialisation boundary, matching the convention in `TradeEventMessage`.
 *
 * ## Discriminator vs raw exec type
 *
 * Both [eventType] (typed) and [execType] (raw FIX tag 150) are carried. The typed discriminator
 * lets consumers branch without a FIX-version-aware mapping table; the raw value preserves
 * `FIXInboundFillEvent` field-for-field compatibility so existing processors do not need
 * signature changes.
 *
 * @property eventId       Producer-minted UUID — unique per published event for trace correlation.
 * @property clOrdId       FIX tag 11 (ClOrdID). Position-service-minted UUID v4. Topic partition key.
 * @property orderId       FIX tag 37 (OrderID, venue-assigned). Empty for 35=j when never assigned.
 * @property execId        FIX tag 17 (ExecID). Unique per execution report at the venue.
 * @property sessionId     FIX session identifier (e.g. `FIX.4.4:SENDER->TARGET`).
 * @property venue         Venue mnemonic (e.g. NYSE, LSE, TSE). Null only when unknown at parse time.
 * @property correlationId Trace propagation correlation id (Tempo span context).
 * @property fixVersion    FIX protocol version (`FIX.4.2`, `FIX.4.4`). Disambiguates execType.
 * @property execType      Raw FIX tag 150 ExecType value (`F`, `1`, `4`, `5`, `8`, ...). Empty for 35=j.
 * @property eventType     Typed discriminator. See [ExecutionEventType].
 * @property lastQty       FIX tag 32 (LastQty). For REPLACED, carries new total quantity.
 * @property lastPrice     FIX tag 31 (LastPx). For REPLACED, carries new limit price.
 * @property cumulativeQty FIX tag 14 (CumQty).
 * @property averagePrice  FIX tag 6 (AvgPx).
 * @property rejectReason  Free-text from FIX tag 58. Set on REJECTED / BUSINESS_REJECT.
 * @property rejectCode    Numeric from FIX tag 102 (OrdRejReason) or 380 (BusinessRejectReason).
 * @property receivedAt    Wire timestamp (ISO-8601 UTC) when fix-gateway received the inbound message.
 */
@Serializable
data class ExecutionReportEvent(
    val eventId: String,
    val clOrdId: String,
    val orderId: String,
    val execId: String,
    val sessionId: String,
    val venue: String? = null,
    val correlationId: String? = null,
    val fixVersion: String,
    val execType: String,
    val eventType: ExecutionEventType,
    val lastQty: String = "0",
    val lastPrice: String = "0",
    val cumulativeQty: String = "0",
    val averagePrice: String = "0",
    val rejectReason: String? = null,
    val rejectCode: String? = null,
    val receivedAt: String,
)

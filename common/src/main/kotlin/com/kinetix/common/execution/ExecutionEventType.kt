package com.kinetix.common.execution

import kotlinx.serialization.Serializable

/**
 * Typed discriminator for execution events published by `fix-gateway` on the
 * `execution.reports` Kafka topic (ADR-0035 phase 3).
 *
 * Consumers prefer this typed enum over the raw FIX `execType` (tag 150) because
 * the same numeric/letter value carries different semantics across FIX versions
 * (e.g. `1` is Partial Fill in FIX 4.2 but represents nothing in 4.4 where Fill
 * is `F`). The mapping `(fixVersion, execType) -> ExecutionEventType` is owned by
 * the `FIXMessageConverter` inside fix-gateway.
 *
 * The raw `execType` is still carried on [ExecutionReportEvent] so consumers that
 * need the original wire value (e.g. the existing `FIXExecutionReportProcessor`)
 * keep working without signature change.
 */
@Serializable
enum class ExecutionEventType {
    /** Full fill of the remaining open quantity (FIX 4.4 ExecType=F with CumQty == OrderQty). */
    FILL,

    /** Partial fill (FIX 4.2 ExecType=1, FIX 4.4 ExecType=F with CumQty < OrderQty). */
    PARTIAL_FILL,

    /** Order cancelled by venue (35=8 ExecType=4). */
    CANCELLED,

    /** Order replaced (35=8 ExecType=5). New quantity/price carried in lastQty/lastPrice. */
    REPLACED,

    /** Reject — either 35=8 ExecType=8 (Rejected) or 35=9 OrderCancelReject. Carries reject reason/code. */
    REJECTED,

    /** 35=j BusinessMessageReject — venue could not process the request at all. */
    BUSINESS_REJECT,
}

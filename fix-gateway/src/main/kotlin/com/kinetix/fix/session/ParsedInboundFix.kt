package com.kinetix.fix.session

import com.kinetix.common.execution.ExecutionReportEvent

/**
 * Internal carrier returned by [FIXMessageConverter.parseRaw] that pairs the typed
 * [ExecutionReportEvent] payload with FIX session-layer flags ([InboundFixHandler]
 * needs to act on, e.g. [possDup] for replay dedup).
 *
 * Most callers want the simpler [FIXMessageConverter.parse] which drops the carrier and
 * returns just the event.
 */
data class ParsedInboundFix(
    val event: ExecutionReportEvent,
    val possDup: Boolean,
)

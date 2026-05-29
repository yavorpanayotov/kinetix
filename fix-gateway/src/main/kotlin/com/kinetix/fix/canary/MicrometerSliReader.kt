package com.kinetix.fix.canary

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.search.MeterNotFoundException
import java.util.concurrent.TimeUnit

/**
 * Production [SliReader] backed by the in-process [MeterRegistry].
 *
 * Derives the three SLIs from counters and timers that [FixGatewayServiceImpl] and
 * [PendingNewCorrelator] already register:
 *
 * **Rejection rate** — `cancel_failed_total` (any reason) / (`fix_messages_out_total`
 * (ORDER_CANCEL_REQUEST) + `cancel_failed_total`).  Returned as 0.0 when no orders
 * have been seen yet (conservative: gate will block on the window, not on a
 * spurious 100% rejection rate).
 *
 * **FIX session uptime** — derived from `fix_session_disconnect_total` relative to
 * wall clock. Since fix-gateway currently has no persistent uptime counter, we
 * approximate via the `cancel_failed_total{reason=SESSION_DOWN}` ratio: if the
 * session-down failure fraction of all outbound messages exceeds the downtime
 * budget, we treat the session as below threshold.  Returned as 100.0 when no
 * session-down events have fired (healthy default).
 *
 * **Average ack latency** — `cancel_ack_latency` Timer's mean over all recorded
 * samples.  Returned as 0.0 when no samples exist yet (healthy default — the gate
 * will block on the window until real traffic arrives).
 *
 * These approximations are intentionally conservative: when data is absent the
 * reader returns safe defaults (no rejection, full uptime, zero latency) so the
 * gate blocks only on the consecutive-window condition, not on missing data.
 */
class MicrometerSliReader(
    private val registry: MeterRegistry,
) : SliReader {

    override fun rejectionRatePct(): Double {
        // All successfully-sent outbound messages are captured under fix_messages_out_total
        // regardless of message type; all failed attempts under cancel_failed_total.
        val rejected = sumCounters("cancel_failed_total")
        val sent = sumCounters("fix_messages_out_total")
        val total = sent + rejected
        return if (total == 0.0) 0.0 else (rejected / total) * 100.0
    }

    override fun uptimePct(): Double {
        val sessionDown = sumCounters("cancel_failed_total", tag = "reason" to "SESSION_DOWN")
        val sent = sumCounters("fix_messages_out_total")
        val total = sent + sessionDown
        if (total == 0.0) return 100.0
        val downFraction = sessionDown / total
        // Uptime is 100% minus the session-down fraction expressed as a percentage.
        return (1.0 - downFraction) * 100.0
    }

    override fun avgAckLatencyMs(): Double {
        val timers = registry.find("cancel_ack_latency").timers()
        if (timers.isEmpty()) return 0.0
        val totalTime = timers.sumOf { it.totalTime(TimeUnit.MILLISECONDS) }
        val totalCount = timers.sumOf { it.count() }
        return if (totalCount == 0L) 0.0 else totalTime / totalCount
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    /**
     * Sum the count of all counters matching [name] and optionally filtered
     * by a single tag key-value pair.  Returns 0.0 when no counters match.
     */
    private fun sumCounters(name: String, tag: Pair<String, String>? = null): Double {
        return try {
            val search = registry.find(name)
            val found = if (tag != null) search.tag(tag.first, tag.second).counters()
            else search.counters()
            found.sumOf { it.count() }
        } catch (_: MeterNotFoundException) {
            0.0
        }
    }
}

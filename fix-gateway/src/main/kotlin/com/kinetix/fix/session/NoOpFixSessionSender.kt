package com.kinetix.fix.session

import org.slf4j.LoggerFactory
import quickfix.Message

/**
 * Selected when `FIX_GATEWAY_LIVE_SESSIONS=false` (default for local dev / CI).
 * Records the outbound message but always returns [SendOutcome.SessionDown] so
 * downstream gRPC clients see realistic degraded behaviour without requiring a
 * live FIX counterparty.
 */
class NoOpFixSessionSender : FixSessionSender {
    private val logger = LoggerFactory.getLogger(NoOpFixSessionSender::class.java)

    override fun send(venue: String, message: Message): SendOutcome {
        val msgType = message.header.getString(35)
        val pretty = message.toString().replace(SOH, '|')
        logger.info("FIX send (no-op, sessions disabled): venue={} msgType={} message={}", venue, msgType, pretty)
        return SendOutcome.SessionDown
    }

    companion object {
        private val SOH: Char = Char(0x01)
    }
}

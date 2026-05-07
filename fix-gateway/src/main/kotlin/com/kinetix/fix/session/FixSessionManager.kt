package com.kinetix.fix.session

import com.kinetix.fix.venue.VenueSessionRegistry
import org.slf4j.LoggerFactory

/**
 * Recovers FIX session sequence state on boot. ADR-0035 phase 2 wires the
 * Postgres-backed seq-num recovery semantics; the QuickFIX/J Initiator wiring
 * (real venue connection, dictionary loading, MessageStoreFactory) is layered
 * on top of this in a follow-on once production venue credentials are wired
 * — production deploys boot with NoOpFixSessionSender so a missing Initiator
 * does not stop the service from starting.
 *
 * Recovery semantics (mirror plan §2.12 FixSessionManagerTest):
 *   - Cold start (no row in fix_session_state for a registered venue) →
 *     write [FixSessionState.coldStart] (seq=1) and return it; the venue
 *     will Logon and increment seq from there.
 *   - Corrupt row (sender_seq_num < 1 or target_seq_num < 1) → throw
 *     [CorruptSessionStateException] which the readiness probe surfaces
 *     as NOT_READY rather than silently using 0.
 *   - Healthy row → return as-is.
 *
 * The seq-behind / seq-ahead / GapFill cases require an active QuickFIX/J
 * Initiator and an in-memory acceptor and are exercised by the follow-on
 * advanced acceptance tests.
 */
class FixSessionManager(
    private val sessionStateRepository: FixSessionStateRepository,
    private val venueSessionRegistry: VenueSessionRegistry,
) {
    private val logger = LoggerFactory.getLogger(FixSessionManager::class.java)

    /**
     * Reconcile every venue's session state row, materialising a cold-start
     * row for any venue without one. Throws [CorruptSessionStateException]
     * if any existing row is invalid — fail-fast at boot, do not start the
     * Initiator with poisoned state.
     */
    suspend fun recoverAll(): Map<String, FixSessionState> {
        val recovered = mutableMapOf<String, FixSessionState>()
        for (session in venueSessionRegistry.all()) {
            recovered[session.venue] = recoverOne(session.venue)
        }
        return recovered
    }

    suspend fun recoverOne(venue: String): FixSessionState {
        val existing = sessionStateRepository.findByVenue(venue)
        if (existing == null) {
            val cold = FixSessionState.coldStart(venue)
            sessionStateRepository.upsert(cold)
            logger.info("FIX session cold-start for venue={} seqs=({}, {})", venue, cold.senderSeqNum, cold.targetSeqNum)
            return cold
        }
        validate(existing)
        logger.info(
            "FIX session recovered for venue={} senderSeq={} targetSeq={}",
            existing.venue, existing.senderSeqNum, existing.targetSeqNum,
        )
        return existing
    }

    private fun validate(state: FixSessionState) {
        if (state.senderSeqNum < 1 || state.targetSeqNum < 1) {
            throw CorruptSessionStateException(
                "fix_session_state row for venue=${state.venue} is corrupt: " +
                    "senderSeq=${state.senderSeqNum} targetSeq=${state.targetSeqNum}",
            )
        }
    }
}

class CorruptSessionStateException(message: String) : RuntimeException(message)

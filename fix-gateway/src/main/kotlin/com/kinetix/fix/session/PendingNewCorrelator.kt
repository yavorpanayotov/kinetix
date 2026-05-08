package com.kinetix.fix.session

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * Pending-new correlator (ADR-0035 §4.4).
 *
 * Owns the in-flight `clOrdID -> CompletableDeferred<Outcome>` map that links a
 * synchronous [PlaceOrder] gRPC call to the inbound 35=8 OrdStatus=A ack arriving
 * asynchronously on the FIX session. Phase 4 explicitly accepts the in-memory
 * design under the phase-1 single-instance HPA pin; active-active or active-passive
 * HA replaces the map with a distributed store (Redis or similar) — captured in
 * the risk register and inherited by the future HA ADR.
 *
 * Invariants this class enforces:
 *   1. **Register-before-send.** Callers register the deferred BEFORE the 35=D
 *      goes on the wire, so an "instant" 35=8 always finds an entry. The
 *      [register] callback runs the on-wire side effect under the entry's
 *      ownership; if it throws, the entry is unwound so the next call with the
 *      same clOrdID is allowed (no permanent slot leak from a transient send
 *      failure).
 *   2. **Atomic completion.** [completePendingNew] / [completeRejected] use
 *      [CompletableDeferred.complete] — losing the race against eviction simply
 *      returns false; the inbound handler still publishes the event downstream.
 *   3. **Back-pressure cap.** Per-venue [Semaphore] caps concurrent in-flight
 *      registrations at [maxInFlightPerVenue]. Blocked attempts wait up to
 *      [backPressureWait] before returning [Registration.BackPressure].
 *      Counter `pending_new_correlator_back_pressure_total{venue}` increments on
 *      every block.
 *   4. **TTL eviction.** Entries older than [ttl] are evicted by [sweepEvictions]
 *      — the gRPC service may either drive this from a background scheduler or
 *      lazily from each [await] (we do the latter to keep the class self-contained
 *      and testable without a clock dependency). Counter
 *      `pending_new_correlator_ttl_evictions_total{venue}` increments per
 *      evicted entry.
 *
 * Threading: the deferred is thread-safe; the map is a [ConcurrentHashMap]; the
 * permit account uses a per-venue [Semaphore]. No coroutine dispatcher is owned
 * by this class — callers supply their own scope when invoking [await].
 */
class PendingNewCorrelator(
    private val maxInFlightPerVenue: Int = DEFAULT_MAX_IN_FLIGHT_PER_VENUE,
    private val backPressureWait: Duration = Duration.ofMillis(100),
    private val ttl: Duration = Duration.ofSeconds(30),
    private val meterRegistry: MeterRegistry,
    private val clock: () -> Instant = Instant::now,
) {
    private val logger = LoggerFactory.getLogger(PendingNewCorrelator::class.java)

    private data class Entry(
        val deferred: CompletableDeferred<Outcome>,
        val registeredAt: Instant,
    )

    private val entries: MutableMap<Key, Entry> = ConcurrentHashMap()
    private val semaphores: MutableMap<String, Semaphore> = ConcurrentHashMap()

    sealed class Outcome {
        data class PendingNew(val venueOrderId: String) : Outcome()
        data class Rejected(val reason: String) : Outcome()

        /** Caller-side timeout — the deferred was never completed within the deadline. */
        object Timeout : Outcome()
    }

    sealed class Registration {
        object Registered : Registration()
        object DuplicateInFlight : Registration()
        object BackPressure : Registration()
    }

    /**
     * Reserve a per-venue permit and register a deferred for [clOrdId]. The [onRegistered]
     * block runs inside the registration so the on-wire 35=D send happens AFTER the entry
     * exists (eliminating the inbound-arrives-before-deferred race) but BEFORE [register]
     * returns to the caller (so the caller is guaranteed `await` will see a registered
     * entry). If [onRegistered] throws, the entry and permit are released so the same
     * clOrdID can be retried after the caller resolves the upstream defect.
     */
    fun register(
        venue: String,
        clOrdId: String,
        onRegistered: () -> Unit,
    ): Registration {
        val semaphore = semaphores.computeIfAbsent(venue) { Semaphore(maxInFlightPerVenue) }
        val acquired = semaphore.tryAcquire(backPressureWait.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!acquired) {
            meterRegistry.counter(
                "pending_new_correlator_back_pressure_total",
                "venue", venue,
            ).increment()
            return Registration.BackPressure
        }

        val key = Key(venue, clOrdId)
        val entry = Entry(deferred = CompletableDeferred(), registeredAt = clock())
        val existing = entries.putIfAbsent(key, entry)
        if (existing != null) {
            // Duplicate — release the permit since this call did not actually claim a slot.
            semaphore.release()
            return Registration.DuplicateInFlight
        }

        try {
            onRegistered()
        } catch (t: Throwable) {
            // The on-wire side effect failed — undo registration so retries are allowed.
            entries.remove(key)
            semaphore.release()
            throw t
        }
        return Registration.Registered
    }

    /**
     * Suspend until the deferred for ([venue], [clOrdId]) completes or [timeout] elapses.
     * Either way the entry is evicted and the per-venue permit released before returning.
     *
     * @throws IllegalStateException if no entry was ever registered for the key — callers
     *   must not invoke [await] without a prior [register].
     */
    suspend fun await(venue: String, clOrdId: String, timeout: Duration): Outcome {
        val key = Key(venue, clOrdId)
        val entry = entries[key]
            ?: throw IllegalStateException("await called without a prior register: venue=$venue clOrdId=$clOrdId")
        return try {
            withTimeout(timeout.toMillis()) { entry.deferred.await() }
        } catch (t: TimeoutCancellationException) {
            Outcome.Timeout
        } finally {
            evict(venue, clOrdId, reason = EvictionReason.AwaitResolved)
        }
    }

    /**
     * Wake the deferred with [Outcome.PendingNew]. Returns true if the entry existed and
     * was completed by THIS call; false if no entry was registered (caller may decide to
     * publish the event downstream regardless — we are not the order-state authority).
     */
    fun completePendingNew(venue: String, clOrdId: String, venueOrderId: String): Boolean {
        val entry = entries[Key(venue, clOrdId)] ?: return false
        return entry.deferred.complete(Outcome.PendingNew(venueOrderId))
    }

    /**
     * Wake the deferred with [Outcome.Rejected]. Returns true if the entry existed and
     * was completed by THIS call.
     */
    fun completeRejected(venue: String, clOrdId: String, reason: String): Boolean {
        val entry = entries[Key(venue, clOrdId)] ?: return false
        return entry.deferred.complete(Outcome.Rejected(reason))
    }

    /**
     * Sweep entries older than [ttl]. Public so tests can drive eviction deterministically.
     * Eviction emits `pending_new_correlator_ttl_evictions_total{venue}` and completes the
     * deferred with [Outcome.Timeout] so any awaiter wakes immediately.
     */
    fun sweepEvictions(): Int {
        val now = clock()
        var evicted = 0
        val cutoff = ttl.toMillis()
        for ((key, entry) in entries.toMap()) {
            val age = Duration.between(entry.registeredAt, now).toMillis()
            if (age >= cutoff) {
                if (evict(key.venue, key.clOrdId, reason = EvictionReason.Ttl)) {
                    evicted += 1
                }
            }
        }
        return evicted
    }

    fun inFlightCount(venue: String): Int =
        entries.keys.count { it.venue == venue }

    private fun evict(venue: String, clOrdId: String, reason: EvictionReason): Boolean {
        val entry = entries.remove(Key(venue, clOrdId)) ?: return false
        // Wake any awaiter that is still suspended.
        entry.deferred.complete(Outcome.Timeout)
        semaphores[venue]?.release()
        if (reason == EvictionReason.Ttl) {
            meterRegistry.counter(
                "pending_new_correlator_ttl_evictions_total",
                "venue", venue,
            ).increment()
            logger.warn("PendingNewCorrelator TTL eviction venue={} clOrdId={}", venue, clOrdId)
        }
        return true
    }

    private enum class EvictionReason { AwaitResolved, Ttl }

    private data class Key(val venue: String, val clOrdId: String)

    companion object {
        /**
         * Per-venue cap. ADR-0035 §4.4 picks 500 — sufficient headroom for launch venues
         * (target throughput at NYSE is ~50 PlaceOrders/sec p99; 500 covers a 10-second
         * stall before back-pressure fires).
         */
        const val DEFAULT_MAX_IN_FLIGHT_PER_VENUE = 500
    }
}

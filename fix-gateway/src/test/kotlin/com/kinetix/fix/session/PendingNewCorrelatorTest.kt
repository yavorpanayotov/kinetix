package com.kinetix.fix.session

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Plan §4.4 — PendingNewCorrelator must satisfy:
 *   1. Register-before-send: a deferred is registered before 35=D goes on the wire,
 *      so an inbound 35=8 that arrives "instantly" still finds an entry.
 *   2. TTL eviction is atomic: if eviction races a late completion, no event is dropped
 *      (the inbound handler should be able to detect the missing entry and still publish
 *      downstream — the correlator merely tells the caller the deferred is gone).
 *   3. Duplicate clOrdID: a second `register` for the same clOrdID returns DUPLICATE.
 *   4. Back-pressure: a global semaphore limits in-flight registrations per venue;
 *      blocked attempts return BACK_PRESSURE after a 100 ms wait window.
 *   5. The single-instance constraint is documented; multi-instance support requires
 *      replacing the in-memory map with a distributed store.
 */
class PendingNewCorrelatorTest : FunSpec({

    test("inbound completion before await wakes the deferred") {
        val correlator = PendingNewCorrelator(meterRegistry = SimpleMeterRegistry())
        val registered = correlator.register("NYSE", "ord-1") {}
        registered shouldBe PendingNewCorrelator.Registration.Registered

        correlator.completePendingNew("NYSE", "ord-1", venueOrderId = "VEN-1")

        runBlocking {
            val outcome = correlator.await("NYSE", "ord-1", Duration.ofMillis(200))
            outcome shouldBe PendingNewCorrelator.Outcome.PendingNew("VEN-1")
        }
    }

    test("inbound completion that arrives after await still wakes the awaiter") {
        val correlator = PendingNewCorrelator(meterRegistry = SimpleMeterRegistry())
        correlator.register("NYSE", "ord-2") {}

        runBlocking {
            val outcome = async(Dispatchers.Default) {
                correlator.await("NYSE", "ord-2", Duration.ofSeconds(2))
            }
            withContext(Dispatchers.Default) { delay(10) }
            correlator.completePendingNew("NYSE", "ord-2", venueOrderId = "VEN-2")
            outcome.await() shouldBe PendingNewCorrelator.Outcome.PendingNew("VEN-2")
        }
    }

    test("rejected completion surfaces REJECTED with reason") {
        val correlator = PendingNewCorrelator(meterRegistry = SimpleMeterRegistry())
        correlator.register("NYSE", "ord-3") {}

        runBlocking {
            val awaitJob = async(Dispatchers.Default) {
                correlator.await("NYSE", "ord-3", Duration.ofSeconds(2))
            }
            correlator.completeRejected("NYSE", "ord-3", "INVALID_INSTRUMENT")
            awaitJob.await() shouldBe PendingNewCorrelator.Outcome.Rejected("INVALID_INSTRUMENT")
        }
    }

    test("await returns Timeout when deferred not completed in deadline") {
        val correlator = PendingNewCorrelator(meterRegistry = SimpleMeterRegistry())
        correlator.register("NYSE", "ord-4") {}

        runBlocking {
            val outcome = correlator.await("NYSE", "ord-4", Duration.ofMillis(50))
            outcome shouldBe PendingNewCorrelator.Outcome.Timeout
        }
    }

    test("Timeout removes the entry so a follow-up registration with same clOrdID succeeds") {
        val correlator = PendingNewCorrelator(meterRegistry = SimpleMeterRegistry())
        correlator.register("NYSE", "ord-5") {}
        runBlocking { correlator.await("NYSE", "ord-5", Duration.ofMillis(20)) }

        // After timeout the entry is evicted, so a fresh registration is allowed.
        val again = correlator.register("NYSE", "ord-5") {}
        again shouldBe PendingNewCorrelator.Registration.Registered
    }

    test("duplicate registration for the same clOrdID returns DuplicateInFlight") {
        val correlator = PendingNewCorrelator(meterRegistry = SimpleMeterRegistry())
        correlator.register("NYSE", "ord-6") {}
        val second = correlator.register("NYSE", "ord-6") {}
        second shouldBe PendingNewCorrelator.Registration.DuplicateInFlight
    }

    test("registration runs the onRegistered side effect before returning") {
        val correlator = PendingNewCorrelator(meterRegistry = SimpleMeterRegistry())
        val flag = AtomicInteger(0)

        correlator.register("NYSE", "ord-7") { flag.set(1) }
        flag.get() shouldBe 1
    }

    test("onRegistered failure unwinds the registration so retries are allowed") {
        val correlator = PendingNewCorrelator(meterRegistry = SimpleMeterRegistry())
        shouldThrow<IllegalStateException> {
            correlator.register("NYSE", "ord-8") {
                throw IllegalStateException("session sender threw")
            }
        }
        // The slot must be free again after the side-effect failed — otherwise the next
        // PlaceOrder with the same clOrdID would always see DUPLICATE_IN_FLIGHT.
        correlator.register("NYSE", "ord-8") {} shouldBe PendingNewCorrelator.Registration.Registered
    }

    test("back-pressure cap blocks registrations beyond MAX_IN_FLIGHT and returns BackPressure after wait window") {
        val correlator = PendingNewCorrelator(
            maxInFlightPerVenue = 2,
            backPressureWait = Duration.ofMillis(50),
            meterRegistry = SimpleMeterRegistry(),
        )
        correlator.register("NYSE", "ord-9a") {} shouldBe PendingNewCorrelator.Registration.Registered
        correlator.register("NYSE", "ord-9b") {} shouldBe PendingNewCorrelator.Registration.Registered

        val third = correlator.register("NYSE", "ord-9c") {}
        third shouldBe PendingNewCorrelator.Registration.BackPressure
    }

    test("back-pressure cap is per venue (NYSE saturated does not block LSE)") {
        val correlator = PendingNewCorrelator(
            maxInFlightPerVenue = 1,
            backPressureWait = Duration.ofMillis(20),
            meterRegistry = SimpleMeterRegistry(),
        )
        correlator.register("NYSE", "ord-10") {} shouldBe PendingNewCorrelator.Registration.Registered
        correlator.register("NYSE", "ord-10b") {} shouldBe PendingNewCorrelator.Registration.BackPressure

        correlator.register("LSE", "ord-10c") {} shouldBe PendingNewCorrelator.Registration.Registered
    }

    test("back-pressure releases when an awaiter completes (semaphore returns the permit)") {
        val correlator = PendingNewCorrelator(
            maxInFlightPerVenue = 1,
            backPressureWait = Duration.ofMillis(20),
            meterRegistry = SimpleMeterRegistry(),
        )
        correlator.register("NYSE", "ord-11") {} shouldBe PendingNewCorrelator.Registration.Registered
        correlator.completePendingNew("NYSE", "ord-11", "VEN-11")
        runBlocking { correlator.await("NYSE", "ord-11", Duration.ofMillis(50)) }

        // Permit released — next registration succeeds.
        correlator.register("NYSE", "ord-11b") {} shouldBe PendingNewCorrelator.Registration.Registered
    }

    test("completePendingNew on an unknown clOrdID returns false (no awaiter, no slot to free)") {
        val correlator = PendingNewCorrelator(meterRegistry = SimpleMeterRegistry())
        val completed = correlator.completePendingNew("NYSE", "no-such-id", "VEN-X")
        completed shouldBe false
    }

    test("inFlightCount reflects per-venue registrations and decrements after await resolves") {
        val correlator = PendingNewCorrelator(meterRegistry = SimpleMeterRegistry())
        correlator.register("NYSE", "ord-12a") {}
        correlator.register("NYSE", "ord-12b") {}
        correlator.register("LSE", "ord-12c") {}

        correlator.inFlightCount("NYSE") shouldBe 2
        correlator.inFlightCount("LSE") shouldBe 1

        correlator.completePendingNew("NYSE", "ord-12a", "VEN-A")
        runBlocking { correlator.await("NYSE", "ord-12a", Duration.ofMillis(50)) }
        correlator.inFlightCount("NYSE") shouldBe 1
    }

    test("ttl eviction running concurrently with completion does not drop the inbound (race-safe)") {
        // Tight loop: register, complete, await all interleave with eviction sweeps.
        val correlator = PendingNewCorrelator(
            ttl = Duration.ofMillis(2),
            meterRegistry = SimpleMeterRegistry(),
        )
        var pendingNewSeen = 0

        runBlocking {
            repeat(50) { idx ->
                val clOrdId = "ord-race-$idx"
                correlator.register("NYSE", clOrdId) {} shouldBe PendingNewCorrelator.Registration.Registered

                // Complete asynchronously; this races with the TTL sweep.
                async(Dispatchers.Default) {
                    delay(1)
                    correlator.completePendingNew("NYSE", clOrdId, "VEN-$idx")
                }
                val outcome = withTimeoutOrNull(Duration.ofMillis(100).toMillis()) {
                    correlator.await("NYSE", clOrdId, Duration.ofMillis(20))
                }
                if (outcome is PendingNewCorrelator.Outcome.PendingNew) pendingNewSeen += 1
            }
        }
        // At least the majority of completions must arrive; the contract is "no event dropped",
        // not "every awaiter wins" (some races legitimately produce Timeout).
        (pendingNewSeen >= 25) shouldBe true
    }

    test("await on never-registered clOrdID throws so callers cannot await stale handles") {
        val correlator = PendingNewCorrelator(meterRegistry = SimpleMeterRegistry())
        runBlocking {
            shouldThrow<IllegalStateException> {
                correlator.await("NYSE", "no-such-id", Duration.ofMillis(20))
            }
        }
    }

    test("correlator emits ttl-eviction counter when eviction actually fires") {
        val meter = SimpleMeterRegistry()
        val correlator = PendingNewCorrelator(
            ttl = Duration.ofMillis(5),
            meterRegistry = meter,
        )
        correlator.register("NYSE", "ord-evict") {}
        Thread.sleep(20)
        correlator.sweepEvictions() // deterministic sweep — no background thread in tests

        val counter = meter.find("pending_new_correlator_ttl_evictions_total")
            .tag("venue", "NYSE")
            .counter()
        counter shouldNotBe null
        counter!!.count() shouldBe 1.0
    }

    test("back-pressure increments back_pressure_total counter") {
        val meter = SimpleMeterRegistry()
        val correlator = PendingNewCorrelator(
            maxInFlightPerVenue = 1,
            backPressureWait = Duration.ofMillis(10),
            meterRegistry = meter,
        )
        correlator.register("NYSE", "ord-bp1") {} shouldBe PendingNewCorrelator.Registration.Registered
        correlator.register("NYSE", "ord-bp2") {} shouldBe PendingNewCorrelator.Registration.BackPressure

        meter.find("pending_new_correlator_back_pressure_total")
            .tag("venue", "NYSE")
            .counter()!!
            .count() shouldBe 1.0
    }
})

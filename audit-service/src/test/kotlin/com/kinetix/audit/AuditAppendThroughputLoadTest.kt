package com.kinetix.audit

import com.kinetix.audit.model.AuditEvent
import com.kinetix.audit.persistence.AuditEventRepository
import com.kinetix.audit.persistence.AuditHasher
import com.kinetix.audit.persistence.DatabaseTestSetup
import com.kinetix.audit.persistence.ExposedAuditEventRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.junit.jupiter.api.Tag
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sustained-throughput load test for the audit hash-chain append path.
 *
 * Drives `LOAD_TEST_RATE_PER_SEC` audit events per second for `LOAD_TEST_DURATION_SECONDS`
 * through `ExposedAuditEventRepository.save`, with calls fanned out across many concurrent
 * coroutines so the cluster-wide advisory lock at the top of `save()` is exercised under
 * realistic contention. Asserts the four properties that matter for a tamper-evident chain
 * under load:
 *
 *   1. Every event lands — `findAll().size == totalEvents`.
 *   2. The chain remains valid — `verifyChain(events).valid == true`.
 *   3. Sustained throughput tracks the target — achieved >= 80% of `LOAD_TEST_RATE_PER_SEC`.
 *   4. p99 append latency below [P99_LATENCY_BUDGET_MS] — early-warning on lock contention.
 *
 * The default profile (100/s × 30s = 3000 events) is sized for the per-commit CI runner.
 * Nightly soak overrides via env vars to exercise higher rates.
 *
 * Tagged `load` and named `*LoadTest` so it's filtered out of the default `:test`,
 * `:acceptanceTest`, and `:integrationTest` tasks (see kinetix.kotlin-testing.gradle.kts);
 * runs only via `:audit-service:loadTest`, wired into the CI `load-tests` job.
 */
@Tag("load")
class AuditAppendThroughputLoadTest : FunSpec({

    val ratePerSec = System.getenv("LOAD_TEST_RATE_PER_SEC")?.toIntOrNull() ?: 100
    val durationSec = System.getenv("LOAD_TEST_DURATION_SECONDS")?.toIntOrNull() ?: 30
    val totalEvents = ratePerSec * durationSec

    val db = DatabaseTestSetup.startAndMigrate()
    val repository: AuditEventRepository = ExposedAuditEventRepository(db)

    beforeSpec {
        newSuspendedTransaction(db = db) {
            exec("TRUNCATE TABLE audit_events RESTART IDENTITY")
        }
    }

    test(
        "$totalEvents audit events @ ${ratePerSec}/s through ExposedAuditEventRepository.save: " +
            "all persisted, chain valid, throughput >= 80% target, p99 < ${P99_LATENCY_BUDGET_MS}ms",
    ) {
        val latencies = LongArray(totalEvents)
        val latencyIdx = AtomicInteger(0)

        val publishStart = System.nanoTime()
        coroutineScope {
            val nanosPerEvent = 1_000_000_000L / ratePerSec
            val jobs = mutableListOf<Deferred<Unit>>()
            for (i in 0 until totalEvents) {
                val deadline = publishStart + nanosPerEvent * i
                val sleep = deadline - System.nanoTime()
                if (sleep > 0) delay(TimeUnit.NANOSECONDS.toMillis(sleep).coerceAtLeast(1))
                jobs += async(Dispatchers.IO) {
                    val t0 = System.nanoTime()
                    repository.save(loadEvent(i))
                    val elapsed = System.nanoTime() - t0
                    val idx = latencyIdx.getAndIncrement()
                    if (idx < latencies.size) latencies[idx] = elapsed
                }
            }
            jobs.awaitAll()
        }
        val wallSec = (System.nanoTime() - publishStart) / 1_000_000_000.0

        val events = repository.findAll()
        val verification = AuditHasher.verifyChain(events)
        val p50Ms = percentileMs(latencies, latencyIdx.get(), 0.50)
        val p99Ms = percentileMs(latencies, latencyIdx.get(), 0.99)
        val achievedPerSec = events.size / wallSec
        val targetFloor = ratePerSec * 0.80

        println(
            "[audit-load-test] target=$totalEvents persisted=${events.size} " +
                "achieved=${"%.1f".format(achievedPerSec)}/s " +
                "p50=${"%.2f".format(p50Ms)}ms p99=${"%.2f".format(p99Ms)}ms " +
                "wallSec=${"%.2f".format(wallSec)} chainValid=${verification.valid}",
        )

        events.size shouldBe totalEvents
        verification.valid shouldBe true
        achievedPerSec shouldBeAtLeast targetFloor
        p99Ms.toLong() shouldBeLessThan P99_LATENCY_BUDGET_MS
    }
}) {
    companion object {
        const val P99_LATENCY_BUDGET_MS = 1_000L
    }
}

private val LOAD_BASE_TIME: Instant = Instant.parse("2026-05-08T09:00:00Z")

private fun loadEvent(i: Int): AuditEvent = AuditEvent(
    tradeId = "load-t-$i",
    bookId = "book-load-${i % 10}",
    instrumentId = "INSTR-${i % 20}",
    assetClass = "EQUITY",
    side = if (i % 2 == 0) "BUY" else "SELL",
    quantity = "100",
    priceAmount = "150.00",
    priceCurrency = "USD",
    tradedAt = "2026-05-08T09:00:00Z",
    receivedAt = LOAD_BASE_TIME.plusNanos(i.toLong()),
    eventType = "TRADE_BOOKED",
)

private fun percentileMs(latencies: LongArray, count: Int, percentile: Double): Double {
    if (count == 0) return 0.0
    val slice = latencies.copyOfRange(0, count)
    slice.sort()
    val idx = ((slice.size - 1) * percentile).toInt().coerceIn(0, slice.size - 1)
    return slice[idx] / 1_000_000.0
}

private infix fun Double.shouldBeAtLeast(min: Double) {
    if (this < min) {
        throw AssertionError("expected throughput >= $min/s, was $this/s")
    }
}

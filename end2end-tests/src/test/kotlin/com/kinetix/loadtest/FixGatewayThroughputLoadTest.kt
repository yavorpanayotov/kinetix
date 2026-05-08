package com.kinetix.loadtest

import com.kinetix.common.execution.ExecutionEventType
import com.kinetix.common.execution.ExecutionReportEvent
import com.kinetix.common.model.Side
import com.kinetix.fix.kafka.ExecutionReportProducerFactory
import com.kinetix.fix.kafka.KafkaExecutionReportPublisher
import com.kinetix.position.fix.ExecutionCostService
import com.kinetix.position.fix.ExecutionFillsTable
import com.kinetix.position.fix.ExposedExecutionCostRepository
import com.kinetix.position.fix.ExposedExecutionFillRepository
import com.kinetix.position.fix.ExposedExecutionOrderRepository
import com.kinetix.position.fix.ExposedGhostFillRepository
import com.kinetix.position.fix.FIXExecutionReportProcessor
import com.kinetix.position.fix.GhostFillsTable
import com.kinetix.position.fix.Order
import com.kinetix.position.fix.OrderStatus
import com.kinetix.position.fix.TimeInForce
import com.kinetix.position.kafka.ExecutionReportDispatcher
import com.kinetix.position.kafka.NoOpRiskBreakPublisher
import com.kinetix.position.kafka.NoOpTradeEventPublisher
import com.kinetix.position.persistence.DatabaseConfig
import com.kinetix.position.persistence.DatabaseFactory
import com.kinetix.position.persistence.ExposedPositionRepository
import com.kinetix.position.persistence.ExposedTradeEventRepository
import com.kinetix.position.service.ExposedTransactionalRunner
import com.kinetix.position.service.TradeBookingService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.junit.jupiter.api.Tag
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.kafka.KafkaContainer
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Load / soak test for the inbound FIX execution-report path (ADR-0035 phase 3 §3.13).
 *
 * Floods events through `fix-gateway → execution.reports → position-service` and asserts
 * the four hard SLOs from the plan:
 *
 *   1. Zero missing fills — published count == persisted [ExecutionFill] count.
 *   2. p99 dispatch latency below [P99_LATENCY_BUDGET_MS] — measured around the
 *      [ExecutionReportDispatcher.dispatchRaw] call (which covers the dedup check, fill
 *      insert, order-status update, trade-event insert, and position upsert).
 *   3. Final consumer lag below [MAX_FINAL_LAG] — committed-offset vs end-offset across
 *      every partition of the throwaway topic, queried via [AdminClient].
 *   4. Zero ghost fills — none of the seeded orders are terminal so the ghost-fill row
 *      count must be exactly zero at end of run.
 *
 * The default profile is sized for the per-commit CI runner (≈ 3000 messages over 30s).
 * Nightly soak runs override via the env vars below to exercise the plan's "1000 fills/s
 * for 5 minutes" scenario:
 *
 *   - LOAD_TEST_RATE_PER_SEC      target produce rate (fills/second), default 100
 *   - LOAD_TEST_DURATION_SECONDS  produce duration in seconds, default 30
 *   - LOAD_TEST_NUM_ORDERS        seed-order count to spread load over, default 100
 *
 * Tagged `load` so it is filterable via JUnit `excludedGroups`. The Gradle wiring keeps
 * `*LoadTest` out of the default `:test` and `:end2EndTest` tasks and exposes it via
 * `:end2end-tests:loadTest`.
 */
@Tag("load")
class FixGatewayThroughputLoadTest : FunSpec({

    val ratePerSec = System.getenv("LOAD_TEST_RATE_PER_SEC")?.toIntOrNull() ?: 100
    val durationSec = System.getenv("LOAD_TEST_DURATION_SECONDS")?.toIntOrNull() ?: 30
    val numOrders = System.getenv("LOAD_TEST_NUM_ORDERS")?.toIntOrNull() ?: 100
    val totalFills = ratePerSec * durationSec
    val orderQuantity = BigDecimal("10000000")

    val postgres = PostgreSQLContainer("postgres:17-alpine")
        .withDatabaseName("position_load")
        .withUsername("test")
        .withPassword("test")
    val kafka = KafkaContainer("apache/kafka:3.8.1")

    lateinit var orderRepo: ExposedExecutionOrderRepository
    lateinit var fillRepo: ExposedExecutionFillRepository
    lateinit var ghostFillRepo: ExposedGhostFillRepository
    lateinit var dispatcher: ExecutionReportDispatcher
    lateinit var registry: SimpleMeterRegistry
    lateinit var topic: String
    lateinit var orderIds: List<String>
    val groupId = "exec-reports-load-${UUID.randomUUID()}"

    beforeSpec {
        postgres.start()
        kafka.start()
        topic = "execution.reports.load-${UUID.randomUUID()}"

        AdminClient.create(
            mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers),
        ).use { admin ->
            admin.createTopics(listOf(NewTopic(topic, TOPIC_PARTITIONS, REPLICATION_FACTOR))).all().get()
        }

        val db = DatabaseFactory.init(
            DatabaseConfig(
                jdbcUrl = postgres.jdbcUrl,
                username = postgres.username,
                password = postgres.password,
                maxPoolSize = 8,
            ),
        )
        orderRepo = ExposedExecutionOrderRepository(db)
        fillRepo = ExposedExecutionFillRepository(db)
        ghostFillRepo = ExposedGhostFillRepository(db)
        val tradeEventRepo = ExposedTradeEventRepository(db)
        val transactional = ExposedTransactionalRunner(db)
        val tradeBookingService = TradeBookingService(
            tradeEventRepository = tradeEventRepo,
            positionRepository = ExposedPositionRepository(db),
            transactional = transactional,
            tradeEventPublisher = NoOpTradeEventPublisher(),
            limitCheckService = null,
        )
        registry = SimpleMeterRegistry()
        val processor = FIXExecutionReportProcessor(
            orderRepository = orderRepo,
            fillRepository = fillRepo,
            tradeBookingService = tradeBookingService,
            executionCostService = ExecutionCostService(),
            executionCostRepository = ExposedExecutionCostRepository(db),
            ghostFillRepository = ghostFillRepo,
            riskBreakPublisher = NoOpRiskBreakPublisher(),
            meterRegistry = registry,
        )
        dispatcher = ExecutionReportDispatcher(processor, registry)

        orderIds = (0 until numOrders).map { i ->
            val orderId = "load-ord-$i-${UUID.randomUUID()}"
            orderRepo.save(
                Order(
                    orderId = orderId,
                    bookId = "book-load-${i % 10}",
                    instrumentId = "INSTR-${i % 20}",
                    side = Side.BUY,
                    quantity = orderQuantity,
                    orderType = "LIMIT",
                    limitPrice = BigDecimal("100.00"),
                    arrivalPrice = BigDecimal("99.99"),
                    submittedAt = Instant.parse("2026-05-07T09:00:00Z"),
                    status = OrderStatus.SENT,
                    riskCheckResult = "APPROVED",
                    riskCheckDetails = null,
                    fixSessionId = "FIX.4.4:KX->LOAD",
                    timeInForce = TimeInForce.DAY,
                    instrumentType = "CASH_EQUITY",
                ),
            )
            orderId
        }
    }

    afterSpec {
        kafka.stop()
        postgres.stop()
    }

    test(
        "$totalFills fills @ ${ratePerSec}/s through fix-gateway→Kafka→position-service: " +
            "zero loss, p99<${P99_LATENCY_BUDGET_MS}ms, lag<$MAX_FINAL_LAG, zero ghost fills",
    ) {
        val latenciesNs = LongArray(totalFills)
        val latencyIdx = AtomicInteger(0)

        val publisher = KafkaExecutionReportPublisher(
            ExecutionReportProducerFactory.idempotent(kafka.bootstrapServers),
            topic = topic,
        )
        val consumer = newConsumer(kafka.bootstrapServers, groupId)
        val consumerLoop = LoadTestConsumerLoop(
            consumer = consumer,
            dispatcher = dispatcher,
            topic = topic,
            latencies = latenciesNs,
            latencyIdx = latencyIdx,
        )

        val consumerJob: Job = CoroutineScope(Dispatchers.IO).launch { consumerLoop.start() }
        try {
            val publishStart = System.nanoTime()
            publishAtRate(publisher, orderIds, totalFills, ratePerSec)
            val publishDurationSec = (System.nanoTime() - publishStart) / 1_000_000_000.0

            // Bound the drain wait at 5x the publish wall-time (or 60s, whichever is larger)
            // to cover single-threaded consumer back-pressure on small CI runners.
            val drainBudgetMs = (publishDurationSec * 5_000).toLong().coerceAtLeast(60_000L)
            withTimeout(drainBudgetMs) {
                while (countFills() < totalFills) {
                    delay(500)
                }
            }
            // Quiescence: let the final offset commit land before lag is read.
            delay(2_000)

            val persisted = countFills()
            val ghostCount = countGhostFills()
            val lag = computeLag(kafka.bootstrapServers, topic, groupId)
            val p99Ms = percentileMs(latenciesNs, latencyIdx.get(), 0.99)
            val p50Ms = percentileMs(latenciesNs, latencyIdx.get(), 0.50)

            println(
                "[load-test] published=$totalFills persisted=$persisted ghost=$ghostCount " +
                    "lag=$lag p50=${"%.2f".format(p50Ms)}ms p99=${"%.2f".format(p99Ms)}ms " +
                    "publishDurationSec=${"%.2f".format(publishDurationSec)}",
            )

            persisted shouldBe totalFills.toLong()
            ghostCount shouldBe 0L
            lag shouldBeLessThan MAX_FINAL_LAG
            p99Ms.toLong() shouldBeLessThan P99_LATENCY_BUDGET_MS
        } finally {
            consumerJob.cancel()
        }
    }
}) {
    companion object {
        const val TOPIC_PARTITIONS = 12
        const val REPLICATION_FACTOR: Short = 1
        const val MAX_FINAL_LAG = 500L
        const val P99_LATENCY_BUDGET_MS = 50L
    }
}

/**
 * Bespoke consumer loop for the load test.
 *
 * Mirrors [com.kinetix.position.kafka.ExecutionReportConsumer] (same poll cadence, same
 * manual offset commits, same Cooperative Sticky assignor) but adds dispatch timing so the
 * test can assert p99 directly. The production consumer cannot be instrumented from
 * outside (its `dispatcher` field is a concrete class, not an interface).
 */
private class LoadTestConsumerLoop(
    private val consumer: KafkaConsumer<String, String>,
    private val dispatcher: ExecutionReportDispatcher,
    private val topic: String,
    private val latencies: LongArray,
    private val latencyIdx: AtomicInteger,
    private val pollTimeout: Duration = Duration.ofMillis(500),
) {
    suspend fun start() {
        withContext(Dispatchers.IO) { consumer.subscribe(listOf(topic)) }
        try {
            while (currentCoroutineContext().isActive) {
                val records = withContext(Dispatchers.IO) { consumer.poll(pollTimeout) }
                for (record in records) {
                    val start = System.nanoTime()
                    dispatcher.dispatchRaw(
                        payload = record.value(),
                        offset = record.offset(),
                        partition = record.partition(),
                        key = record.key(),
                    )
                    val elapsed = System.nanoTime() - start
                    val idx = latencyIdx.getAndIncrement()
                    if (idx < latencies.size) latencies[idx] = elapsed
                    withContext(Dispatchers.IO) {
                        consumer.commitSync(
                            mapOf(
                                TopicPartition(record.topic(), record.partition()) to
                                    OffsetAndMetadata(record.offset() + 1),
                            ),
                        )
                    }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                consumer.close(Duration.ofSeconds(10))
            }
        }
    }
}

private suspend fun publishAtRate(
    publisher: KafkaExecutionReportPublisher,
    orderIds: List<String>,
    totalFills: Int,
    ratePerSec: Int,
) {
    val nanosPerFill = 1_000_000_000L / ratePerSec
    val start = System.nanoTime()
    for (i in 0 until totalFills) {
        val deadline = start + nanosPerFill * i
        val sleep = deadline - System.nanoTime()
        if (sleep > 0) delay(TimeUnit.NANOSECONDS.toMillis(sleep).coerceAtLeast(1))
        val orderId = orderIds[i % orderIds.size]
        publisher.publish(loadEvent(orderId))
    }
}

private fun loadEvent(clOrdId: String): ExecutionReportEvent {
    val execId = UUID.randomUUID().toString()
    return ExecutionReportEvent(
        eventId = "evt-$execId",
        clOrdId = clOrdId,
        orderId = "venue-37-$clOrdId",
        execId = execId,
        sessionId = "FIX.4.4:KX->LOAD",
        venue = "NYSE",
        fixVersion = "FIX.4.4",
        execType = "1",
        eventType = ExecutionEventType.PARTIAL_FILL,
        lastQty = "1",
        lastPrice = "100.00",
        cumulativeQty = "1",
        averagePrice = "100.00",
        receivedAt = Instant.now().toString(),
    )
}

private suspend fun countFills(): Long = newSuspendedTransaction {
    ExecutionFillsTable.selectAll().count()
}

private suspend fun countGhostFills(): Long = newSuspendedTransaction {
    GhostFillsTable.selectAll().count()
}

private fun newConsumer(bootstrap: String, groupId: String): KafkaConsumer<String, String> {
    val props = Properties().apply {
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap)
        put(ConsumerConfig.GROUP_ID_CONFIG, groupId)
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        put(
            ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
            "org.apache.kafka.clients.consumer.CooperativeStickyAssignor",
        )
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
        put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500")
    }
    return KafkaConsumer(props)
}

private fun computeLag(bootstrap: String, topic: String, groupId: String): Long {
    AdminClient.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap)).use { admin ->
        val committed = admin.listConsumerGroupOffsets(groupId)
            .partitionsToOffsetAndMetadata().get()
            .filterKeys { it.topic() == topic }
        if (committed.isEmpty()) return 0L
        val endOffsets = admin.listOffsets(
            committed.keys.associateWith { OffsetSpec.latest() },
        ).all().get()
        return committed.entries.sumOf { (tp, meta) ->
            val end = endOffsets[tp]?.offset() ?: 0L
            (end - meta.offset()).coerceAtLeast(0)
        }
    }
}

private fun percentileMs(latencies: LongArray, count: Int, percentile: Double): Double {
    if (count == 0) return 0.0
    val slice = latencies.copyOfRange(0, count)
    slice.sort()
    val idx = ((slice.size - 1) * percentile).toInt().coerceIn(0, slice.size - 1)
    return slice[idx] / 1_000_000.0
}

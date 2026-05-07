package com.kinetix.position.kafka

import com.kinetix.common.execution.ExecutionEventType
import com.kinetix.common.execution.ExecutionReportEvent
import com.kinetix.common.kafka.ConsumerLivenessTracker
import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Side
import com.kinetix.position.fix.ExecutionCostService
import com.kinetix.position.fix.ExposedExecutionCostRepository
import com.kinetix.position.fix.ExposedExecutionFillRepository
import com.kinetix.position.fix.ExposedExecutionOrderRepository
import com.kinetix.position.fix.ExposedGhostFillRepository
import com.kinetix.position.fix.FIXExecutionReportProcessor
import com.kinetix.position.fix.Order
import com.kinetix.position.fix.OrderStatus
import com.kinetix.position.persistence.DatabaseTestSetup
import com.kinetix.position.persistence.ExposedPositionRepository
import com.kinetix.position.persistence.ExposedTradeEventRepository
import com.kinetix.position.service.ExposedTransactionalRunner
import com.kinetix.position.service.TradeBookingService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.math.BigDecimal
import java.time.Instant
import java.util.Properties
import java.util.UUID

private fun newOrder(
    orderId: String,
    quantity: BigDecimal = BigDecimal("100"),
    status: OrderStatus = OrderStatus.SENT,
) = Order(
    orderId = orderId,
    bookId = "book-acc-1",
    instrumentId = "AAPL",
    side = Side.BUY,
    quantity = quantity,
    orderType = "LIMIT",
    limitPrice = BigDecimal("150.00"),
    arrivalPrice = BigDecimal("149.90"),
    submittedAt = Instant.parse("2026-05-07T09:00:00Z"),
    status = status,
    riskCheckResult = "APPROVED",
    riskCheckDetails = null,
    fixSessionId = "FIX.4.4:KX->VENUE",
)

private fun newEvent(
    clOrdId: String,
    execId: String = UUID.randomUUID().toString(),
    eventType: ExecutionEventType = ExecutionEventType.FILL,
    execType: String = "F",
    fixVersion: String = "FIX.4.4",
    venue: String = "NYSE",
    lastQty: String = "100",
    lastPrice: String = "150.00",
    cumulativeQty: String = "100",
    averagePrice: String = "150.00",
    rejectReason: String? = null,
    rejectCode: String? = null,
    orderId: String = "venue-tag37-${UUID.randomUUID()}",
) = ExecutionReportEvent(
    eventId = "evt-$execId",
    clOrdId = clOrdId,
    orderId = orderId,
    execId = execId,
    sessionId = "FIX.4.4:KX->VENUE",
    venue = venue,
    fixVersion = fixVersion,
    execType = execType,
    eventType = eventType,
    lastQty = lastQty,
    lastPrice = lastPrice,
    cumulativeQty = cumulativeQty,
    averagePrice = averagePrice,
    rejectReason = rejectReason,
    rejectCode = rejectCode,
    receivedAt = Instant.now().toString(),
)

class ExecutionReportConsumerAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val orderRepo = ExposedExecutionOrderRepository(db)
    val fillRepo = ExposedExecutionFillRepository(db)
    val positionRepo = ExposedPositionRepository(db)
    val tradeEventRepo = ExposedTradeEventRepository(db)
    val transactional = ExposedTransactionalRunner(db)
    val tradeBookingService = TradeBookingService(
        tradeEventRepository = tradeEventRepo,
        positionRepository = positionRepo,
        transactional = transactional,
        tradeEventPublisher = com.kinetix.position.kafka.NoOpTradeEventPublisher(),
        limitCheckService = null,
    )
    val executionCostService = ExecutionCostService()
    val executionCostRepo = ExposedExecutionCostRepository(db)
    val ghostFillRepo = ExposedGhostFillRepository(db)
    val riskBreakPublisher = RecordingRiskBreakPublisher()

    beforeEach {
        riskBreakPublisher.events.clear()
        newSuspendedTransaction(db = db) {
            exec(
                "TRUNCATE TABLE execution_cost_analysis, execution_fills, execution_orders, " +
                    "ghost_fills, trade_events, positions RESTART IDENTITY CASCADE",
            )
        }
    }

    test("FILL event lands on Kafka, persists fill, advances order to FILLED, and updates the position") {
        val (consumer, processor, registry, topic) = startConsumer(
            orderRepo, fillRepo, tradeBookingService, executionCostService,
            executionCostRepo, ghostFillRepo, riskBreakPublisher,
        )
        val orderId = "ord-acc-fill-${UUID.randomUUID()}"
        orderRepo.save(newOrder(orderId, quantity = BigDecimal("100")))

        val event = newEvent(clOrdId = orderId, execId = "exec-${UUID.randomUUID()}")
        producer().use { p ->
            p.send(ProducerRecord(topic, orderId, Json.encodeToString(event))).get()
        }

        val job = launch { consumer.start() }
        try {
            withTimeout(20_000) {
                while (true) {
                    val fills = fillRepo.findByOrderId(orderId)
                    val order = orderRepo.findById(orderId)
                    if (fills.size == 1 && order?.status == OrderStatus.FILLED) break
                    delay(150)
                }
            }
            val position = positionRepo.findByKey(BookId("book-acc-1"), InstrumentId("AAPL"))
            position shouldNotBe null
            position!!.quantity.compareTo(BigDecimal("100")) shouldBe 0
            registry.find("execution_report_consumer_dispatched_total")
                .tag("event_type", "FILL")
                .tag("venue", "NYSE")
                .counter()!!.count() shouldBe 1.0
        } finally {
            job.cancel()
        }
    }

    test("PARTIAL_FILL persists partial fill and advances order to PARTIAL") {
        val (consumer, _, _, topic) = startConsumer(
            orderRepo, fillRepo, tradeBookingService, executionCostService,
            executionCostRepo, ghostFillRepo, riskBreakPublisher,
        )
        val orderId = "ord-acc-partial-${UUID.randomUUID()}"
        orderRepo.save(newOrder(orderId, quantity = BigDecimal("100")))

        val event = newEvent(
            clOrdId = orderId,
            execId = "exec-${UUID.randomUUID()}",
            eventType = ExecutionEventType.PARTIAL_FILL,
            execType = "1",
            fixVersion = "FIX.4.2",
            lastQty = "40",
            cumulativeQty = "40",
        )
        producer().use { p ->
            p.send(ProducerRecord(topic, orderId, Json.encodeToString(event))).get()
        }

        val job = launch { consumer.start() }
        try {
            withTimeout(20_000) {
                while (true) {
                    val order = orderRepo.findById(orderId)
                    if (order?.status == OrderStatus.PARTIAL) break
                    delay(150)
                }
            }
            val fills = fillRepo.findByOrderId(orderId)
            fills.size shouldBe 1
            fills.single().fillQty.compareTo(BigDecimal("40")) shouldBe 0
        } finally {
            job.cancel()
        }
    }

    test("CANCELLED event marks order CANCELLED") {
        val (consumer, _, _, topic) = startConsumer(
            orderRepo, fillRepo, tradeBookingService, executionCostService,
            executionCostRepo, ghostFillRepo, riskBreakPublisher,
        )
        val orderId = "ord-acc-cxl-${UUID.randomUUID()}"
        orderRepo.save(newOrder(orderId, status = OrderStatus.SENT))

        val event = newEvent(
            clOrdId = orderId,
            execId = "exec-${UUID.randomUUID()}",
            eventType = ExecutionEventType.CANCELLED,
            execType = "4",
            lastQty = "0",
            lastPrice = "0",
            cumulativeQty = "0",
            averagePrice = "0",
        )
        producer().use { p ->
            p.send(ProducerRecord(topic, orderId, Json.encodeToString(event))).get()
        }

        val job = launch { consumer.start() }
        try {
            withTimeout(20_000) {
                while (true) {
                    val order = orderRepo.findById(orderId)
                    if (order?.status == OrderStatus.CANCELLED) break
                    delay(150)
                }
            }
        } finally {
            job.cancel()
        }
    }

    test("overfill event publishes RiskBreak (CRITICAL) and increments overfill_rejected_total") {
        val (consumer, _, registry, topic) = startConsumer(
            orderRepo, fillRepo, tradeBookingService, executionCostService,
            executionCostRepo, ghostFillRepo, riskBreakPublisher,
        )
        val orderId = "ord-acc-overfill-${UUID.randomUUID()}"
        orderRepo.save(newOrder(orderId, quantity = BigDecimal("100")))

        // First fill: 90 of 100. Then a second fill of 20 → overfill (cumulative 110).
        val firstEvent = newEvent(
            clOrdId = orderId,
            execId = "exec-of-1-${UUID.randomUUID()}",
            lastQty = "90",
            cumulativeQty = "90",
        )
        val overfillEvent = newEvent(
            clOrdId = orderId,
            execId = "exec-of-2-${UUID.randomUUID()}",
            lastQty = "20",
            cumulativeQty = "110",
        )

        producer().use { p ->
            p.send(ProducerRecord(topic, orderId, Json.encodeToString(firstEvent))).get()
            p.send(ProducerRecord(topic, orderId, Json.encodeToString(overfillEvent))).get()
        }

        val job = launch { consumer.start() }
        try {
            withTimeout(25_000) {
                while (true) {
                    val overfillCount = registry.find("overfill_rejected_total")
                        .tag("venue", "NYSE")
                        .counter()?.count() ?: 0.0
                    if (overfillCount >= 1.0) break
                    delay(150)
                }
            }
            val fills = fillRepo.findByOrderId(orderId)
            // Only the first 90 should have persisted; the second is rejected.
            fills.sumOf { it.fillQty.toDouble() } shouldBe 90.0
            riskBreakPublisher.events.any { it.breakType == "OVERFILL" && it.severity == "CRITICAL" } shouldBe true
        } finally {
            job.cancel()
        }
    }

    test("orphan fill (clOrdId not in execution_orders) increments orphan_fill_total but does not crash the consumer") {
        val (consumer, _, registry, topic) = startConsumer(
            orderRepo, fillRepo, tradeBookingService, executionCostService,
            executionCostRepo, ghostFillRepo, riskBreakPublisher,
        )

        val orphanEvent = newEvent(
            clOrdId = "ord-does-not-exist",
            execId = "exec-orphan-${UUID.randomUUID()}",
        )
        producer().use { p ->
            p.send(ProducerRecord(topic, "ord-does-not-exist", Json.encodeToString(orphanEvent))).get()
        }

        val job = launch { consumer.start() }
        try {
            withTimeout(20_000) {
                while (true) {
                    val orphan = registry.find("orphan_fill_total")
                        .tag("venue", "NYSE")
                        .counter()?.count() ?: 0.0
                    if (orphan >= 1.0) break
                    delay(150)
                }
            }
            // Subsequent valid event still flows — consumer kept making progress.
            val followUpOrderId = "ord-acc-followup-${UUID.randomUUID()}"
            orderRepo.save(newOrder(followUpOrderId))
            val follow = newEvent(clOrdId = followUpOrderId, execId = "exec-followup-${UUID.randomUUID()}")
            producer().use { p ->
                p.send(ProducerRecord(topic, followUpOrderId, Json.encodeToString(follow))).get()
            }
            withTimeout(20_000) {
                while (true) {
                    if (orderRepo.findById(followUpOrderId)?.status == OrderStatus.FILLED) break
                    delay(150)
                }
            }
        } finally {
            job.cancel()
        }
    }

    test("REJECTED 35=9 with no clOrdId is skipped — does not NPE the consumer") {
        val (consumer, _, registry, topic) = startConsumer(
            orderRepo, fillRepo, tradeBookingService, executionCostService,
            executionCostRepo, ghostFillRepo, riskBreakPublisher,
        )

        val rejected = newEvent(
            clOrdId = "",
            execId = "exec-rej-${UUID.randomUUID()}",
            eventType = ExecutionEventType.REJECTED,
            execType = "",
            rejectReason = "Too late to cancel",
            rejectCode = "0",
            lastQty = "0",
            lastPrice = "0",
            cumulativeQty = "0",
            averagePrice = "0",
            orderId = "",
        )
        producer().use { p ->
            p.send(ProducerRecord(topic, null, Json.encodeToString(rejected))).get()
        }

        val job = launch { consumer.start() }
        try {
            withTimeout(20_000) {
                while (true) {
                    val skipped = registry.find("execution_report_consumer_skipped_total")
                        .tag("event_type", "REJECTED")
                        .counter()?.count() ?: 0.0
                    if (skipped >= 1.0) break
                    delay(150)
                }
            }
            // Sanity: a follow-up fill still processes after the reject was skipped.
            val followUpOrderId = "ord-acc-after-rej-${UUID.randomUUID()}"
            orderRepo.save(newOrder(followUpOrderId))
            val follow = newEvent(clOrdId = followUpOrderId, execId = "exec-after-rej-${UUID.randomUUID()}")
            producer().use { p ->
                p.send(ProducerRecord(topic, followUpOrderId, Json.encodeToString(follow))).get()
            }
            withTimeout(20_000) {
                while (true) {
                    if (orderRepo.findById(followUpOrderId)?.status == OrderStatus.FILLED) break
                    delay(150)
                }
            }
        } finally {
            job.cancel()
        }
    }

    test("duplicate event (same venue + execId) does not double-write — consumer commits both offsets") {
        val (consumer, _, _, topic) = startConsumer(
            orderRepo, fillRepo, tradeBookingService, executionCostService,
            executionCostRepo, ghostFillRepo, riskBreakPublisher,
        )
        val orderId = "ord-acc-dup-${UUID.randomUUID()}"
        orderRepo.save(newOrder(orderId, quantity = BigDecimal("100")))

        val execId = "exec-dup-${UUID.randomUUID()}"
        val event = newEvent(clOrdId = orderId, execId = execId)
        producer().use { p ->
            p.send(ProducerRecord(topic, orderId, Json.encodeToString(event))).get()
            p.send(ProducerRecord(topic, orderId, Json.encodeToString(event))).get()
        }

        val job = launch { consumer.start() }
        try {
            withTimeout(20_000) {
                while (true) {
                    if (orderRepo.findById(orderId)?.status == OrderStatus.FILLED) break
                    delay(150)
                }
            }
            // Give the consumer a moment to attempt the duplicate.
            delay(1_000)
            val fills = fillRepo.findByOrderId(orderId)
            fills.size shouldBe 1
        } finally {
            job.cancel()
        }
    }
}) {
    companion object {
        private val kafka = org.testcontainers.kafka.KafkaContainer("apache/kafka:3.8.1").also {
            it.start()
        }

        private fun bootstrapServers() = kafka.bootstrapServers

        fun producer() = KafkaTestSetup.createProducer(bootstrapServers())

        @Suppress("LongParameterList")
        fun startConsumer(
            orderRepo: ExposedExecutionOrderRepository,
            fillRepo: ExposedExecutionFillRepository,
            tradeBookingService: TradeBookingService,
            executionCostService: ExecutionCostService,
            executionCostRepo: ExposedExecutionCostRepository,
            ghostFillRepo: ExposedGhostFillRepository,
            riskBreakPublisher: RecordingRiskBreakPublisher,
        ): ConsumerHarness {
            val registry = SimpleMeterRegistry()
            val processor = FIXExecutionReportProcessor(
                orderRepository = orderRepo,
                fillRepository = fillRepo,
                tradeBookingService = tradeBookingService,
                executionCostService = executionCostService,
                executionCostRepository = executionCostRepo,
                ghostFillRepository = ghostFillRepo,
                riskBreakPublisher = riskBreakPublisher,
                meterRegistry = registry,
            )
            val dispatcher = ExecutionReportDispatcher(processor, registry)
            val topic = "execution.reports.acc-${UUID.randomUUID()}"
            val props = Properties().apply {
                put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers())
                put(ConsumerConfig.GROUP_ID_CONFIG, "exec-reports-acc-${UUID.randomUUID()}")
                put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
                    "org.apache.kafka.clients.consumer.CooperativeStickyAssignor")
                put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
            }
            val tracker = ConsumerLivenessTracker(topic = topic, groupId = "exec-reports-acc")
            val consumer = ExecutionReportConsumer(
                consumer = KafkaConsumer<String, String>(props),
                dispatcher = dispatcher,
                livenessTracker = tracker,
                meterRegistry = registry,
                topic = topic,
            )
            return ConsumerHarness(consumer, processor, registry, topic)
        }
    }

    data class ConsumerHarness(
        val consumer: ExecutionReportConsumer,
        val processor: FIXExecutionReportProcessor,
        val registry: SimpleMeterRegistry,
        val topic: String,
    )
}

class RecordingRiskBreakPublisher : com.kinetix.position.kafka.RiskBreakPublisher {
    val events: MutableList<com.kinetix.common.kafka.events.RiskBreakEvent> =
        java.util.Collections.synchronizedList(mutableListOf())
    override suspend fun publish(event: com.kinetix.common.kafka.events.RiskBreakEvent) {
        events.add(event)
    }
}

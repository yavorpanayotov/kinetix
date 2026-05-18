package com.kinetix.position.kafka

import com.kinetix.testsupport.kafka.KafkaTestSetup
import com.kinetix.common.execution.ExecutionEventType
import com.kinetix.common.execution.ExecutionReportEvent
import com.kinetix.common.kafka.ConsumerLivenessTracker
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

/**
 * Phase 3 commit 4 cannot ship until the consumer-side path produces results structurally
 * identical to the legacy in-process processor. This test routes one identical fixture
 * down each path (different `clOrdId`s to avoid the dedup boundary) and asserts the resulting
 * `Position`, `Fill`, and `trade.events` records are structurally identical (deep-equal modulo
 * timestamp + ID fields).
 */
class DualPathParityAcceptanceTest : FunSpec({

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
        tradeEventPublisher = NoOpTradeEventPublisher(),
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

    test("FILL produces structurally identical Position + Fill via Kafka path and direct in-process path") {
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

        val bookId = "book-parity-${UUID.randomUUID()}"
        val instrumentId = "AAPL"

        // Two orders so the fills don't collide on the position update — one routed via
        // each path. The Position aggregates both fills, so we compare per-Fill rather than
        // per-Position.
        val kafkaOrderId = "ord-kafka-${UUID.randomUUID()}"
        val inProcOrderId = "ord-inproc-${UUID.randomUUID()}"
        orderRepo.save(makeOrder(kafkaOrderId, bookId, instrumentId))
        orderRepo.save(makeOrder(inProcOrderId, bookId, instrumentId))

        // ---- Kafka path: publish, start consumer, wait for the Kafka-side fill to land.
        val topic = "execution.reports.parity-${UUID.randomUUID()}"
        val props = Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
            put(ConsumerConfig.GROUP_ID_CONFIG, "exec-reports-parity-${UUID.randomUUID()}")
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
        }
        val tracker = ConsumerLivenessTracker(topic = topic, groupId = "parity")
        val kafkaConsumer = ExecutionReportConsumer(
            consumer = KafkaConsumer<String, String>(props),
            dispatcher = dispatcher,
            livenessTracker = tracker,
            meterRegistry = registry,
            topic = topic,
        )

        val kafkaEvent = makeFillEvent(
            clOrdId = kafkaOrderId,
            execId = "exec-kafka-${UUID.randomUUID()}",
        )
        KafkaTestSetup.createProducer(kafka.bootstrapServers).use { p ->
            p.send(ProducerRecord(topic, kafkaOrderId, Json.encodeToString(kafkaEvent))).get()
        }

        val job = launch { kafkaConsumer.start() }
        try {
            withTimeout(20_000) {
                while (true) {
                    val o = orderRepo.findById(kafkaOrderId)
                    if (o?.status == OrderStatus.FILLED) break
                    delay(150)
                }
            }
        } finally {
            job.cancel()
        }

        // ---- In-process path: drive the processor directly with the equivalent
        // FIXInboundFillEvent (the legacy in-process pipeline's input shape).
        val inProcEvent = com.kinetix.position.fix.FIXInboundFillEvent(
            sessionId = "FIX.4.4:KX->VENUE",
            execId = "exec-inproc-${UUID.randomUUID()}",
            orderId = inProcOrderId,
            execType = "F",
            lastQty = BigDecimal("100"),
            lastPrice = BigDecimal("150.00"),
            cumulativeQty = BigDecimal("100"),
            averagePrice = BigDecimal("150.00"),
            venue = "NYSE",
        )
        processor.process(inProcEvent)

        // ---- Compare the two fills (modulo identifying fields).
        val kafkaFills = fillRepo.findByOrderId(kafkaOrderId)
        val inProcFills = fillRepo.findByOrderId(inProcOrderId)
        kafkaFills.size shouldBe 1
        inProcFills.size shouldBe 1
        val kf = kafkaFills.single()
        val ipf = inProcFills.single()
        kf.bookId shouldBe ipf.bookId
        kf.instrumentId shouldBe ipf.instrumentId
        kf.fillQty.compareTo(ipf.fillQty) shouldBe 0
        kf.fillPrice.compareTo(ipf.fillPrice) shouldBe 0
        kf.fillType shouldBe ipf.fillType
        kf.venue shouldBe ipf.venue
        kf.cumulativeQty.compareTo(ipf.cumulativeQty) shouldBe 0
        kf.averagePrice.compareTo(ipf.averagePrice) shouldBe 0

        // ---- Both orders advanced to FILLED.
        orderRepo.findById(kafkaOrderId)?.status shouldBe OrderStatus.FILLED
        orderRepo.findById(inProcOrderId)?.status shouldBe OrderStatus.FILLED

        // ---- Position aggregated both fills (200 of AAPL @ avg 150.00).
        val position = positionRepo.findByKey(BookId(bookId), InstrumentId(instrumentId))
        position shouldNotBe null
        position!!.quantity.compareTo(BigDecimal("200")) shouldBe 0
        position.averageCost.amount.compareTo(BigDecimal("150.00")) shouldBe 0

        // ---- trade.events table received exactly two trades (one per path).
        val trades = newSuspendedTransaction(db = db) {
            exec("SELECT COUNT(*) FROM trade_events WHERE book_id = '$bookId'") {
                it.next()
                it.getInt(1)
            }
        } ?: 0
        trades shouldBe 2
    }
}) {
    companion object {
        private val kafka = org.testcontainers.kafka.KafkaContainer("apache/kafka:3.8.1").also {
            it.start()
        }

        private fun makeOrder(orderId: String, bookId: String, instrumentId: String) = Order(
            orderId = orderId,
            bookId = bookId,
            instrumentId = instrumentId,
            side = Side.BUY,
            quantity = BigDecimal("100"),
            orderType = "LIMIT",
            limitPrice = BigDecimal("150.00"),
            arrivalPrice = BigDecimal("149.90"),
            submittedAt = Instant.parse("2026-05-07T09:00:00Z"),
            status = OrderStatus.SENT,
            riskCheckResult = "APPROVED",
            riskCheckDetails = null,
            fixSessionId = "FIX.4.4:KX->VENUE",
            instrumentType = "CASH_EQUITY",
        )

        private fun makeFillEvent(clOrdId: String, execId: String) = ExecutionReportEvent(
            eventId = "evt-$execId",
            clOrdId = clOrdId,
            orderId = "venue-tag37-${UUID.randomUUID()}",
            execId = execId,
            sessionId = "FIX.4.4:KX->VENUE",
            venue = "NYSE",
            fixVersion = "FIX.4.4",
            execType = "F",
            eventType = ExecutionEventType.FILL,
            lastQty = "100",
            lastPrice = "150.00",
            cumulativeQty = "100",
            averagePrice = "150.00",
            receivedAt = Instant.now().toString(),
        )
    }
}

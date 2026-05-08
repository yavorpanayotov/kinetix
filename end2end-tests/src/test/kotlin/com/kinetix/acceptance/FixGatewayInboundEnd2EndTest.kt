package com.kinetix.acceptance

import com.kinetix.common.kafka.ConsumerLivenessTracker
import com.kinetix.common.kafka.events.TradeEventMessage
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Side
import com.kinetix.fix.kafka.ExecutionReportProducerFactory
import com.kinetix.fix.kafka.KafkaExecutionReportPublisher
import com.kinetix.fix.session.FIXMessageConverter
import com.kinetix.fix.session.InboundFixHandler
import com.kinetix.position.fix.ExecutionCostService
import com.kinetix.position.fix.ExposedExecutionCostRepository
import com.kinetix.position.fix.ExposedExecutionFillRepository
import com.kinetix.position.fix.ExposedExecutionOrderRepository
import com.kinetix.position.fix.ExposedGhostFillRepository
import com.kinetix.position.fix.FIXExecutionReportProcessor
import com.kinetix.position.fix.Order
import com.kinetix.position.fix.OrderStatus
import com.kinetix.position.fix.TimeInForce
import com.kinetix.position.kafka.ExecutionReportConsumer
import com.kinetix.position.kafka.ExecutionReportDispatcher
import com.kinetix.position.kafka.KafkaTradeEventPublisher
import com.kinetix.position.kafka.NoOpRiskBreakPublisher
import com.kinetix.position.persistence.DatabaseConfig
import com.kinetix.position.persistence.DatabaseFactory
import com.kinetix.position.persistence.ExposedPositionRepository
import com.kinetix.position.persistence.ExposedTradeEventRepository
import com.kinetix.position.service.ExposedTransactionalRunner
import com.kinetix.position.service.TradeBookingService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.kafka.KafkaContainer
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.UUID

/**
 * End-to-end inbound FIX path (ADR-0035 / fix-gateway plan §3.12).
 *
 * Wires the two services together over real Kafka:
 *
 *   raw FIX 35=8                  fix-gateway                    position-service
 *   ──────────────►  InboundFixHandler ──► execution.reports ──► ExecutionReportConsumer
 *                            │                                          │
 *                            ▼                                          ▼
 *                    KafkaExecutionReportPublisher              FIXExecutionReportProcessor
 *                                                                       │
 *                                                                       ▼
 *                                                          fills + position + trades.lifecycle
 *
 * The acceptor side is simulated by feeding crafted FIX bytes directly into
 * [InboundFixHandler] — the wire-format contract is identical to what
 * QuickFIX/J's `Application.fromApp` callback delivers, and the
 * `InboundExecutionReportAcceptanceTest` in fix-gateway pins that boundary
 * separately. This test's job is to prove the *cross-service* contract holds
 * end-to-end: a venue fill produces (a) an execution_fills row, (b) a position
 * update, AND (c) a TradeEventMessage on `trades.lifecycle` so downstream audit,
 * risk, and analytics consumers see the trade.
 */
class FixGatewayInboundEnd2EndTest : FunSpec({

    val postgres = PostgreSQLContainer("postgres:17-alpine")
        .withDatabaseName("position_e2e")
        .withUsername("test")
        .withPassword("test")
    val kafka = KafkaContainer("apache/kafka:3.8.1")

    lateinit var orderRepo: ExposedExecutionOrderRepository
    lateinit var fillRepo: ExposedExecutionFillRepository
    lateinit var positionRepo: ExposedPositionRepository
    lateinit var inboundHandler: InboundFixHandler
    lateinit var tradesLifecycleTopic: String
    lateinit var executionReportsTopic: String
    var consumerJob: Job? = null

    beforeSpec {
        postgres.start()
        kafka.start()

        // --- position-service infra ---
        val db = DatabaseFactory.init(
            DatabaseConfig(
                jdbcUrl = postgres.jdbcUrl,
                username = postgres.username,
                password = postgres.password,
                maxPoolSize = 5,
            )
        )
        orderRepo = ExposedExecutionOrderRepository(db)
        fillRepo = ExposedExecutionFillRepository(db)
        positionRepo = ExposedPositionRepository(db)
        val tradeEventRepo = ExposedTradeEventRepository(db)
        val transactional = ExposedTransactionalRunner(db)

        // Topics — randomized per run so reruns don't see leftover offsets.
        executionReportsTopic = "execution.reports.e2e-${UUID.randomUUID()}"
        tradesLifecycleTopic = "trades.lifecycle.e2e-${UUID.randomUUID()}"

        // Trade event publisher (position-service → trades.lifecycle).
        val tradeProducerProps = Properties().apply {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.ACKS_CONFIG, "all")
        }
        val tradeProducer = KafkaProducer<String, String>(tradeProducerProps)
        val tradeEventPublisher = KafkaTradeEventPublisher(tradeProducer, topic = tradesLifecycleTopic)

        val tradeBookingService = TradeBookingService(
            tradeEventRepository = tradeEventRepo,
            positionRepository = positionRepo,
            transactional = transactional,
            tradeEventPublisher = tradeEventPublisher,
            limitCheckService = null,
        )
        val executionCostService = ExecutionCostService()
        val executionCostRepo = ExposedExecutionCostRepository(db)
        val ghostFillRepo = ExposedGhostFillRepository(db)

        val processorRegistry = SimpleMeterRegistry()
        val processor = FIXExecutionReportProcessor(
            orderRepository = orderRepo,
            fillRepository = fillRepo,
            tradeBookingService = tradeBookingService,
            executionCostService = executionCostService,
            executionCostRepository = executionCostRepo,
            ghostFillRepository = ghostFillRepo,
            riskBreakPublisher = NoOpRiskBreakPublisher(),
            meterRegistry = processorRegistry,
        )
        val dispatcher = ExecutionReportDispatcher(processor, processorRegistry)

        // Position-service consumer of execution.reports.
        val consumerProps = Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
            put(ConsumerConfig.GROUP_ID_CONFIG, "exec-reports-e2e-${UUID.randomUUID()}")
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            put(
                ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
                "org.apache.kafka.clients.consumer.CooperativeStickyAssignor",
            )
            put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
        }
        val livenessTracker = ConsumerLivenessTracker(topic = executionReportsTopic, groupId = "exec-reports-e2e")
        val executionReportConsumer = ExecutionReportConsumer(
            consumer = KafkaConsumer<String, String>(consumerProps),
            dispatcher = dispatcher,
            livenessTracker = livenessTracker,
            meterRegistry = processorRegistry,
            topic = executionReportsTopic,
        )

        // --- fix-gateway infra (publishing side) ---
        val fixProducer = ExecutionReportProducerFactory.idempotent(kafka.bootstrapServers)
        val publisher = KafkaExecutionReportPublisher(fixProducer, topic = executionReportsTopic)
        val handlerRegistry = SimpleMeterRegistry()
        inboundHandler = InboundFixHandler(
            converter = FIXMessageConverter(),
            publisher = publisher,
            meterRegistry = handlerRegistry,
            clock = { Instant.parse("2026-05-07T10:00:00Z") },
        )

        consumerJob = CoroutineScope(Dispatchers.Default).launch { executionReportConsumer.start() }
    }

    afterSpec {
        consumerJob?.cancel()
        postgres.stop()
        kafka.stop()
    }

    test("35=8 Fill flows raw FIX → fix-gateway → Kafka → position-service: fill persisted, position updated, trade event published") {
        val orderId = "ord-e2e-fill-${UUID.randomUUID()}"
        val bookId = "book-e2e-${UUID.randomUUID()}"
        seedOrder(orderRepo, orderId = orderId, bookId = bookId, instrumentId = "AAPL", quantity = "100")

        // The acceptor side: a FIX 35=8 Fill referencing the seeded clOrdID.
        val execId = "exec-e2e-${UUID.randomUUID()}"
        val rawFix = fix(
            "35" to "8",
            "11" to orderId,           // ClOrdID — must match the seeded order
            "17" to execId,            // ExecID — partition-key + dedup
            "37" to "venue-37-$orderId",
            "150" to "F",              // ExecType = Fill
            "32" to "100",             // LastQty
            "31" to "150.25",          // LastPx
            "14" to "100",             // CumQty
            "6" to "150.25",           // AvgPx
            "30" to "NYSE",            // LastMkt
            "38" to "100",             // OrderQty
        )

        inboundHandler.handle(rawFix, sessionId = "FIX.4.4:KX->NYSE", fixVersion = "FIX.4.4")

        // (a) The fill must persist.
        withTimeout(20_000) {
            while (fillRepo.findByOrderId(orderId).isEmpty()) {
                delay(150)
            }
        }
        val fills = fillRepo.findByOrderId(orderId)
        fills.size shouldBe 1
        fills.single().fillQty.compareTo(BigDecimal("100")) shouldBe 0
        fills.single().fillPrice.compareTo(BigDecimal("150.25")) shouldBe 0

        // (b) The order advances to FILLED.
        withTimeout(20_000) {
            while (orderRepo.findById(orderId)?.status != OrderStatus.FILLED) {
                delay(150)
            }
        }

        // (c) The position is materialised with the correct quantity and average cost.
        val position = positionRepo.findByKey(BookId(bookId), InstrumentId("AAPL"))
        position shouldNotBe null
        position!!.quantity.compareTo(BigDecimal("100")) shouldBe 0
        position.averageCost.amount.compareTo(BigDecimal("150.25")) shouldBe 0

        // (d) The trade event lands on trades.lifecycle for downstream audit/risk consumers.
        val tradeEvents = consumeTradeEvents(
            kafka.bootstrapServers,
            tradesLifecycleTopic,
            expected = 1,
        )
        tradeEvents.size shouldBe 1
        val tradeEvent = tradeEvents.single()
        tradeEvent.bookId shouldBe bookId
        tradeEvent.instrumentId shouldBe "AAPL"
        tradeEvent.side shouldBe "BUY"
        tradeEvent.quantity shouldBe "100"
        tradeEvent.priceAmount shouldBe "150.25"
        tradeEvent.priceCurrency shouldBe "USD"
        tradeEvent.assetClass shouldBe "EQUITY"
    }
})

private suspend fun seedOrder(
    repo: ExposedExecutionOrderRepository,
    orderId: String,
    bookId: String,
    instrumentId: String,
    quantity: String,
) {
    repo.save(
        Order(
            orderId = orderId,
            bookId = bookId,
            instrumentId = instrumentId,
            side = Side.BUY,
            quantity = BigDecimal(quantity),
            orderType = "LIMIT",
            limitPrice = BigDecimal("150.00"),
            arrivalPrice = BigDecimal("149.90"),
            submittedAt = Instant.parse("2026-05-07T09:30:00Z"),
            status = OrderStatus.SENT,
            riskCheckResult = "APPROVED",
            riskCheckDetails = null,
            fixSessionId = "FIX.4.4:KX->NYSE",
            timeInForce = TimeInForce.DAY,
            instrumentType = "CASH_EQUITY",
        )
    )
}

private fun consumeTradeEvents(
    bootstrapServers: String,
    topic: String,
    expected: Int,
    timeoutSec: Long = 20,
): List<TradeEventMessage> {
    val props = Properties().apply {
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ConsumerConfig.GROUP_ID_CONFIG, "trades-lifecycle-assertion-${System.nanoTime()}")
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    }
    val out = mutableListOf<TradeEventMessage>()
    KafkaConsumer<String, String>(props).use { consumer ->
        consumer.subscribe(listOf(topic))
        val deadline = System.nanoTime() + Duration.ofSeconds(timeoutSec).toNanos()
        while (out.size < expected && System.nanoTime() < deadline) {
            val records = consumer.poll(Duration.ofMillis(500))
            for (record in records) {
                out += Json.decodeFromString<TradeEventMessage>(record.value())
            }
        }
    }
    return out
}

private fun fix(vararg pairs: Pair<String, String>): String =
    pairs.joinToString("|") { (tag, value) -> "$tag=$value" }

package com.kinetix.acceptance

import com.kinetix.audit.kafka.AuditEventConsumer
import com.kinetix.audit.persistence.ExposedAuditEventRepository
import com.kinetix.common.model.*
import com.kinetix.testsupport.containers.TestcontainerCaps
import com.kinetix.position.kafka.KafkaTradeEventPublisher
import com.kinetix.position.persistence.ExposedPositionRepository
import com.kinetix.position.persistence.ExposedTradeEventRepository
import com.kinetix.position.service.*
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency
import java.util.Properties

class TradeBookingEnd2EndTest : BehaviorSpec({

    // --- Infrastructure ---

    val positionDb = TestcontainerCaps.tunePostgres(
        PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("position_test")
            .withUsername("test")
            .withPassword("test"),
    )

    val auditDb = TestcontainerCaps.tunePostgres(
        PostgreSQLContainer(
            DockerImageName.parse("timescale/timescaledb:latest-pg17")
                .asCompatibleSubstituteFor("postgres"),
        )
            .withDatabaseName("audit_test")
            .withUsername("test")
            .withPassword("test"),
        withTimescale = true,
    )

    val kafka = TestcontainerCaps.tuneKafka(
        org.testcontainers.kafka.KafkaContainer("apache/kafka:3.8.1"),
    )

    // --- Services (initialized in beforeSpec) ---

    lateinit var bookingService: TradeBookingService
    lateinit var queryService: PositionQueryService
    lateinit var auditRepository: ExposedAuditEventRepository
    var consumerJob: Job? = null

    beforeSpec {
        // Start infrastructure
        positionDb.start()
        auditDb.start()
        kafka.start()

        // Wire position-service
        val positionDatabase = com.kinetix.position.persistence.DatabaseFactory.init(
            com.kinetix.position.persistence.DatabaseConfig(
                jdbcUrl = positionDb.jdbcUrl,
                username = positionDb.username,
                password = positionDb.password,
                maxPoolSize = 5,
            )
        )
        val tradeEventRepo = ExposedTradeEventRepository(positionDatabase)
        val positionRepo = ExposedPositionRepository(positionDatabase)
        val transactional = ExposedTransactionalRunner(positionDatabase)

        val producerProps = Properties().apply {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.ACKS_CONFIG, "all")
        }
        val kafkaProducer = KafkaProducer<String, String>(producerProps)
        val publisher = KafkaTradeEventPublisher(kafkaProducer)

        bookingService = TradeBookingService(tradeEventRepo, positionRepo, transactional, publisher)
        queryService = PositionQueryService(positionRepo)

        // Wire audit-service
        val auditDatabase = com.kinetix.audit.persistence.DatabaseFactory.init(
            com.kinetix.audit.persistence.DatabaseConfig(
                jdbcUrl = auditDb.jdbcUrl,
                username = auditDb.username,
                password = auditDb.password,
                maxPoolSize = 5,
            )
        )
        auditRepository = ExposedAuditEventRepository(auditDatabase)

        val consumerProps = Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
            put(ConsumerConfig.GROUP_ID_CONFIG, "audit-acceptance-test")
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        }
        val kafkaConsumer = KafkaConsumer<String, String>(consumerProps)
        val auditConsumer = AuditEventConsumer(kafkaConsumer, auditRepository)

        consumerJob = CoroutineScope(Dispatchers.Default).launch { auditConsumer.start() }
    }

    afterSpec {
        consumerJob?.cancel()
        positionDb.stop()
        auditDb.stop()
        kafka.stop()
    }

    given("an empty portfolio") {
        `when`("a BUY trade for 100 AAPL @ 150 USD is booked") {
            val command = BookTradeCommand(
                tradeId = TradeId("t-accept-1"),
                bookId = BookId("port-accept-1"),
                instrumentId = InstrumentId("AAPL"),
                assetClass = AssetClass.EQUITY,
                side = Side.BUY,
                quantity = BigDecimal("100"),
                price = Money(BigDecimal("150.00"), Currency.getInstance("USD")),
                tradedAt = Instant.parse("2025-01-15T10:00:00Z"),
                instrumentType = "CASH_EQUITY",
                traderId = TraderId("tr-test-001"),
            )

            val result = bookingService.handle(command)

            then("the trade is persisted with correct details") {
                result.trade.tradeId shouldBe TradeId("t-accept-1")
                result.trade.side shouldBe Side.BUY
                result.trade.quantity.compareTo(BigDecimal("100")) shouldBe 0
            }

            then("the position exists with quantity 100 and average cost 150") {
                val positions = queryService.handle(
                    GetPositionsQuery(BookId("port-accept-1"))
                )
                positions.size shouldBe 1
                positions[0].instrumentId shouldBe InstrumentId("AAPL")
                positions[0].quantity.compareTo(BigDecimal("100")) shouldBe 0
                positions[0].averageCost.amount.compareTo(BigDecimal("150.00")) shouldBe 0
            }

            then("an audit event is recorded with matching trade details") {
                withTimeout(10_000) {
                    while (auditRepository.findByBookId("port-accept-1").isEmpty()) {
                        delay(100)
                    }
                }
                val auditEvents = auditRepository.findByBookId("port-accept-1")
                auditEvents.size shouldBe 1
                auditEvents[0].tradeId shouldBe "t-accept-1"
                auditEvents[0].bookId shouldBe "port-accept-1"
                auditEvents[0].instrumentId shouldBe "AAPL"
                auditEvents[0].assetClass shouldBe "EQUITY"
                auditEvents[0].side shouldBe "BUY"
                auditEvents[0].quantity shouldBe "100"
                auditEvents[0].priceAmount shouldBe "150"
                auditEvents[0].priceCurrency shouldBe "USD"
            }
        }
    }
})

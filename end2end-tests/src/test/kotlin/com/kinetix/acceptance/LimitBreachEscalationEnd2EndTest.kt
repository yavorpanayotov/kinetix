package com.kinetix.acceptance

import com.kinetix.common.kafka.events.LimitBreachEvent
import com.kinetix.common.model.*
import com.kinetix.notification.engine.LimitBreachRule
import com.kinetix.notification.kafka.LimitBreachEventConsumer
import com.kinetix.notification.model.AlertType
import com.kinetix.notification.model.Severity
import com.kinetix.notification.persistence.InMemoryAlertEventRepository
import com.kinetix.position.kafka.KafkaLimitBreachEventPublisher
import com.kinetix.position.kafka.KafkaTradeEventPublisher
import com.kinetix.position.model.LimitDefinition
import com.kinetix.position.model.LimitLevel
import com.kinetix.position.model.LimitType
import com.kinetix.position.persistence.ExposedLimitDefinitionRepository
import com.kinetix.position.persistence.ExposedPositionRepository
import com.kinetix.position.persistence.ExposedTemporaryLimitIncreaseRepository
import com.kinetix.position.persistence.ExposedTradeEventRepository
import com.kinetix.position.service.BookTradeCommand
import com.kinetix.position.service.ExposedTransactionalRunner
import com.kinetix.position.service.HierarchyBasedPreTradeCheckService
import com.kinetix.position.service.LimitBreachException
import com.kinetix.position.service.LimitHierarchyService
import com.kinetix.position.service.TradeBookingService
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
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency
import java.util.Properties
import java.util.UUID

class LimitBreachEscalationEnd2EndTest : BehaviorSpec({

    // --- Infrastructure ---
    val positionDb = PostgreSQLContainer("postgres:17-alpine")
        .withDatabaseName("position_test")
        .withUsername("test")
        .withPassword("test")

    val kafka = org.testcontainers.kafka.KafkaContainer("apache/kafka:3.8.1")

    lateinit var bookingService: TradeBookingService
    lateinit var alertRepo: InMemoryAlertEventRepository
    var consumerJob: Job? = null

    val topic = "limits.breaches.e2e"

    beforeSpec {
        positionDb.start()
        kafka.start()

        val database = com.kinetix.position.persistence.DatabaseFactory.init(
            com.kinetix.position.persistence.DatabaseConfig(
                jdbcUrl = positionDb.jdbcUrl,
                username = positionDb.username,
                password = positionDb.password,
                maxPoolSize = 5,
            )
        )
        val tradeEventRepo = ExposedTradeEventRepository(database)
        val positionRepo = ExposedPositionRepository(database)
        val limitDefinitionRepo = ExposedLimitDefinitionRepository(database)
        val temporaryLimitIncreaseRepo = ExposedTemporaryLimitIncreaseRepository(database)
        val transactional = ExposedTransactionalRunner(database)
        val limitHierarchyService = LimitHierarchyService(limitDefinitionRepo, temporaryLimitIncreaseRepo)
        val preTradeCheck = HierarchyBasedPreTradeCheckService(positionRepo, limitHierarchyService)

        // Seed a HARD notional limit: $200K on book port-e2e-1
        limitDefinitionRepo.save(
            LimitDefinition(
                id = UUID.randomUUID().toString(),
                level = LimitLevel.BOOK,
                entityId = "port-e2e-1",
                limitType = LimitType.NOTIONAL,
                limitValue = BigDecimal("200000"),
                intradayLimit = null,
                overnightLimit = null,
                active = true,
            ),
        )

        val producerProps = Properties().apply {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.ACKS_CONFIG, "all")
        }
        val tradeProducer = KafkaProducer<String, String>(producerProps)
        val breachProducer = KafkaProducer<String, String>(producerProps)
        val tradeEventPublisher = KafkaTradeEventPublisher(tradeProducer)
        val breachPublisher = KafkaLimitBreachEventPublisher(breachProducer, topic)

        bookingService = TradeBookingService(
            tradeEventRepo,
            positionRepo,
            transactional,
            tradeEventPublisher,
            preTradeCheck,
            limitBreachEventPublisher = breachPublisher,
        )

        alertRepo = InMemoryAlertEventRepository()

        val consumerProps = Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
            put(ConsumerConfig.GROUP_ID_CONFIG, "limit-breach-e2e-group")
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        }
        val kafkaConsumer = KafkaConsumer<String, String>(consumerProps)
        val limitBreachConsumer = LimitBreachEventConsumer(
            consumer = kafkaConsumer,
            rule = LimitBreachRule(),
            eventRepository = alertRepo,
            topic = topic,
        )

        consumerJob = CoroutineScope(Dispatchers.Default).launch { limitBreachConsumer.start() }
    }

    afterSpec {
        consumerJob?.cancel()
        positionDb.stop()
        kafka.stop()
    }

    given("a HARD NOTIONAL limit of \$200,000 on book port-e2e-1") {
        `when`("a trade with \$300,000 notional is booked (3000 shares at \$100)") {
            then("the booking fails with LimitBreachException AND a CRITICAL LIMIT_BREACH alert is created in notification-service") {
                var thrown: LimitBreachException? = null
                try {
                    bookingService.handle(
                        BookTradeCommand(
                            tradeId = TradeId("t-e2e-1"),
                            bookId = BookId("port-e2e-1"),
                            instrumentId = InstrumentId("AAPL"),
                            assetClass = AssetClass.EQUITY,
                            side = Side.BUY,
                            quantity = BigDecimal("3000"),
                            price = Money(BigDecimal("100.00"), Currency.getInstance("USD")),
                            tradedAt = Instant.parse("2026-04-29T10:00:00Z"),
                            instrumentType = "CASH_EQUITY",
                        ),
                    )
                } catch (e: LimitBreachException) {
                    thrown = e
                }

                // Synchronous semantic preserved
                (thrown is LimitBreachException) shouldBe true

                // Wait for the consumer to pick the event up and persist the alert.
                withTimeout(15_000) {
                    while (alertRepo.findRecent(10).isEmpty()) {
                        delay(200)
                    }
                }

                val alerts = alertRepo.findRecent(10)
                alerts.size shouldBe 1
                alerts[0].type shouldBe AlertType.LIMIT_BREACH
                alerts[0].severity shouldBe Severity.CRITICAL
                alerts[0].bookId shouldBe "port-e2e-1"
                alerts[0].ruleId shouldBe "LIMIT_BREACH"
                alerts[0].message.isNotBlank() shouldBe true
            }
        }
    }
})

// Bridges the public LimitBreachEvent type so unused-import doesn't trip compileTestKotlin.
@Suppress("unused")
private val unusedTypeAnchor: LimitBreachEvent? = null

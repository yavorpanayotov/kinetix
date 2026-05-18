package com.kinetix.acceptance

import com.kinetix.common.model.*
import com.kinetix.position.kafka.PriceConsumer
import com.kinetix.position.kafka.KafkaTradeEventPublisher
import com.kinetix.position.persistence.ExposedPositionRepository
import com.kinetix.position.persistence.ExposedTradeEventRepository
import com.kinetix.position.service.*
import com.kinetix.testsupport.containers.TestcontainerCaps
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.testcontainers.containers.PostgreSQLContainer
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency
import java.util.Properties

class PricePnlEnd2EndTest : BehaviorSpec({

    // --- Infrastructure ---

    val positionDb = TestcontainerCaps.tunePostgres(
        PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("position_test")
            .withUsername("test")
            .withPassword("test"),
    )

    val kafka = TestcontainerCaps.tuneKafka(
        org.testcontainers.kafka.KafkaContainer("apache/kafka:3.8.1"),
    )

    // --- Services (initialized in beforeSpec) ---

    lateinit var bookingService: TradeBookingService
    lateinit var queryService: PositionQueryService
    lateinit var kafkaProducer: KafkaProducer<String, String>
    var consumerJob: Job? = null

    beforeSpec {
        // Start infrastructure
        positionDb.start()
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
        kafkaProducer = KafkaProducer(producerProps)
        val publisher = KafkaTradeEventPublisher(kafkaProducer)

        bookingService = TradeBookingService(tradeEventRepo, positionRepo, transactional, publisher)
        queryService = PositionQueryService(positionRepo)

        // Wire price consumer
        val priceUpdateService = PriceUpdateService(positionRepo)
        val consumerProps = Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
            put(ConsumerConfig.GROUP_ID_CONFIG, "price-acceptance-test")
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        }
        val kafkaConsumer = KafkaConsumer<String, String>(consumerProps)
        val priceConsumer = PriceConsumer(kafkaConsumer, priceUpdateService)

        consumerJob = CoroutineScope(Dispatchers.Default).launch { priceConsumer.start() }
    }

    afterSpec {
        consumerJob?.cancel()
        positionDb.stop()
        kafka.stop()
    }

    given("a portfolio with 100 AAPL bought at 150 USD") {
        val command = BookTradeCommand(
            tradeId = TradeId("t-mkt-1"),
            bookId = BookId("port-mkt-1"),
            instrumentId = InstrumentId("AAPL"),
            assetClass = AssetClass.EQUITY,
            side = Side.BUY,
            quantity = BigDecimal("100"),
            price = Money(BigDecimal("150.00"), Currency.getInstance("USD")),
            tradedAt = Instant.parse("2025-01-15T10:00:00Z"),
            instrumentType = "CASH_EQUITY",
            traderId = TraderId("tr-test-001"),
        )

        bookingService.handle(command)

        `when`("a price update for AAPL at 170 USD is published to Kafka") {
            val priceJson = """
                {
                    "instrumentId": "AAPL",
                    "priceAmount": "170.00",
                    "priceCurrency": "USD",
                    "timestamp": "2025-01-15T11:00:00Z",
                    "source": "BLOOMBERG"
                }
            """.trimIndent()

            kafkaProducer.send(
                ProducerRecord("price.updates", "AAPL", priceJson)
            ).get()

            then("the position market price is updated to 170 within 2 seconds") {
                lateinit var positions: List<Position>
                withTimeout(2_000) {
                    while (true) {
                        positions = queryService.handle(
                            GetPositionsQuery(BookId("port-mkt-1"))
                        )
                        if (positions.isNotEmpty() &&
                            positions[0].marketPrice.amount.compareTo(BigDecimal("170.00")) == 0
                        ) break
                        delay(100)
                    }
                }
                positions[0].marketPrice.amount.compareTo(BigDecimal("170.00")) shouldBe 0
            }

            then("the market value is recalculated as quantity * new price") {
                val positions = queryService.handle(
                    GetPositionsQuery(BookId("port-mkt-1"))
                )
                // 100 * 170 = 17000.00
                positions[0].marketValue.amount.compareTo(BigDecimal("17000.00")) shouldBe 0
            }

            then("the unrealized P&L reflects the price change") {
                val positions = queryService.handle(
                    GetPositionsQuery(BookId("port-mkt-1"))
                )
                // (170 - 150) * 100 = 2000.00
                positions[0].unrealizedPnl.amount.compareTo(BigDecimal("2000.00")) shouldBe 0
            }

            then("the average cost remains unchanged") {
                val positions = queryService.handle(
                    GetPositionsQuery(BookId("port-mkt-1"))
                )
                positions[0].averageCost.amount.compareTo(BigDecimal("150.00")) shouldBe 0
            }
        }
    }
})

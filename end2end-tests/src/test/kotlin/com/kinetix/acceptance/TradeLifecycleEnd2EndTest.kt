package com.kinetix.acceptance

import com.kinetix.audit.kafka.AuditEventConsumer
import com.kinetix.audit.persistence.AuditHasher
import com.kinetix.audit.persistence.ExposedAuditEventRepository
import com.kinetix.common.model.*
import com.kinetix.position.kafka.KafkaTradeEventPublisher
import com.kinetix.position.model.LimitBreachSeverity
import com.kinetix.position.model.LimitDefinition
import com.kinetix.position.model.LimitLevel
import com.kinetix.position.model.LimitType
import com.kinetix.position.persistence.ExposedLimitDefinitionRepository
import com.kinetix.position.persistence.ExposedPositionRepository
import com.kinetix.position.persistence.ExposedTemporaryLimitIncreaseRepository
import com.kinetix.position.persistence.ExposedTradeEventRepository
import com.kinetix.position.service.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
import java.math.RoundingMode
import java.time.Instant
import java.util.Currency
import java.util.Properties

class TradeLifecycleEnd2EndTest : BehaviorSpec({

    // --- Infrastructure ---

    val positionDb = PostgreSQLContainer("postgres:17-alpine")
        .withDatabaseName("position_lifecycle_test")
        .withUsername("test")
        .withPassword("test")

    val auditDb = PostgreSQLContainer(
        DockerImageName.parse("timescale/timescaledb:latest-pg17")
            .asCompatibleSubstituteFor("postgres")
    )
        .withDatabaseName("audit_lifecycle_test")
        .withUsername("test")
        .withPassword("test")

    val kafka = org.testcontainers.kafka.KafkaContainer("apache/kafka:3.8.1")

    // --- Services (initialized in beforeSpec) ---

    lateinit var bookingService: TradeBookingService
    lateinit var bookingServiceWithLimits: TradeBookingService
    lateinit var lifecycleService: TradeLifecycleService
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
        lifecycleService = TradeLifecycleService(tradeEventRepo, positionRepo, transactional, publisher)
        queryService = PositionQueryService(positionRepo)

        val limitDefinitionRepo = ExposedLimitDefinitionRepository(positionDatabase)
        val temporaryLimitIncreaseRepo = ExposedTemporaryLimitIncreaseRepository(positionDatabase)
        val limitHierarchyService = LimitHierarchyService(
            limitDefinitionRepo = limitDefinitionRepo,
            temporaryLimitIncreaseRepo = temporaryLimitIncreaseRepo,
            referenceDataClient = null,
            warningThresholdPct = 0.8,
        )
        val preTradeCheckService = HierarchyBasedPreTradeCheckService(positionRepo, limitHierarchyService)

        // Seed a POSITION limit of 1000 shares at BOOK level for the limit-test books
        for (bookId in listOf("port-lifecycle-6", "port-lifecycle-7")) {
            limitDefinitionRepo.save(
                LimitDefinition(
                    id = "e2e-limit-position-$bookId",
                    level = LimitLevel.BOOK,
                    entityId = bookId,
                    limitType = LimitType.POSITION,
                    limitValue = BigDecimal("1000"),
                    intradayLimit = null,
                    overnightLimit = null,
                    active = true,
                )
            )
        }

        bookingServiceWithLimits = TradeBookingService(
            tradeEventRepo, positionRepo, transactional, publisher, preTradeCheckService
        )

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
            put(ConsumerConfig.GROUP_ID_CONFIG, "audit-lifecycle-test")
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

    // --- Test 23: amend trade updates position and produces audit event with hash chain ---

    given("a LIVE BUY trade of 100 AAPL @ 150") {
        `when`("the trade is amended to 200 shares @ 160") {
            val bookId ="port-lifecycle-1"
            val originalTradeId = "t-lifecycle-1-original"
            val amendTradeId = "t-lifecycle-1-amend"

            bookingService.handle(
                BookTradeCommand(
                    tradeId = TradeId(originalTradeId),
                    bookId = BookId(bookId),
                    instrumentId = InstrumentId("AAPL"),
                    assetClass = AssetClass.EQUITY,
                    side = Side.BUY,
                    quantity = BigDecimal("100"),
                    price = Money(BigDecimal("150.00"), Currency.getInstance("USD")),
                    tradedAt = Instant.parse("2025-01-15T10:00:00Z"),
                    instrumentType = "CASH_EQUITY",
                    traderId = TraderId("tr-test-001"),
                )
            )

            val amendResult = lifecycleService.handleAmend(
                AmendTradeCommand(
                    originalTradeId = TradeId(originalTradeId),
                    newTradeId = TradeId(amendTradeId),
                    bookId = BookId(bookId),
                    instrumentId = InstrumentId("AAPL"),
                    assetClass = AssetClass.EQUITY,
                    side = Side.BUY,
                    quantity = BigDecimal("200"),
                    price = Money(BigDecimal("160.00"), Currency.getInstance("USD")),
                    tradedAt = Instant.parse("2025-01-15T11:00:00Z"),
                    instrumentType = "CASH_EQUITY",
                )
            )

            then("amend trade is LIVE and original trade is AMENDED") {
                amendResult.trade.tradeId shouldBe TradeId(amendTradeId)
                amendResult.trade.status shouldBe TradeStatus.LIVE
                amendResult.trade.eventType shouldBe TradeEventType.AMEND
                amendResult.trade.originalTradeId shouldBe TradeId(originalTradeId)
            }

            then("position reflects the amended quantity and price") {
                val positions = queryService.handle(GetPositionsQuery(BookId(bookId)))
                positions shouldHaveSize 1
                positions[0].quantity.compareTo(BigDecimal("200")) shouldBe 0
                positions[0].averageCost.amount.compareTo(BigDecimal("160.00")) shouldBe 0
            }

            then("two audit events are produced with a valid hash chain") {
                withTimeout(10_000) {
                    while (auditRepository.findByBookId(bookId).size < 2) {
                        delay(100)
                    }
                }
                val auditEvents = auditRepository.findByBookId(bookId)
                auditEvents shouldHaveSize 2
                val chainResult = AuditHasher.verifyChain(auditEvents)
                chainResult.valid shouldBe true
                auditEvents[1].previousHash shouldNotBe null
                auditEvents[1].previousHash shouldBe auditEvents[0].recordHash
            }
        }
    }

    // --- Test 24: cancel trade reverses position and produces audit event ---

    given("a LIVE BUY trade of 100 AAPL @ 150 (to be cancelled)") {
        `when`("the trade is cancelled") {
            val bookId ="port-lifecycle-2"
            val tradeId = "t-lifecycle-2"

            bookingService.handle(
                BookTradeCommand(
                    tradeId = TradeId(tradeId),
                    bookId = BookId(bookId),
                    instrumentId = InstrumentId("AAPL"),
                    assetClass = AssetClass.EQUITY,
                    side = Side.BUY,
                    quantity = BigDecimal("100"),
                    price = Money(BigDecimal("150.00"), Currency.getInstance("USD")),
                    tradedAt = Instant.parse("2025-01-15T10:00:00Z"),
                    instrumentType = "CASH_EQUITY",
                    traderId = TraderId("tr-test-001"),
                )
            )

            val cancelResult = lifecycleService.handleCancel(
                CancelTradeCommand(
                    tradeId = TradeId(tradeId),
                    bookId = BookId(bookId),
                )
            )

            then("the trade status is CANCELLED") {
                cancelResult.trade.status shouldBe TradeStatus.CANCELLED
            }

            then("the position quantity is zero") {
                val positions = queryService.handle(GetPositionsQuery(BookId(bookId)))
                positions shouldHaveSize 1
                positions[0].quantity.compareTo(BigDecimal.ZERO) shouldBe 0
            }

            then("two audit events are recorded (book + cancel)") {
                withTimeout(10_000) {
                    while (auditRepository.findByBookId(bookId).size < 2) {
                        delay(100)
                    }
                }
                val auditEvents = auditRepository.findByBookId(bookId)
                auditEvents shouldHaveSize 2
            }
        }
    }

    // --- Test 25: SELL trade reduces position and computes realized P&L ---

    given("a position of 100 AAPL bought @ 150") {
        `when`("30 shares are sold @ 160") {
            val bookId ="port-lifecycle-3"

            bookingService.handle(
                BookTradeCommand(
                    tradeId = TradeId("t-lifecycle-3-buy"),
                    bookId = BookId(bookId),
                    instrumentId = InstrumentId("AAPL"),
                    assetClass = AssetClass.EQUITY,
                    side = Side.BUY,
                    quantity = BigDecimal("100"),
                    price = Money(BigDecimal("150.00"), Currency.getInstance("USD")),
                    tradedAt = Instant.parse("2025-01-15T10:00:00Z"),
                    instrumentType = "CASH_EQUITY",
                    traderId = TraderId("tr-test-001"),
                )
            )

            bookingService.handle(
                BookTradeCommand(
                    tradeId = TradeId("t-lifecycle-3-sell"),
                    bookId = BookId(bookId),
                    instrumentId = InstrumentId("AAPL"),
                    assetClass = AssetClass.EQUITY,
                    side = Side.SELL,
                    quantity = BigDecimal("30"),
                    price = Money(BigDecimal("160.00"), Currency.getInstance("USD")),
                    tradedAt = Instant.parse("2025-01-15T11:00:00Z"),
                    instrumentType = "CASH_EQUITY",
                    traderId = TraderId("tr-test-001"),
                )
            )

            then("position quantity is reduced to 70") {
                val positions = queryService.handle(GetPositionsQuery(BookId(bookId)))
                positions shouldHaveSize 1
                positions[0].quantity.compareTo(BigDecimal("70")) shouldBe 0
            }

            then("realized P&L is 300 USD (30 shares × (160 - 150))") {
                val positions = queryService.handle(GetPositionsQuery(BookId(bookId)))
                positions[0].realizedPnl.amount.compareTo(BigDecimal("300.00")) shouldBe 0
                positions[0].realizedPnl.currency shouldBe Currency.getInstance("USD")
            }

            then("two audit events are recorded") {
                withTimeout(10_000) {
                    while (auditRepository.findByBookId(bookId).size < 2) {
                        delay(100)
                    }
                }
                val auditEvents = auditRepository.findByBookId(bookId)
                auditEvents shouldHaveSize 2
            }
        }
    }

    // --- Test 26: amending a cancelled trade throws InvalidTradeStateException ---

    given("a trade that has been booked and then cancelled") {
        `when`("an amend is attempted on the cancelled trade") {
            val bookId ="port-lifecycle-4"
            val tradeId = "t-lifecycle-4"

            bookingService.handle(
                BookTradeCommand(
                    tradeId = TradeId(tradeId),
                    bookId = BookId(bookId),
                    instrumentId = InstrumentId("AAPL"),
                    assetClass = AssetClass.EQUITY,
                    side = Side.BUY,
                    quantity = BigDecimal("100"),
                    price = Money(BigDecimal("150.00"), Currency.getInstance("USD")),
                    tradedAt = Instant.parse("2025-01-15T10:00:00Z"),
                    instrumentType = "CASH_EQUITY",
                    traderId = TraderId("tr-test-001"),
                )
            )

            lifecycleService.handleCancel(
                CancelTradeCommand(
                    tradeId = TradeId(tradeId),
                    bookId = BookId(bookId),
                )
            )

            then("InvalidTradeStateException is thrown") {
                shouldThrow<InvalidTradeStateException> {
                    lifecycleService.handleAmend(
                        AmendTradeCommand(
                            originalTradeId = TradeId(tradeId),
                            newTradeId = TradeId("t-lifecycle-4-amend"),
                            bookId = BookId(bookId),
                            instrumentId = InstrumentId("AAPL"),
                            assetClass = AssetClass.EQUITY,
                            side = Side.BUY,
                            quantity = BigDecimal("200"),
                            price = Money(BigDecimal("155.00"), Currency.getInstance("USD")),
                            tradedAt = Instant.parse("2025-01-15T12:00:00Z"),
                            instrumentType = "CASH_EQUITY",
                        )
                    )
                }
            }
        }
    }

    // --- Test 27: cancelling an already-cancelled trade returns idempotently ---

    given("a trade that has already been cancelled") {
        `when`("a second cancel is attempted") {
            val bookId ="port-lifecycle-5"
            val tradeId = "t-lifecycle-5"

            bookingService.handle(
                BookTradeCommand(
                    tradeId = TradeId(tradeId),
                    bookId = BookId(bookId),
                    instrumentId = InstrumentId("AAPL"),
                    assetClass = AssetClass.EQUITY,
                    side = Side.BUY,
                    quantity = BigDecimal("100"),
                    price = Money(BigDecimal("150.00"), Currency.getInstance("USD")),
                    tradedAt = Instant.parse("2025-01-15T10:00:00Z"),
                    instrumentType = "CASH_EQUITY",
                    traderId = TraderId("tr-test-001"),
                )
            )

            val firstResult = lifecycleService.handleCancel(
                CancelTradeCommand(
                    tradeId = TradeId(tradeId),
                    bookId = BookId(bookId),
                )
            )

            then("the same cancelled result is returned idempotently") {
                val secondResult = lifecycleService.handleCancel(
                    CancelTradeCommand(
                        tradeId = TradeId(tradeId),
                        bookId = BookId(bookId),
                    )
                )
                secondResult.trade.status shouldBe TradeStatus.CANCELLED
                secondResult.position.quantity.compareTo(firstResult.position.quantity) shouldBe 0
            }
        }
    }

    // --- Test 28: HARD position limit breach blocks trade and produces no audit event ---

    given("a limit check service with a HARD position limit of 1000 shares") {
        `when`("a trade for 1001 shares is submitted") {
            val bookId ="port-lifecycle-6"

            then("LimitBreachException is thrown and no position or audit event is created") {
                shouldThrow<LimitBreachException> {
                    bookingServiceWithLimits.handle(
                        BookTradeCommand(
                            tradeId = TradeId("t-lifecycle-6"),
                            bookId = BookId(bookId),
                            instrumentId = InstrumentId("AAPL"),
                            assetClass = AssetClass.EQUITY,
                            side = Side.BUY,
                            quantity = BigDecimal("1001"),
                            price = Money(BigDecimal("150.00"), Currency.getInstance("USD")),
                            tradedAt = Instant.parse("2025-01-15T10:00:00Z"),
                            instrumentType = "CASH_EQUITY",
                            traderId = TraderId("tr-test-001"),
                        )
                    )
                }

                val positions = queryService.handle(GetPositionsQuery(BookId(bookId)))
                positions shouldHaveSize 0

                // Brief delay to allow any spurious Kafka events to propagate before asserting absence
                delay(500)
                val auditEvents = auditRepository.findByBookId(bookId)
                auditEvents shouldHaveSize 0
            }
        }
    }

    // --- Test 29: SOFT position limit produces trade with warning and records audit ---

    given("a limit check service with a position limit of 1000 and soft threshold at 80% (800)") {
        `when`("800 shares are booked and then 50 more are attempted (total 850 > soft threshold)") {
            val bookId ="port-lifecycle-7"

            bookingServiceWithLimits.handle(
                BookTradeCommand(
                    tradeId = TradeId("t-lifecycle-7-first"),
                    bookId = BookId(bookId),
                    instrumentId = InstrumentId("AAPL"),
                    assetClass = AssetClass.EQUITY,
                    side = Side.BUY,
                    quantity = BigDecimal("800"),
                    price = Money(BigDecimal("150.00"), Currency.getInstance("USD")),
                    tradedAt = Instant.parse("2025-01-15T10:00:00Z"),
                    instrumentType = "CASH_EQUITY",
                    traderId = TraderId("tr-test-001"),
                )
            )

            val softBreachResult = bookingServiceWithLimits.handle(
                BookTradeCommand(
                    tradeId = TradeId("t-lifecycle-7-second"),
                    bookId = BookId(bookId),
                    instrumentId = InstrumentId("AAPL"),
                    assetClass = AssetClass.EQUITY,
                    side = Side.BUY,
                    quantity = BigDecimal("50"),
                    price = Money(BigDecimal("150.00"), Currency.getInstance("USD")),
                    tradedAt = Instant.parse("2025-01-15T11:00:00Z"),
                    instrumentType = "CASH_EQUITY",
                    traderId = TraderId("tr-test-001"),
                )
            )

            then("the trade succeeds with a SOFT warning") {
                softBreachResult.warnings.size shouldBe 1
                softBreachResult.warnings[0].severity shouldBe LimitBreachSeverity.SOFT
            }

            then("the position quantity is 850") {
                val positions = queryService.handle(GetPositionsQuery(BookId(bookId)))
                positions shouldHaveSize 1
                positions[0].quantity.compareTo(BigDecimal("850")) shouldBe 0
            }

            then("audit events are recorded for both trades") {
                withTimeout(10_000) {
                    while (auditRepository.findByBookId(bookId).size < 2) {
                        delay(100)
                    }
                }
                val auditEvents = auditRepository.findByBookId(bookId)
                auditEvents shouldHaveSize 2
            }
        }
    }

    // --- Test 30: multiple trades aggregate position correctly ---

    given("a series of BUY and SELL trades on the same instrument") {
        `when`("100 AAPL @ 150, 50 AAPL @ 160, and SELL 30 AAPL @ 155 are executed") {
            val bookId ="port-lifecycle-8"

            bookingService.handle(
                BookTradeCommand(
                    tradeId = TradeId("t-lifecycle-8-buy1"),
                    bookId = BookId(bookId),
                    instrumentId = InstrumentId("AAPL"),
                    assetClass = AssetClass.EQUITY,
                    side = Side.BUY,
                    quantity = BigDecimal("100"),
                    price = Money(BigDecimal("150.00"), Currency.getInstance("USD")),
                    tradedAt = Instant.parse("2025-01-15T10:00:00Z"),
                    instrumentType = "CASH_EQUITY",
                    traderId = TraderId("tr-test-001"),
                )
            )

            bookingService.handle(
                BookTradeCommand(
                    tradeId = TradeId("t-lifecycle-8-buy2"),
                    bookId = BookId(bookId),
                    instrumentId = InstrumentId("AAPL"),
                    assetClass = AssetClass.EQUITY,
                    side = Side.BUY,
                    quantity = BigDecimal("50"),
                    price = Money(BigDecimal("160.00"), Currency.getInstance("USD")),
                    tradedAt = Instant.parse("2025-01-15T10:30:00Z"),
                    instrumentType = "CASH_EQUITY",
                    traderId = TraderId("tr-test-001"),
                )
            )

            bookingService.handle(
                BookTradeCommand(
                    tradeId = TradeId("t-lifecycle-8-sell"),
                    bookId = BookId(bookId),
                    instrumentId = InstrumentId("AAPL"),
                    assetClass = AssetClass.EQUITY,
                    side = Side.SELL,
                    quantity = BigDecimal("30"),
                    price = Money(BigDecimal("155.00"), Currency.getInstance("USD")),
                    tradedAt = Instant.parse("2025-01-15T11:00:00Z"),
                    instrumentType = "CASH_EQUITY",
                    traderId = TraderId("tr-test-001"),
                )
            )

            then("the final position quantity is 120") {
                val positions = queryService.handle(GetPositionsQuery(BookId(bookId)))
                positions shouldHaveSize 1
                positions[0].quantity.compareTo(BigDecimal("120")) shouldBe 0
            }

            then("the average cost is the weighted average of the BUY trades (~153.33)") {
                // (100 × 150 + 50 × 160) / 150 = 23000 / 150 = 153.333...
                val expectedAvgCost = BigDecimal("23000").divide(BigDecimal("150"), 2, RoundingMode.HALF_UP)
                val positions = queryService.handle(GetPositionsQuery(BookId(bookId)))
                positions[0].averageCost.amount.setScale(2, RoundingMode.HALF_UP)
                    .compareTo(expectedAvgCost) shouldBe 0
            }

            then("three audit events are recorded") {
                withTimeout(10_000) {
                    while (auditRepository.findByBookId(bookId).size < 3) {
                        delay(100)
                    }
                }
                val auditEvents = auditRepository.findByBookId(bookId)
                auditEvents shouldHaveSize 3
            }
        }
    }
})

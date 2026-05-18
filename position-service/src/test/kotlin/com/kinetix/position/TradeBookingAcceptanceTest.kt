package com.kinetix.position

import com.kinetix.common.kafka.events.TradeEventMessage
import com.kinetix.common.model.*
import com.kinetix.testsupport.kafka.KafkaTestSetup
import com.kinetix.position.kafka.KafkaTradeEventPublisher
import com.kinetix.position.model.LimitBreachSeverity
import com.kinetix.position.model.LimitDefinition
import com.kinetix.position.model.LimitLevel
import com.kinetix.position.model.LimitType
import com.kinetix.position.persistence.DatabaseTestSetup
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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.Currency
import java.util.UUID

private val USD = Currency.getInstance("USD")
private val TRADED_AT = Instant.parse("2025-01-15T10:00:00Z")

class TradeBookingAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val tradeRepo = ExposedTradeEventRepository(db)
    val positionRepo = ExposedPositionRepository(db)
    val transactional = ExposedTransactionalRunner(db)
    val limitDefinitionRepo = ExposedLimitDefinitionRepository(db)
    val temporaryLimitIncreaseRepo = ExposedTemporaryLimitIncreaseRepository(db)
    val limitHierarchyService = LimitHierarchyService(limitDefinitionRepo, temporaryLimitIncreaseRepo)

    fun preTradeCheck() = HierarchyBasedPreTradeCheckService(positionRepo, limitHierarchyService)

    val bootstrapServers = KafkaTestSetup.start()
    val producer = KafkaTestSetup.createProducer(bootstrapServers)

    fun publisherFor(topic: String) = KafkaTradeEventPublisher(producer, topic)

    fun consumerFor(topic: String, group: String): KafkaConsumer<String, String> {
        val consumer = KafkaTestSetup.createConsumer(bootstrapServers, group)
        consumer.subscribe(listOf(topic))
        return consumer
    }

    beforeEach {
        newSuspendedTransaction(db = db) {
            exec("TRUNCATE TABLE trade_events, positions, limit_definitions, limit_temporary_increases RESTART IDENTITY CASCADE")
        }
    }

    // Scenario 1: book a trade creates position
    test("a portfolio with no existing positions — a BUY trade is submitted for 100 AAPL at \$150 — trade is persisted, position is created with qty=100 and avgCost=\$150, and a trade event is published") {
        val topic = "trades.lifecycle.tb-book-1"
        val consumer = consumerFor(topic, "tb-book-1-group")
        try {
            val publisher = publisherFor(topic)
            val service = TradeBookingService(tradeRepo, positionRepo, transactional, publisher)

            val result = service.handle(
                BookTradeCommand(
                    tradeId = TradeId("t-book-1"),
                    bookId = BookId("port-book-1"),
                    instrumentId = InstrumentId("AAPL"),
                    assetClass = AssetClass.EQUITY,
                    side = Side.BUY,
                    quantity = BigDecimal("100"),
                    price = Money(BigDecimal("150.00"), USD),
                    tradedAt = TRADED_AT,
                    instrumentType = "CASH_EQUITY",
                    traderId = TraderId("tr-test-001"),
                ),
            )

            val saved = tradeRepo.findByTradeId(TradeId("t-book-1"))
            saved?.tradeId shouldBe TradeId("t-book-1")
            saved?.side shouldBe Side.BUY
            saved?.quantity?.compareTo(BigDecimal("100")) shouldBe 0

            val position = positionRepo.findByKey(BookId("port-book-1"), InstrumentId("AAPL"))
            position?.quantity?.compareTo(BigDecimal("100")) shouldBe 0
            position?.averageCost?.amount?.compareTo(BigDecimal("150.00")) shouldBe 0

            val records = consumer.poll(Duration.ofSeconds(10))
            records.count() shouldBe 1
            val event = Json.decodeFromString<TradeEventMessage>(records.first().value())
            event.tradeId shouldBe "t-book-1"
            event.bookId shouldBe "port-book-1"

            result.trade.tradeId shouldBe TradeId("t-book-1")
            result.warnings.shouldBeEmpty()
        } finally {
            consumer.close()
        }
    }

    // Scenario 2: hard limit breach blocks trade
    test("a hard BOOK-level position limit of 1000 shares — a BUY trade for 1001 shares is submitted — LimitBreachException is thrown, no trade or position is created, and no trade event is published") {
        val topic = "trades.lifecycle.tb-hard-1"
        val consumer = consumerFor(topic, "tb-hard-1-group")
        try {
            val publisher = publisherFor(topic)
            limitDefinitionRepo.save(
                LimitDefinition(
                    id = UUID.randomUUID().toString(),
                    level = LimitLevel.BOOK,
                    entityId = "port-hard-1",
                    limitType = LimitType.POSITION,
                    limitValue = BigDecimal("1000"),
                    intradayLimit = null,
                    overnightLimit = null,
                    active = true,
                ),
            )
            val service = TradeBookingService(tradeRepo, positionRepo, transactional, publisher, preTradeCheck())

            var caughtException: Exception? = null
            try {
                service.handle(
                    BookTradeCommand(
                        tradeId = TradeId("t-hard-1"),
                        bookId = BookId("port-hard-1"),
                        instrumentId = InstrumentId("AAPL"),
                        assetClass = AssetClass.EQUITY,
                        side = Side.BUY,
                        quantity = BigDecimal("1001"),
                        price = Money(BigDecimal("100.00"), USD),
                        tradedAt = TRADED_AT,
                        instrumentType = "CASH_EQUITY",
                        traderId = TraderId("tr-test-001"),
                    ),
                )
            } catch (e: LimitBreachException) {
                caughtException = e
            }
            (caughtException is LimitBreachException) shouldBe true

            tradeRepo.findByTradeId(TradeId("t-hard-1")) shouldBe null
            positionRepo.findByKey(BookId("port-hard-1"), InstrumentId("AAPL")) shouldBe null

            val records = consumer.poll(Duration.ofMillis(750))
            records.count() shouldBe 0
        } finally {
            consumer.close()
        }
    }

    // Scenario 3: soft limit warning passes trade with warning
    test("a BOOK-level position limit of 1000 shares with an existing position of 800 shares — a BUY trade for 1 share is submitted (total would be 801, above 80% soft threshold) — trade succeeds with a SOFT severity warning and position is updated to 801 shares") {
        val topic = "trades.lifecycle.tb-soft-1"
        val consumer = consumerFor(topic, "tb-soft-1-group")
        try {
            val publisher = publisherFor(topic)
            // Seed 800 shares without limit check
            TradeBookingService(tradeRepo, positionRepo, transactional, publisher).handle(
                BookTradeCommand(
                    tradeId = TradeId("t-soft-seed"),
                    bookId = BookId("port-soft-1"),
                    instrumentId = InstrumentId("AAPL"),
                    assetClass = AssetClass.EQUITY,
                    side = Side.BUY,
                    quantity = BigDecimal("800"),
                    price = Money(BigDecimal("100.00"), USD),
                    tradedAt = TRADED_AT,
                    instrumentType = "CASH_EQUITY",
                    traderId = TraderId("tr-test-001"),
                ),
            )

            limitDefinitionRepo.save(
                LimitDefinition(
                    id = UUID.randomUUID().toString(),
                    level = LimitLevel.BOOK,
                    entityId = "port-soft-1",
                    limitType = LimitType.POSITION,
                    limitValue = BigDecimal("1000"),
                    intradayLimit = null,
                    overnightLimit = null,
                    active = true,
                ),
            )
            val service = TradeBookingService(tradeRepo, positionRepo, transactional, publisher, preTradeCheck())

            val result = service.handle(
                BookTradeCommand(
                    tradeId = TradeId("t-soft-1"),
                    bookId = BookId("port-soft-1"),
                    instrumentId = InstrumentId("AAPL"),
                    assetClass = AssetClass.EQUITY,
                    side = Side.BUY,
                    quantity = BigDecimal("1"),
                    price = Money(BigDecimal("100.00"), USD),
                    tradedAt = TRADED_AT,
                    instrumentType = "CASH_EQUITY",
                    traderId = TraderId("tr-test-001"),
                ),
            )

            tradeRepo.findByTradeId(TradeId("t-soft-1"))?.tradeId shouldBe TradeId("t-soft-1")
            result.warnings shouldHaveSize 1
            result.warnings[0].severity shouldBe LimitBreachSeverity.SOFT

            val position = positionRepo.findByKey(BookId("port-soft-1"), InstrumentId("AAPL"))
            position?.quantity?.compareTo(BigDecimal("801")) shouldBe 0
        } finally {
            consumer.close()
        }
    }

    // Scenario: counterpartyId is persisted when supplied
    test("a booking request that includes a counterpartyId — the trade is submitted — the persisted trade record carries the supplied counterpartyId") {
        val topic = "trades.lifecycle.tb-cpty-1"
        val consumer = consumerFor(topic, "tb-cpty-1-group")
        try {
            val publisher = publisherFor(topic)
            val service = TradeBookingService(tradeRepo, positionRepo, transactional, publisher)

            service.handle(
                BookTradeCommand(
                    tradeId = TradeId("t-cpty-1"),
                    bookId = BookId("port-cpty-1"),
                    instrumentId = InstrumentId("AAPL"),
                    assetClass = AssetClass.EQUITY,
                    side = Side.BUY,
                    quantity = BigDecimal("100"),
                    price = Money(BigDecimal("150.00"), USD),
                    tradedAt = TRADED_AT,
                    counterpartyId = "CPTY-ABC",
                    instrumentType = "CASH_EQUITY",
                    traderId = TraderId("tr-test-001"),
                ),
            )

            val saved = tradeRepo.findByTradeId(TradeId("t-cpty-1"))
            saved?.counterpartyId shouldBe "CPTY-ABC"
        } finally {
            consumer.close()
        }
    }

    // Scenario: counterpartyId defaults to null when not supplied
    test("a booking request with no counterpartyId — the trade is submitted — the persisted trade record has a null counterpartyId") {
        val topic = "trades.lifecycle.tb-cpty-2"
        val consumer = consumerFor(topic, "tb-cpty-2-group")
        try {
            val publisher = publisherFor(topic)
            val service = TradeBookingService(tradeRepo, positionRepo, transactional, publisher)

            service.handle(
                BookTradeCommand(
                    tradeId = TradeId("t-cpty-2"),
                    bookId = BookId("port-cpty-2"),
                    instrumentId = InstrumentId("AAPL"),
                    assetClass = AssetClass.EQUITY,
                    side = Side.BUY,
                    quantity = BigDecimal("50"),
                    price = Money(BigDecimal("100.00"), USD),
                    tradedAt = TRADED_AT,
                    instrumentType = "CASH_EQUITY",
                    traderId = TraderId("tr-test-001"),
                ),
            )

            val saved = tradeRepo.findByTradeId(TradeId("t-cpty-2"))
            saved?.counterpartyId shouldBe null
        } finally {
            consumer.close()
        }
    }

    // Scenario 4: duplicate trade ID handled idempotently
    test("a previously booked trade with ID t-idem-1 — the same trade ID is submitted a second time — only one trade record exists, exactly one trade event is published, and position quantity is 100") {
        val topic = "trades.lifecycle.tb-idem-1"
        val consumer = consumerFor(topic, "tb-idem-1-group")
        try {
            val publisher = publisherFor(topic)
            val service = TradeBookingService(tradeRepo, positionRepo, transactional, publisher)

            val command = BookTradeCommand(
                tradeId = TradeId("t-idem-1"),
                bookId = BookId("port-idem-1"),
                instrumentId = InstrumentId("AAPL"),
                assetClass = AssetClass.EQUITY,
                side = Side.BUY,
                quantity = BigDecimal("100"),
                price = Money(BigDecimal("150.00"), USD),
                tradedAt = TRADED_AT,
                instrumentType = "CASH_EQUITY",
                traderId = TraderId("tr-test-001"),
            )
            service.handle(command)
            service.handle(command)

            tradeRepo.findByBookId(BookId("port-idem-1")) shouldHaveSize 1

            val records = consumer.poll(Duration.ofSeconds(10))
            records.count() shouldBe 1
            val event = Json.decodeFromString<TradeEventMessage>(records.first().value())
            event.tradeId shouldBe "t-idem-1"

            val position = positionRepo.findByKey(BookId("port-idem-1"), InstrumentId("AAPL"))
            position?.quantity?.compareTo(BigDecimal("100")) shouldBe 0
        } finally {
            consumer.close()
        }
    }

    afterSpec {
        producer.close()
    }
})

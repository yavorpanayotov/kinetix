package com.kinetix.position

import com.kinetix.common.kafka.events.LimitBreachEvent
import com.kinetix.common.model.*
import com.kinetix.testsupport.kafka.KafkaTestSetup
import com.kinetix.position.kafka.KafkaTradeEventPublisher
import com.kinetix.position.kafka.LimitBreachEventPublisher
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
import io.kotest.matchers.shouldBe

import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency
import java.util.UUID

private val USD = Currency.getInstance("USD")
private val TRADED_AT = Instant.parse("2025-01-15T10:00:00Z")

class LimitEnforcementAcceptanceTest : FunSpec({

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

    beforeEach {
        newSuspendedTransaction(db = db) {
            exec("TRUNCATE TABLE trade_events, positions, limit_definitions, limit_temporary_increases RESTART IDENTITY CASCADE")
        }
    }

    // Scenario 10: notional limit breach
    test("a BOOK-level notional limit of \$200,000 — a trade with \$300,000 notional is submitted (3000 shares at \$100) — LimitBreachException is thrown with NOTIONAL breach and no trade is persisted") {
        val publisher = publisherFor("trades.lifecycle.le-notional-1")
        limitDefinitionRepo.save(
            LimitDefinition(
                id = UUID.randomUUID().toString(),
                level = LimitLevel.BOOK,
                entityId = "port-notional-1",
                limitType = LimitType.NOTIONAL,
                limitValue = BigDecimal("200000"),
                intradayLimit = null,
                overnightLimit = null,
                active = true,
            ),
        )
        val service = TradeBookingService(tradeRepo, positionRepo, transactional, publisher, preTradeCheck())

        var notionalEx: LimitBreachException? = null
        try {
            service.handle(
                BookTradeCommand(
                    tradeId = TradeId("t-notional-1"),
                    bookId = BookId("port-notional-1"),
                    instrumentId = InstrumentId("AAPL"),
                    assetClass = AssetClass.EQUITY,
                    side = Side.BUY,
                    quantity = BigDecimal("3000"),
                    price = Money(BigDecimal("100.00"), USD),
                    tradedAt = TRADED_AT,
                    instrumentType = "CASH_EQUITY",
                    traderId = TraderId("tr-test-001"),
                ),
            )
        } catch (e: LimitBreachException) {
            notionalEx = e
        }
        (notionalEx is LimitBreachException) shouldBe true
        notionalEx!!.result.blocked shouldBe true
        notionalEx.result.breaches.any { it.limitType == "NOTIONAL" } shouldBe true
        tradeRepo.findByTradeId(TradeId("t-notional-1")) shouldBe null
    }

    // Scenario 11: concentration limit breach
    test("a BOOK-level concentration limit of 50% with AAPL at 50% of portfolio by market value — a trade is submitted that would push AAPL above 50% concentration — LimitBreachException is thrown with CONCENTRATION breach and no trade is persisted") {
        // Portfolio: AAPL 4000 shares @ $100 market = $400K; MSFT 4000 shares @ $100 market = $400K
        // Total portfolio market value = $800K; AAPL = 50% (exactly at limit)
        // Trade: BUY 1 AAPL at $100 → newPortfolioValue = $800,100
        //   instrumentValue = 4001 * $100 = $400,100
        //   concentrationPct ≈ 50.006% > 50% → breach
        val publisher = publisherFor("trades.lifecycle.le-concentration-1")
        limitDefinitionRepo.save(
            LimitDefinition(
                id = UUID.randomUUID().toString(),
                level = LimitLevel.BOOK,
                entityId = "port-conc-1",
                limitType = LimitType.CONCENTRATION,
                limitValue = BigDecimal("0.5"),
                intradayLimit = null,
                overnightLimit = null,
                active = true,
            ),
        )
        val service = TradeBookingService(tradeRepo, positionRepo, transactional, publisher, preTradeCheck())

        positionRepo.save(
            Position(
                bookId = BookId("port-conc-1"),
                instrumentId = InstrumentId("AAPL"),
                assetClass = AssetClass.EQUITY,
                quantity = BigDecimal("4000"),
                averageCost = Money(BigDecimal("100.00"), USD),
                marketPrice = Money(BigDecimal("100.00"), USD),
                instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
            ),
        )
        positionRepo.save(
            Position(
                bookId = BookId("port-conc-1"),
                instrumentId = InstrumentId("MSFT"),
                assetClass = AssetClass.EQUITY,
                quantity = BigDecimal("4000"),
                averageCost = Money(BigDecimal("100.00"), USD),
                marketPrice = Money(BigDecimal("100.00"), USD),
                instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
            ),
        )

        var concEx: LimitBreachException? = null
        try {
            service.handle(
                BookTradeCommand(
                    tradeId = TradeId("t-conc-1"),
                    bookId = BookId("port-conc-1"),
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
        } catch (e: LimitBreachException) {
            concEx = e
        }
        (concEx is LimitBreachException) shouldBe true
        concEx!!.result.blocked shouldBe true
        concEx.result.breaches.any { it.limitType == "CONCENTRATION" } shouldBe true
        tradeRepo.findByTradeId(TradeId("t-conc-1")) shouldBe null
    }

    // Phase-4 closeout: limit-breach observability
    test("a HARD limit breach during booking and a configured LimitBreachEventPublisher — a trade is rejected for breaching the limit — a LimitBreachEvent is published before the LimitBreachException is thrown") {
        val tradePublisher = publisherFor("trades.lifecycle.le-publish-1")
        val capturedBreaches = mutableListOf<LimitBreachEvent>()
        val breachPublisher = object : LimitBreachEventPublisher {
            override suspend fun publish(event: LimitBreachEvent) {
                capturedBreaches.add(event)
            }
        }
        limitDefinitionRepo.save(
            LimitDefinition(
                id = UUID.randomUUID().toString(),
                level = LimitLevel.BOOK,
                entityId = "port-publish-1",
                limitType = LimitType.NOTIONAL,
                limitValue = BigDecimal("100000"),
                intradayLimit = null,
                overnightLimit = null,
                active = true,
            ),
        )
        val service = TradeBookingService(
            tradeRepo,
            positionRepo,
            transactional,
            tradePublisher,
            preTradeCheck(),
            limitBreachEventPublisher = breachPublisher,
        )

        var thrown: LimitBreachException? = null
        try {
            service.handle(
                BookTradeCommand(
                    tradeId = TradeId("t-publish-1"),
                    bookId = BookId("port-publish-1"),
                    instrumentId = InstrumentId("AAPL"),
                    assetClass = AssetClass.EQUITY,
                    side = Side.BUY,
                    quantity = BigDecimal("3000"),
                    price = Money(BigDecimal("100.00"), USD),
                    tradedAt = TRADED_AT,
                    instrumentType = "CASH_EQUITY",
                    traderId = TraderId("tr-test-001"),
                ),
            )
        } catch (e: LimitBreachException) {
            thrown = e
        }

        (thrown is LimitBreachException) shouldBe true
        capturedBreaches.size shouldBe 1
        capturedBreaches[0].bookId shouldBe "port-publish-1"
        capturedBreaches[0].tradeId shouldBe "t-publish-1"
        capturedBreaches[0].limitType shouldBe "NOTIONAL"
        capturedBreaches[0].severity shouldBe "HARD"
        tradeRepo.findByTradeId(TradeId("t-publish-1")) shouldBe null
    }

    afterSpec {
        producer.close()
    }
})

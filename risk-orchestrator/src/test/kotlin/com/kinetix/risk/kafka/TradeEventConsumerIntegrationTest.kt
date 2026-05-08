package com.kinetix.risk.kafka

import com.kinetix.common.kafka.events.TradeEventMessage
import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Position
import com.kinetix.risk.cache.VaRCache
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.model.PnlTrigger
import com.kinetix.risk.model.ValuationResult
import com.kinetix.risk.model.VaRCalculationRequest
import com.kinetix.risk.service.IntradayPnlService
import com.kinetix.risk.service.LiquidityRiskService
import com.kinetix.risk.service.VaRCalculationService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.ProducerRecord
import java.math.BigDecimal
import java.util.Currency

class TradeEventConsumerIntegrationTest : FunSpec({

    test("consumes trade event and triggers VaR calculation for the portfolio and caches result") {
        val bootstrapServers = KafkaTestSetup.start()
        val topic = "trades.lifecycle.test-1"
        val varService = mockk<VaRCalculationService>()
        val varCache = mockk<VaRCache>(relaxed = true)
        val kafkaConsumer = KafkaTestSetup.createConsumer(bootstrapServers, "trade-consumer-test-1")
        val consumer = TradeEventConsumer(kafkaConsumer, varService, varCache = varCache, topic = topic)

        val mockResult = mockk<ValuationResult>()
        var capturedBookId: String? = null
        coEvery { varService.calculateVaR(any(), any()) } answers {
            capturedBookId = firstArg<VaRCalculationRequest>().bookId.value
            mockResult
        }

        val job = launch { consumer.start() }

        val event = TradeEventMessage(
            tradeId = "trade-1",
            bookId = "port-1",
            instrumentId = "AAPL",
            assetClass = "EQUITY",
            side = "BUY",
            quantity = "100",
            priceAmount = "150.00",
            priceCurrency = "USD",
            tradedAt = "2025-01-15T10:00:00Z",
        )

        val producer = KafkaTestSetup.createProducer(bootstrapServers)
        producer.send(ProducerRecord(topic, "port-1", Json.encodeToString(event))).get()

        withTimeout(10_000) {
            while (capturedBookId == null) {
                delay(100)
            }
        }

        capturedBookId shouldBe "port-1"
        coVerify(exactly = 1) { varService.calculateVaR(match { it.bookId == BookId("port-1") }, any()) }
        coVerify(exactly = 1) { varCache.put("port-1", mockResult) }

        job.cancel()
        producer.close()
    }

    test("processes multiple trade events for different portfolios") {
        val bootstrapServers = KafkaTestSetup.start()
        val topic = "trades.lifecycle.test-2"
        val varService = mockk<VaRCalculationService>()
        val kafkaConsumer = KafkaTestSetup.createConsumer(bootstrapServers, "trade-consumer-test-2")
        val consumer = TradeEventConsumer(kafkaConsumer, varService, topic = topic)

        val portfoliosCalculated = mutableListOf<String>()
        coEvery { varService.calculateVaR(any(), any()) } answers {
            portfoliosCalculated.add(firstArg<VaRCalculationRequest>().bookId.value)
            null
        }

        val job = launch { consumer.start() }

        val producer = KafkaTestSetup.createProducer(bootstrapServers)
        for (portId in listOf("port-A", "port-B")) {
            val event = TradeEventMessage(
                tradeId = "trade-$portId",
                bookId = portId,
                instrumentId = "AAPL",
                assetClass = "EQUITY",
                side = "BUY",
                quantity = "100",
                priceAmount = "150.00",
                priceCurrency = "USD",
                tradedAt = "2025-01-15T10:00:00Z",
            )
            producer.send(ProducerRecord(topic, portId, Json.encodeToString(event))).get()
        }

        withTimeout(10_000) {
            while (portfoliosCalculated.size < 2) {
                delay(100)
            }
        }

        portfoliosCalculated.toSet() shouldBe setOf("port-A", "port-B")

        job.cancel()
        producer.close()
    }

    test("triggers liquidity risk recomputation when trade notional exceeds threshold percentage of existing position notional") {
        val bootstrapServers = KafkaTestSetup.start()
        val topic = "trades.lifecycle.test-liq-1"
        val varService = mockk<VaRCalculationService>()
        val liquidityRiskService = mockk<LiquidityRiskService>(relaxed = true)
        val positionProvider = mockk<PositionProvider>()
        val USD = Currency.getInstance("USD")
        // Existing position: 1000 shares at $100 = $100,000 notional
        val existingPosition = Position(
            bookId = BookId("port-liq"),
            instrumentId = InstrumentId("AAPL"),
            assetClass = AssetClass.EQUITY,
            quantity = BigDecimal("1000"),
            averageCost = Money(BigDecimal("100.00"), USD),
            marketPrice = Money(BigDecimal("100.00"), USD),
            instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
        )
        coEvery { positionProvider.getPositions(BookId("port-liq")) } returns listOf(existingPosition)

        val kafkaConsumer = KafkaTestSetup.createConsumer(bootstrapServers, "trade-consumer-liq-1")
        val consumer = TradeEventConsumer(
            kafkaConsumer,
            varService,
            topic = topic,
            liquidityRiskService = liquidityRiskService,
            positionProvider = positionProvider,
            liquidityRecomputeThresholdPct = 0.10,
        )

        var varCalculated = false
        coEvery { varService.calculateVaR(any(), any()) } answers {
            varCalculated = true
            null
        }

        val job = launch { consumer.start() }

        // Trade notional: 150 shares * $100 = $15,000 = 15% of $100,000 → exceeds 10% threshold
        val event = TradeEventMessage(
            tradeId = "trade-liq-1",
            bookId = "port-liq",
            instrumentId = "AAPL",
            assetClass = "EQUITY",
            side = "BUY",
            quantity = "150",
            priceAmount = "100.00",
            priceCurrency = "USD",
            tradedAt = "2025-01-15T10:00:00Z",
        )

        val producer = KafkaTestSetup.createProducer(bootstrapServers)
        producer.send(ProducerRecord(topic, "port-liq", Json.encodeToString(event))).get()

        withTimeout(10_000) {
            while (!varCalculated) {
                delay(100)
            }
        }
        delay(300) // allow liquidity call to propagate

        coVerify(atLeast = 1) { liquidityRiskService.calculateAndSave(BookId("port-liq"), any()) }

        job.cancel()
        producer.close()
    }

    test("skips liquidity risk recomputation when trade notional is below threshold percentage of existing position notional") {
        val bootstrapServers = KafkaTestSetup.start()
        val topic = "trades.lifecycle.test-liq-2"
        val varService = mockk<VaRCalculationService>()
        val liquidityRiskService = mockk<LiquidityRiskService>(relaxed = true)
        val positionProvider = mockk<PositionProvider>()
        val USD = Currency.getInstance("USD")
        // Existing position: 1000 shares at $100 = $100,000 notional
        val existingPosition = Position(
            bookId = BookId("port-liq2"),
            instrumentId = InstrumentId("AAPL"),
            assetClass = AssetClass.EQUITY,
            quantity = BigDecimal("1000"),
            averageCost = Money(BigDecimal("100.00"), USD),
            marketPrice = Money(BigDecimal("100.00"), USD),
            instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
        )
        coEvery { positionProvider.getPositions(BookId("port-liq2")) } returns listOf(existingPosition)

        val kafkaConsumer = KafkaTestSetup.createConsumer(bootstrapServers, "trade-consumer-liq-2")
        val consumer = TradeEventConsumer(
            kafkaConsumer,
            varService,
            topic = topic,
            liquidityRiskService = liquidityRiskService,
            positionProvider = positionProvider,
            liquidityRecomputeThresholdPct = 0.10,
        )

        var varCalculated = false
        coEvery { varService.calculateVaR(any(), any()) } answers {
            varCalculated = true
            null
        }

        val job = launch { consumer.start() }

        // Trade notional: 5 shares * $100 = $500 = 0.5% of $100,000 → below 10% threshold
        val event = TradeEventMessage(
            tradeId = "trade-liq-2",
            bookId = "port-liq2",
            instrumentId = "AAPL",
            assetClass = "EQUITY",
            side = "BUY",
            quantity = "5",
            priceAmount = "100.00",
            priceCurrency = "USD",
            tradedAt = "2025-01-15T10:00:00Z",
        )

        val producer = KafkaTestSetup.createProducer(bootstrapServers)
        producer.send(ProducerRecord(topic, "port-liq2", Json.encodeToString(event))).get()

        withTimeout(10_000) {
            while (!varCalculated) {
                delay(100)
            }
        }
        delay(300)

        coVerify(exactly = 0) { liquidityRiskService.calculateAndSave(any(), any()) }

        job.cancel()
        producer.close()
    }

    test("triggers intradayPnlService recompute after VaR calculation on trade event") {
        val bootstrapServers = KafkaTestSetup.start()
        val topic = "trades.lifecycle.test-3"
        val varService = mockk<VaRCalculationService>()
        val intradayPnlService = mockk<IntradayPnlService>(relaxed = true)
        val kafkaConsumer = KafkaTestSetup.createConsumer(bootstrapServers, "trade-consumer-test-3")
        val consumer = TradeEventConsumer(
            kafkaConsumer,
            varService,
            intradayPnlService = intradayPnlService,
            topic = topic,
        )

        val mockResult = mockk<ValuationResult>()
        var varCalculated = false
        coEvery { varService.calculateVaR(any(), any()) } answers {
            varCalculated = true
            mockResult
        }

        val job = launch { consumer.start() }

        val event = TradeEventMessage(
            tradeId = "trade-pnl",
            bookId = "port-pnl",
            instrumentId = "AAPL",
            assetClass = "EQUITY",
            side = "BUY",
            quantity = "100",
            priceAmount = "150.00",
            priceCurrency = "USD",
            tradedAt = "2025-01-15T10:00:00Z",
        )

        val producer = KafkaTestSetup.createProducer(bootstrapServers)
        producer.send(ProducerRecord(topic, "port-pnl", Json.encodeToString(event))).get()

        withTimeout(10_000) {
            while (!varCalculated) {
                delay(100)
            }
        }
        delay(200) // allow P&L call to propagate

        coVerify(exactly = 1) {
            intradayPnlService.recompute(
                bookId = BookId("port-pnl"),
                trigger = PnlTrigger.TRADE_BOOKED,
                correlationId = null,
            )
        }

        job.cancel()
        producer.close()
    }
})

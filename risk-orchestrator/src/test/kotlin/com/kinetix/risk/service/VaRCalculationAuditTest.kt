package com.kinetix.risk.service

import com.kinetix.common.audit.AuditEventType
import com.kinetix.common.audit.GovernanceAuditEvent
import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Position
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.client.RiskEngineClient
import com.kinetix.risk.kafka.GovernanceAuditPublisher
import com.kinetix.risk.kafka.NoOpRiskResultPublisher
import com.kinetix.risk.model.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.serialization.json.Json
import org.slf4j.MDC
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.RecordMetadata
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency
import java.util.concurrent.Future

private val USD = Currency.getInstance("USD")

private fun aPosition(bookId: String = "book-1") = Position(
    bookId = BookId(bookId),
    instrumentId = InstrumentId("AAPL"),
    assetClass = AssetClass.EQUITY,
    quantity = BigDecimal("100"),
    averageCost = Money(BigDecimal("150.00"), USD),
    marketPrice = Money(BigDecimal("155.00"), USD),
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

private fun aValuationResult(bookId: String = "book-1") = ValuationResult(
    bookId = BookId(bookId),
    calculationType = CalculationType.PARAMETRIC,
    confidenceLevel = ConfidenceLevel.CL_99,
    varValue = 5000.0,
    expectedShortfall = 6250.0,
    componentBreakdown = listOf(ComponentBreakdown(AssetClass.EQUITY, 5000.0, 100.0)),
    greeks = null,
    calculatedAt = Instant.now(),
    computedOutputs = setOf(ValuationOutput.VAR, ValuationOutput.EXPECTED_SHORTFALL),
)

class VaRCalculationAuditTest : FunSpec({

    val positionProvider = mockk<PositionProvider>()
    val riskEngineClient = mockk<RiskEngineClient>()
    val producer = mockk<KafkaProducer<String, String>>()
    val publisher = GovernanceAuditPublisher(producer, topic = "governance.audit")
    val service = VaRCalculationService(
        positionProvider = positionProvider,
        riskEngineClient = riskEngineClient,
        resultPublisher = NoOpRiskResultPublisher(),
        governanceAuditPublisher = publisher,
    )

    fun givenFuture(): Future<RecordMetadata> = mockk(relaxed = true)

    beforeEach {
        clearMocks(positionProvider, riskEngineClient, producer)
        MDC.clear()
    }

    afterEach {
        MDC.clear()
    }

    test("publishes RISK_CALCULATION_COMPLETED when VaR calculation succeeds") {
        val bookId = BookId("book-1")
        coEvery { positionProvider.getPositions(bookId) } returns listOf(aPosition(bookId.value))
        coEvery { riskEngineClient.valuate(any(), any(), any(), any()) } returns aValuationResult(bookId.value)

        val publishedSlot = slot<org.apache.kafka.clients.producer.ProducerRecord<String, String>>()
        every { producer.send(capture(publishedSlot)) } returns givenFuture()

        service.calculateVaR(
            VaRCalculationRequest(
                bookId = bookId,
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_99,
                timeHorizonDays = 1,
            )
        )

        verify(atLeast = 1) { producer.send(any()) }
        val decoded = Json { ignoreUnknownKeys = true }
            .decodeFromString<GovernanceAuditEvent>(publishedSlot.captured.value())
        decoded.eventType shouldBe AuditEventType.RISK_CALCULATION_COMPLETED
        decoded.bookId shouldBe "book-1"
    }

    test("carries the correlationId from the MDC on the published governance event") {
        val bookId = BookId("book-1")
        coEvery { positionProvider.getPositions(bookId) } returns listOf(aPosition(bookId.value))
        coEvery { riskEngineClient.valuate(any(), any(), any(), any()) } returns aValuationResult(bookId.value)

        val publishedSlot = slot<org.apache.kafka.clients.producer.ProducerRecord<String, String>>()
        every { producer.send(capture(publishedSlot)) } returns givenFuture()

        MDC.put("correlationId", "corr-var-123")
        service.calculateVaR(
            VaRCalculationRequest(
                bookId = bookId,
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_99,
                timeHorizonDays = 1,
            )
        )

        val decoded = Json { ignoreUnknownKeys = true }
            .decodeFromString<GovernanceAuditEvent>(publishedSlot.captured.value())
        decoded.correlationId shouldBe "corr-var-123"
    }

    test("publishes a null correlationId when the MDC has no correlation id") {
        val bookId = BookId("book-1")
        coEvery { positionProvider.getPositions(bookId) } returns listOf(aPosition(bookId.value))
        coEvery { riskEngineClient.valuate(any(), any(), any(), any()) } returns aValuationResult(bookId.value)

        val publishedSlot = slot<org.apache.kafka.clients.producer.ProducerRecord<String, String>>()
        every { producer.send(capture(publishedSlot)) } returns givenFuture()

        service.calculateVaR(
            VaRCalculationRequest(
                bookId = bookId,
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_99,
                timeHorizonDays = 1,
            )
        )

        val decoded = Json { ignoreUnknownKeys = true }
            .decodeFromString<GovernanceAuditEvent>(publishedSlot.captured.value())
        decoded.correlationId shouldBe null
    }

    test("when the risk engine throws, a RISK_CALCULATION_FAILED governance event is published") {
        val bookId = BookId("book-1")
        coEvery { positionProvider.getPositions(bookId) } returns listOf(aPosition(bookId.value))
        coEvery { riskEngineClient.valuate(any(), any(), any(), any()) } throws
            RuntimeException("risk engine unavailable")

        val publishedSlot = slot<org.apache.kafka.clients.producer.ProducerRecord<String, String>>()
        every { producer.send(capture(publishedSlot)) } returns givenFuture()

        MDC.put("correlationId", "corr-var-fail-1")
        shouldThrow<RuntimeException> {
            service.calculateVaR(
                VaRCalculationRequest(
                    bookId = bookId,
                    calculationType = CalculationType.PARAMETRIC,
                    confidenceLevel = ConfidenceLevel.CL_99,
                    timeHorizonDays = 1,
                )
            )
        }

        val decoded = Json { ignoreUnknownKeys = true }
            .decodeFromString<GovernanceAuditEvent>(publishedSlot.captured.value())
        decoded.eventType shouldBe AuditEventType.RISK_CALCULATION_FAILED
        decoded.bookId shouldBe "book-1"
        decoded.correlationId shouldBe "corr-var-fail-1"
        decoded.details shouldContain "risk engine unavailable"
    }
})

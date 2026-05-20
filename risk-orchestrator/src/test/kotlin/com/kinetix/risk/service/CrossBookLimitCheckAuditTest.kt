package com.kinetix.risk.service

import com.kinetix.common.audit.AuditEventType
import com.kinetix.common.audit.GovernanceAuditEvent
import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.risk.client.ClientResponse
import com.kinetix.risk.client.LimitServiceClient
import com.kinetix.risk.client.dtos.LimitDefinitionDto
import com.kinetix.risk.kafka.GovernanceAuditPublisher
import com.kinetix.risk.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.RecordMetadata
import org.slf4j.MDC
import java.time.Instant
import java.util.concurrent.Future

private fun aCrossBookResult(
    groupId: String = "desk-alpha",
    varValue: Double = 120_000.0,
) = CrossBookValuationResult(
    portfolioGroupId = groupId,
    bookIds = listOf(BookId("book-1"), BookId("book-2")),
    calculationType = CalculationType.PARAMETRIC,
    confidenceLevel = ConfidenceLevel.CL_95,
    varValue = varValue,
    expectedShortfall = varValue * 1.25,
    componentBreakdown = listOf(ComponentBreakdown(AssetClass.EQUITY, varValue, 100.0)),
    bookContributions = emptyList(),
    totalStandaloneVar = varValue * 1.2,
    diversificationBenefit = varValue * 0.2,
    calculatedAt = Instant.now(),
)

private fun aVarLimit(
    id: String = "lim-desk-1",
    entityId: String = "desk-alpha",
    limitValue: String = "100000",
    level: String = "DESK",
) = LimitDefinitionDto(
    id = id,
    level = level,
    entityId = entityId,
    limitType = "VAR",
    limitValue = limitValue,
    active = true,
)

class CrossBookLimitCheckAuditTest : FunSpec({

    val limitClient = mockk<LimitServiceClient>()
    val producer = mockk<KafkaProducer<String, String>>()
    val publisher = GovernanceAuditPublisher(producer, topic = "governance.audit")
    val service = CrossBookLimitCheckService(limitClient, warningThresholdPct = 0.8, governanceAuditPublisher = publisher)

    fun givenFuture(): Future<RecordMetadata> = mockk(relaxed = true)

    beforeEach {
        clearMocks(limitClient, producer)
        MDC.clear()
    }

    afterEach {
        MDC.clear()
    }

    test("publishes LIMIT_BREACHED audit event when VaR exceeds limit") {
        coEvery { limitClient.getLimits() } returns ClientResponse.Success(
            listOf(aVarLimit(id = "lim-desk-1", limitValue = "100000")),
        )
        val publishedSlot = slot<org.apache.kafka.clients.producer.ProducerRecord<String, String>>()
        every { producer.send(capture(publishedSlot)) } returns givenFuture()

        service.checkLimits(aCrossBookResult(varValue = 120_000.0))

        verify(atLeast = 1) { producer.send(any()) }
        val decoded = Json { ignoreUnknownKeys = true }
            .decodeFromString<GovernanceAuditEvent>(publishedSlot.captured.value())
        decoded.eventType shouldBe AuditEventType.LIMIT_BREACHED
        decoded.limitId shouldBe "lim-desk-1"
        decoded.bookId shouldBe "desk-alpha"
    }

    test("does not publish audit event when VaR is only at WARNING level") {
        coEvery { limitClient.getLimits() } returns ClientResponse.Success(
            listOf(aVarLimit(limitValue = "100000")),
        )

        service.checkLimits(aCrossBookResult(varValue = 85_000.0))

        verify(exactly = 0) { producer.send(any()) }
    }

    test("does not publish audit event when VaR is within limit") {
        coEvery { limitClient.getLimits() } returns ClientResponse.Success(
            listOf(aVarLimit(limitValue = "100000")),
        )

        service.checkLimits(aCrossBookResult(varValue = 50_000.0))

        verify(exactly = 0) { producer.send(any()) }
    }

    test("publishes LIMIT_BREACHED with correct limit ID when multiple limits exist") {
        coEvery { limitClient.getLimits() } returns ClientResponse.Success(
            listOf(
                aVarLimit(id = "lim-desk-1", entityId = "desk-alpha", limitValue = "100000", level = "DESK"),
                aVarLimit(id = "lim-firm-1", entityId = "FIRM", limitValue = "80000", level = "FIRM"),
            ),
        )
        val publishedSlots = mutableListOf<org.apache.kafka.clients.producer.ProducerRecord<String, String>>()
        every { producer.send(capture(publishedSlots)) } returns givenFuture()

        service.checkLimits(aCrossBookResult(varValue = 120_000.0))

        verify(exactly = 2) { producer.send(any()) }
        val eventTypes = publishedSlots.map {
            Json { ignoreUnknownKeys = true }
                .decodeFromString<GovernanceAuditEvent>(it.value()).eventType
        }
        eventTypes.all { it == AuditEventType.LIMIT_BREACHED } shouldBe true
    }

    test("carries the correlationId from the MDC on the LIMIT_BREACHED governance event") {
        coEvery { limitClient.getLimits() } returns ClientResponse.Success(
            listOf(aVarLimit(id = "lim-desk-1", limitValue = "100000")),
        )
        val publishedSlot = slot<org.apache.kafka.clients.producer.ProducerRecord<String, String>>()
        every { producer.send(capture(publishedSlot)) } returns givenFuture()

        MDC.put("correlationId", "corr-xbook-456")
        service.checkLimits(aCrossBookResult(varValue = 120_000.0))

        val decoded = Json { ignoreUnknownKeys = true }
            .decodeFromString<GovernanceAuditEvent>(publishedSlot.captured.value())
        decoded.correlationId shouldBe "corr-xbook-456"
    }
})

package com.kinetix.audit.kafka

import com.kinetix.audit.model.AuditEvent
import com.kinetix.audit.persistence.AuditEventRepository
import com.kinetix.common.audit.AuditEventType
import com.kinetix.common.audit.GovernanceAuditEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import java.time.Duration

class GovernanceAuditEventConsumerTest : FunSpec({

    val repository = mockk<AuditEventRepository>()

    beforeEach {
        clearMocks(repository)
    }

    fun makeConsumerWithOneRecord(json: String): KafkaConsumer<String, String> {
        val consumer = mockk<KafkaConsumer<String, String>>()
        val partition = TopicPartition("governance.audit", 0)
        val record = ConsumerRecord("governance.audit", 0, 0L, "key", json)
        val records = ConsumerRecords(mapOf(partition to listOf(record)))
        val emptyRecords = ConsumerRecords<String, String>(emptyMap())

        var callCount = 0
        every { consumer.subscribe(any<List<String>>()) } returns Unit
        every { consumer.poll(any<Duration>()) } answers {
            if (callCount++ == 0) records else emptyRecords
        }
        every { consumer.commitSync() } returns Unit
        every { consumer.close(any<Duration>()) } returns Unit
        return consumer
    }

    test("persists governance audit event with model name when MODEL_STATUS_CHANGED received") {
        val event = GovernanceAuditEvent(
            eventType = AuditEventType.MODEL_STATUS_CHANGED,
            userId = "user-1",
            userRole = "RISK_MANAGER",
            modelName = "VaR-v2",
            details = "DRAFT->VALIDATED",
        )
        val json = Json.encodeToString(event)
        val consumer = makeConsumerWithOneRecord(json)

        val savedSlot = slot<AuditEvent>()
        coEvery { repository.save(capture(savedSlot)) } returns Unit

        val governanceConsumer = GovernanceAuditEventConsumer(consumer, repository)
        val job = launch { governanceConsumer.start() }
        withTimeout(3000) {
            while (!savedSlot.isCaptured) delay(50)
        }
        job.cancel()

        coVerify(exactly = 1) { repository.save(any()) }
        val saved = savedSlot.captured
        saved.eventType shouldBe "MODEL_STATUS_CHANGED"
        saved.userId shouldBe "user-1"
        saved.userRole shouldBe "RISK_MANAGER"
        saved.modelName shouldBe "VaR-v2"
        saved.details shouldBe "DRAFT->VALIDATED"
        saved.tradeId shouldBe null
        saved.instrumentId shouldBe null
    }

    test("persists governance audit event with scenarioId when SCENARIO_APPROVED received") {
        val event = GovernanceAuditEvent(
            eventType = AuditEventType.SCENARIO_APPROVED,
            userId = "approver-1",
            userRole = "HEAD_OF_RISK",
            scenarioId = "sc-abc",
            details = "approved by risk committee",
        )
        val json = Json.encodeToString(event)
        val consumer = makeConsumerWithOneRecord(json)

        val savedSlot = slot<AuditEvent>()
        coEvery { repository.save(capture(savedSlot)) } returns Unit

        val governanceConsumer = GovernanceAuditEventConsumer(consumer, repository)
        val job = launch { governanceConsumer.start() }
        withTimeout(3000) {
            while (!savedSlot.isCaptured) delay(50)
        }
        job.cancel()

        val saved = savedSlot.captured
        saved.eventType shouldBe "SCENARIO_APPROVED"
        saved.scenarioId shouldBe "sc-abc"
        saved.tradeId shouldBe null
    }

    test("persists governance audit event with the inbound correlationId carried onto the stored record") {
        val event = GovernanceAuditEvent(
            eventType = AuditEventType.RISK_CALCULATION_COMPLETED,
            userId = "system",
            userRole = "SYSTEM",
            bookId = "book-A",
            details = "VaR run complete",
            correlationId = "corr-xyz-123",
        )
        val json = Json.encodeToString(event)
        val consumer = makeConsumerWithOneRecord(json)

        val savedSlot = slot<AuditEvent>()
        coEvery { repository.save(capture(savedSlot)) } returns Unit

        val governanceConsumer = GovernanceAuditEventConsumer(consumer, repository)
        val job = launch { governanceConsumer.start() }
        withTimeout(3000) {
            while (!savedSlot.isCaptured) delay(50)
        }
        job.cancel()

        val saved = savedSlot.captured
        saved.eventType shouldBe "RISK_CALCULATION_COMPLETED"
        saved.correlationId shouldBe "corr-xyz-123"
    }

    test("persists governance audit event with RBAC_ACCESS_DENIED event type") {
        val event = GovernanceAuditEvent(
            eventType = AuditEventType.RBAC_ACCESS_DENIED,
            userId = "attacker",
            userRole = "UNKNOWN",
            details = "attempted POST /api/v1/admin/reset",
        )
        val json = Json.encodeToString(event)
        val consumer = makeConsumerWithOneRecord(json)

        val savedSlot = slot<AuditEvent>()
        coEvery { repository.save(capture(savedSlot)) } returns Unit

        val governanceConsumer = GovernanceAuditEventConsumer(consumer, repository)
        val job = launch { governanceConsumer.start() }
        withTimeout(3000) {
            while (!savedSlot.isCaptured) delay(50)
        }
        job.cancel()

        val saved = savedSlot.captured
        saved.eventType shouldBe "RBAC_ACCESS_DENIED"
        saved.userId shouldBe "attacker"
        saved.details shouldBe "attempted POST /api/v1/admin/reset"
    }
})
